package app.androplay.core

import android.content.Context
import android.os.Build
import android.util.Base64
import app.androplay.BuildConfig
import app.androplay.protocol.ProtocolJson
import app.androplay.protocol.toHex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.util.UUID

enum class Role { BOARD, ADMIN }

/** Where everything lives. Shareable between devices as a single "setup code". */
@Serializable
data class BackendConfig(
    val redisUrl: String = "",
    val prefix: String = "ap:",
    val s3Endpoint: String = "",
    val s3Region: String = "auto",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val fallbackBuckets: List<String> = DEFAULT_FALLBACK_BUCKETS,
    /** Tried in order when the primary Redis is down or out of quota. */
    val fallbackRedisUrls: List<String> = emptyList(),
) {
    val isComplete: Boolean
        get() = listOf(redisUrl, s3Endpoint, bucket, accessKey, secretKey).all { it.isNotBlank() }

    /** Primary bucket first, then fallbacks (skips blanks and duplicates). */
    fun storageBuckets(): List<String> =
        (listOf(bucket) + fallbackBuckets.ifEmpty { DEFAULT_FALLBACK_BUCKETS })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Fixes common paste mistakes: repeated schemes ("https://https://…"), spaces, trailing slashes. */
    fun normalized(): BackendConfig {
        val raw = s3Endpoint.trim().replace(" ", "")
        val host = raw.substringAfterLast("://").trimEnd('/')
        val scheme = if (raw.startsWith("http://") && !raw.contains("https://")) "http" else "https"
        return copy(
            redisUrl = RedisUrl.normalize(redisUrl),
            s3Endpoint = if (host.isEmpty()) "" else "$scheme://$host",
            bucket = bucket.trim(),
            s3Region = s3Region.trim().ifEmpty { "auto" },
            accessKey = accessKey.trim(),
            secretKey = secretKey.trim(),
            fallbackBuckets = fallbackBuckets.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            fallbackRedisUrls = fallbackRedisUrls.map(RedisUrl::normalize).filter { it.isNotEmpty() }.distinct(),
        )
    }

    /**
     * A saved config that points at one of this build's Redis databases (e.g. the old primary)
     * follows the build's current primary and fallbacks, so retiring a database needs no setup.
     */
    fun withBuildRedis(): BackendConfig {
        val build = buildDefault
        val known = (listOf(build.redisUrl) + build.fallbackRedisUrls).filter { it.isNotBlank() }.map(::hostOf).toSet()
        if (build.redisUrl.isBlank() || hostOf(redisUrl) !in known) return this
        if (redisUrl == build.redisUrl && fallbackRedisUrls == build.fallbackRedisUrls) return this
        return copy(redisUrl = build.redisUrl, fallbackRedisUrls = build.fallbackRedisUrls)
    }

    fun toCode(): String =
        Base64.encodeToString(ProtocolJson.encodeToString(this).encodeToByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)

    companion object {
        fun fromCode(code: String): BackendConfig? = runCatching {
            ProtocolJson.decodeFromString<BackendConfig>(Base64.decode(code.trim(), Base64.URL_SAFE).decodeToString())
        }.getOrNull()

        val DEFAULT_FALLBACK_BUCKETS = listOf("androplay1", "androplay2", "androplay3")

        private fun hostOf(url: String): String = runCatching { RedisUrl.parse(url).host }.getOrDefault(url)

        val buildDefault = BackendConfig(
            redisUrl = BuildConfig.DEFAULT_REDIS_URL,
            s3Endpoint = BuildConfig.DEFAULT_S3_ENDPOINT,
            s3Region = BuildConfig.DEFAULT_S3_REGION.ifBlank { "auto" },
            bucket = BuildConfig.DEFAULT_S3_BUCKET,
            accessKey = BuildConfig.DEFAULT_S3_ACCESS_KEY,
            secretKey = BuildConfig.DEFAULT_S3_SECRET_KEY,
            fallbackBuckets = BuildConfig.DEFAULT_S3_FALLBACK_BUCKETS
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { DEFAULT_FALLBACK_BUCKETS },
            fallbackRedisUrls = BuildConfig.DEFAULT_REDIS_FALLBACK_URLS
                .split(',')
                .map { RedisUrl.normalize(it) }
                .filter { it.isNotEmpty() },
        )
    }
}

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("deviceId", null)
        ?: UUID.randomUUID().toString().take(8).also { sp.edit().putString("deviceId", it).apply() }

    /** Board only: secret phones must present over BLE. Shared with admins through Redis. */
    val bleToken: String = sp.getString("bleToken", null)
        ?: ByteArray(16).also(SecureRandom()::nextBytes).toHex().also { sp.edit().putString("bleToken", it).apply() }

    var role: Role?
        get() = sp.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        set(value) = sp.edit().putString("role", value?.name).apply()

    var deviceName: String
        get() = sp.getString("deviceName", null) ?: Build.MODEL
        set(value) = sp.edit().putString("deviceName", value).apply()

    var config: BackendConfig
        get() = (sp.getString("config", null)
            ?.let { runCatching { ProtocolJson.decodeFromString<BackendConfig>(it) }.getOrNull() }
            ?: BackendConfig.buildDefault).let { cfg ->
            if (cfg.fallbackBuckets.isEmpty()) cfg.copy(fallbackBuckets = BackendConfig.DEFAULT_FALLBACK_BUCKETS) else cfg
        }.withBuildRedis()
        set(value) = sp.edit().putString("config", ProtocolJson.encodeToString(value)).apply()
}
