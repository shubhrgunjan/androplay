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
) {
    val isComplete: Boolean
        get() = listOf(redisUrl, s3Endpoint, bucket, accessKey, secretKey).all { it.isNotBlank() }

    fun toCode(): String =
        Base64.encodeToString(ProtocolJson.encodeToString(this).encodeToByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)

    companion object {
        fun fromCode(code: String): BackendConfig? = runCatching {
            ProtocolJson.decodeFromString<BackendConfig>(Base64.decode(code.trim(), Base64.URL_SAFE).decodeToString())
        }.getOrNull()

        val buildDefault = BackendConfig(
            redisUrl = BuildConfig.DEFAULT_REDIS_URL,
            s3Endpoint = BuildConfig.DEFAULT_S3_ENDPOINT,
            s3Region = BuildConfig.DEFAULT_S3_REGION.ifBlank { "auto" },
            bucket = BuildConfig.DEFAULT_S3_BUCKET,
            accessKey = BuildConfig.DEFAULT_S3_ACCESS_KEY,
            secretKey = BuildConfig.DEFAULT_S3_SECRET_KEY,
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
        get() = sp.getString("config", null)
            ?.let { runCatching { ProtocolJson.decodeFromString<BackendConfig>(it) }.getOrNull() }
            ?: BackendConfig.buildDefault
        set(value) = sp.edit().putString("config", ProtocolJson.encodeToString(value)).apply()
}
