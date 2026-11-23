package app.androplay.core

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Just enough S3 for AndroPlay (path-style, SigV4): works with Cloudflare R2, AWS S3, MinIO,
 * Backblaze B2. Objects are keyed by content id, so names never need URL escaping.
 *
 * Reads try the primary bucket, then each fallback, on any failure (cap, 403, connection).
 * Writes land on every bucket so a later failover still has the object.
 */
class S3(config: BackendConfig, private val http: OkHttpClient) {
    private val cfg = config.normalized()
    class S3Error(val code: Int, val body: String) : IOException(friendly(code, body)) {
        companion object {
            fun friendly(code: Int, body: String): String {
                val err = Regex("<Code>([^<]+)</Code>").find(body)?.groupValues?.get(1)
                val msg = Regex("<Message>([^<]+)</Message>").find(body)?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
                    ?.replace("&lt;", "<")
                    ?.replace("&gt;", ">")
                val cap = msg?.contains("cap exceeded", ignoreCase = true) == true ||
                    msg?.contains("bandwidth", ignoreCase = true) == true
                return when {
                    cap -> "Backblaze download cap is used up on every bucket. Install from the lab Mac with adb, or raise the cap in Backblaze → Caps & Alerts."
                    !msg.isNullOrBlank() -> "Storage $code${err?.let { " $it" } ?: ""}: $msg"
                    else -> "S3 $code: ${body.take(160)}"
                }
            }
        }
    }

    private val endpoint: HttpUrl = cfg.s3Endpoint.toHttpUrl()
    private val region = cfg.s3Region.ifBlank { "auto" }
    private val buckets: List<String> = cfg.storageBuckets()
    @Volatile private var healthyIndex = 0
    @Volatile private var writeBucket: String? = null

    fun putObject(key: String, bytes: ByteArray, contentType: String) {
        writeAll { putObjectOn(it, key, bytes, contentType) }
    }

    fun createMultipart(key: String, contentType: String): String {
        val (bucket, id) = firstWorking { b -> b to createMultipartOn(b, key, contentType) }
        writeBucket = bucket
        return id
    }

    /** Returns the part's ETag, needed to complete the upload. */
    fun uploadPart(key: String, uploadId: String, partNumber: Int, bytes: ByteArray): String =
        uploadPartOn(writeBucket ?: buckets.first(), key, uploadId, partNumber, bytes)

    fun completeMultipart(key: String, uploadId: String, parts: List<Pair<Int, String>>) {
        completeMultipartOn(writeBucket ?: buckets.first(), key, uploadId, parts)
    }

    /**
     * Best-effort copy onto every bucket except the one [createMultipart] already used.
     * Call after a successful primary upload so fallbacks can serve the same key.
     */
    fun mirror(key: String, contentType: String, size: Long, read: (offset: Long, length: Int) -> ByteArray) {
        val skip = writeBucket
        for (bucket in buckets) {
            if (bucket == skip) continue
            runCatching { putOn(bucket, key, contentType, size, read) }
                .onFailure { Log.w(TAG, "mirror $key → $bucket failed", it) }
        }
    }

    fun delete(key: String) {
        for (bucket in buckets) {
            runCatching { execute(sign("DELETE", bucket, key, emptyMap(), EMPTY).delete().build()).close() }
        }
    }

    /** Ranged GET for resumable downloads. Caller closes the response. */
    fun get(key: String, fromByte: Long = 0): Response = firstWorking { bucket ->
        val b = sign("GET", bucket, key, emptyMap(), EMPTY)
        if (fromByte > 0) b.header("Range", "bytes=$fromByte-")
        execute(b.get().build())
    }

    /** Time-limited public URL, e.g. for thumbnails loaded by the image loader. */
    fun presignGet(key: String, expiresSeconds: Int = 3600): String {
        val bucket = buckets.getOrElse(healthyIndex) { buckets.first() }
        val now = Date()
        val amzDate = amzFormat().format(now)
        val day = amzDate.take(8)
        val scope = "$day/$region/s3/aws4_request"
        val query = sortedMapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "${cfg.accessKey}/$scope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        val canonicalQuery = canonicalQuery(query)
        val canonical = listOf("GET", path(bucket, key), canonicalQuery, "host:${hostHeader()}\n", "host", "UNSIGNED-PAYLOAD")
            .joinToString("\n")
        val signature = signature(day, amzDate, scope, canonical)
        return objectUrl(bucket, key).newBuilder().encodedQuery("$canonicalQuery&X-Amz-Signature=$signature").build().toString()
    }

    private fun putObjectOn(bucket: String, key: String, bytes: ByteArray, contentType: String) {
        execute(sign("PUT", bucket, key, emptyMap(), bytes).put(bytes.toRequestBody(contentType.toMediaTypeOrNull())).build()).close()
    }

    private fun createMultipartOn(bucket: String, key: String, contentType: String): String {
        val req = sign("POST", bucket, key, mapOf("uploads" to ""), EMPTY)
            .header("Content-Type", contentType)
            .post(EMPTY.toRequestBody(null))
            .build()
        val body = execute(req).use { it.body?.string().orEmpty() }
        return Regex("<UploadId>([^<]+)</UploadId>").find(body)?.groupValues?.get(1)
            ?: throw IOException("No UploadId in response")
    }

    private fun uploadPartOn(bucket: String, key: String, uploadId: String, partNumber: Int, bytes: ByteArray): String {
        val query = mapOf("partNumber" to partNumber.toString(), "uploadId" to uploadId)
        val req = sign("PUT", bucket, key, query, bytes).put(bytes.toRequestBody(null)).build()
        return execute(req).use { it.header("ETag") ?: throw IOException("Part $partNumber has no ETag") }
    }

    private fun completeMultipartOn(bucket: String, key: String, uploadId: String, parts: List<Pair<Int, String>>) {
        val xml = buildString {
            append("<CompleteMultipartUpload>")
            parts.sortedBy { it.first }.forEach { (n, etag) ->
                append("<Part><PartNumber>").append(n).append("</PartNumber><ETag>").append(etag).append("</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }.encodeToByteArray()
        val req = sign("POST", bucket, key, mapOf("uploadId" to uploadId), xml)
            .post(xml.toRequestBody("application/xml".toMediaTypeOrNull()))
            .build()
        execute(req).use { r ->
            val body = r.body?.string().orEmpty()
            if ("<Error>" in body) throw S3Error(500, body)
        }
    }

    private fun putOn(bucket: String, key: String, contentType: String, size: Long, read: (offset: Long, length: Int) -> ByteArray) {
        if (size <= PART) {
            putObjectOn(bucket, key, read(0, size.toInt()), contentType)
            return
        }
        val uploadId = createMultipartOn(bucket, key, contentType)
        val parts = ArrayList<Pair<Int, String>>()
        val total = ((size + PART - 1) / PART).toInt()
        for (n in 1..total) {
            val offset = (n - 1).toLong() * PART
            val len = min(PART.toLong(), size - offset).toInt()
            parts += n to uploadPartOn(bucket, key, uploadId, n, read(offset, len))
        }
        completeMultipartOn(bucket, key, uploadId, parts)
    }

    private fun writeAll(block: (String) -> Unit) {
        var last: Exception? = null
        var ok = false
        for (bucket in buckets) {
            try {
                block(bucket)
                ok = true
                writeBucket = bucket
            } catch (e: Exception) {
                Log.w(TAG, "$bucket write failed", e)
                last = e
            }
        }
        if (!ok) throw last ?: IOException("No storage buckets configured")
    }

    private fun <T> firstWorking(block: (String) -> T): T {
        var lastNon404: Exception? = null
        var last404: Exception? = null
        val n = buckets.size
        val start = if (n == 0) 0 else healthyIndex.coerceIn(0, n - 1)
        for (i in 0 until n) {
            val idx = (start + i) % n
            val bucket = buckets[idx]
            try {
                return block(bucket).also {
                    if (idx != start) Log.i(TAG, "failed over to $bucket")
                    healthyIndex = idx
                }
            } catch (e: Exception) {
                Log.w(TAG, "$bucket failed: ${e.message}")
                if (e is S3Error && e.code == 404) last404 = e else lastNon404 = e
            }
        }
        throw lastNon404 ?: last404 ?: IOException("No storage buckets configured")
    }

    private fun sign(method: String, bucket: String, key: String, query: Map<String, String>, payload: ByteArray): Request.Builder {
        val amzDate = amzFormat().format(Date())
        val day = amzDate.take(8)
        val scope = "$day/$region/s3/aws4_request"
        val payloadHash = sha256(payload).hex()
        val canonicalQuery = canonicalQuery(query.toSortedMap())
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonical = listOf(
            method, path(bucket, key), canonicalQuery,
            "host:${hostHeader()}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n",
            signedHeaders, payloadHash,
        ).joinToString("\n")
        val signature = signature(day, amzDate, scope, canonical)
        val url = objectUrl(bucket, key).newBuilder().encodedQuery(canonicalQuery.ifEmpty { null }).build()
        return Request.Builder()
            .url(url)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
            .header(
                "Authorization",
                "$ALGORITHM Credential=${cfg.accessKey}/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
            )
    }

    private fun signature(day: String, amzDate: String, scope: String, canonical: String): String {
        val toSign = "$ALGORITHM\n$amzDate\n$scope\n${sha256(canonical.encodeToByteArray()).hex()}"
        var k = hmac("AWS4${cfg.secretKey}".encodeToByteArray(), day)
        k = hmac(k, region)
        k = hmac(k, "s3")
        k = hmac(k, "aws4_request")
        return hmac(k, toSign).hex()
    }

    private fun execute(req: Request): Response {
        val r = http.newCall(req).execute()
        if (!r.isSuccessful) {
            val body = r.body?.string().orEmpty()
            r.close()
            throw S3Error(r.code, body)
        }
        return r
    }

    private fun objectUrl(bucket: String, key: String): HttpUrl =
        endpoint.newBuilder().addPathSegment(bucket).addPathSegments(key).build()

    private fun path(bucket: String, key: String) = objectUrl(bucket, key).encodedPath

    private fun hostHeader(): String =
        if (endpoint.port == HttpUrl.defaultPort(endpoint.scheme)) endpoint.host else "${endpoint.host}:${endpoint.port}"

    private fun canonicalQuery(q: Map<String, String>) =
        q.entries.joinToString("&") { "${awsEncode(it.key)}=${awsEncode(it.value)}" }

    private companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val PART = 8 * 1024 * 1024
        const val TAG = "S3"
        val EMPTY = ByteArray(0)

        fun amzFormat() = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.encodeToByteArray())

        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

        fun awsEncode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt().toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' || c == '.' || c == '~') append(c)
                else append('%').append("%02X".format(b))
            }
        }
    }
}
