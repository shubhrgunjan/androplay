package app.androplay.core

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

/**
 * Just enough S3 for AndroPlay (path-style, SigV4): works with Cloudflare R2, AWS S3, MinIO,
 * Backblaze B2. Objects are keyed by content id, so names never need URL escaping.
 */
class S3(config: BackendConfig, private val http: OkHttpClient) {
    private val cfg = config.normalized()
    class S3Error(val code: Int, val body: String) : IOException("S3 $code: ${body.take(300)}")

    private val endpoint: HttpUrl = cfg.s3Endpoint.toHttpUrl()
    private val region = cfg.s3Region.ifBlank { "auto" }

    fun putObject(key: String, bytes: ByteArray, contentType: String) {
        execute(sign("PUT", key, emptyMap(), bytes).put(bytes.toRequestBody(contentType.toMediaTypeOrNull())).build()).close()
    }

    fun createMultipart(key: String, contentType: String): String {
        val req = sign("POST", key, mapOf("uploads" to ""), EMPTY)
            .header("Content-Type", contentType)
            .post(EMPTY.toRequestBody(null))
            .build()
        val body = execute(req).use { it.body?.string().orEmpty() }
        return Regex("<UploadId>([^<]+)</UploadId>").find(body)?.groupValues?.get(1)
            ?: throw IOException("No UploadId in response")
    }

    /** Returns the part's ETag, needed to complete the upload. */
    fun uploadPart(key: String, uploadId: String, partNumber: Int, bytes: ByteArray): String {
        val query = mapOf("partNumber" to partNumber.toString(), "uploadId" to uploadId)
        val req = sign("PUT", key, query, bytes).put(bytes.toRequestBody(null)).build()
        return execute(req).use { it.header("ETag") ?: throw IOException("Part $partNumber has no ETag") }
    }

    fun completeMultipart(key: String, uploadId: String, parts: List<Pair<Int, String>>) {
        val xml = buildString {
            append("<CompleteMultipartUpload>")
            parts.sortedBy { it.first }.forEach { (n, etag) ->
                append("<Part><PartNumber>").append(n).append("</PartNumber><ETag>").append(etag).append("</ETag></Part>")
            }
            append("</CompleteMultipartUpload>")
        }.encodeToByteArray()
        val req = sign("POST", key, mapOf("uploadId" to uploadId), xml)
            .post(xml.toRequestBody("application/xml".toMediaTypeOrNull()))
            .build()
        execute(req).use { r ->
            val body = r.body?.string().orEmpty()
            // S3 can return 200 with an <Error> body for CompleteMultipartUpload.
            if ("<Error>" in body) throw S3Error(500, body)
        }
    }

    fun delete(key: String) {
        runCatching { execute(sign("DELETE", key, emptyMap(), EMPTY).delete().build()).close() }
    }

    /** Ranged GET for resumable downloads. Caller closes the response. */
    fun get(key: String, fromByte: Long = 0): Response {
        val b = sign("GET", key, emptyMap(), EMPTY)
        if (fromByte > 0) b.header("Range", "bytes=$fromByte-")
        return execute(b.get().build())
    }

    /** Time-limited public URL, e.g. for thumbnails loaded by the image loader. */
    fun presignGet(key: String, expiresSeconds: Int = 3600): String {
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
        val canonical = listOf("GET", path(key), canonicalQuery, "host:${hostHeader()}\n", "host", "UNSIGNED-PAYLOAD")
            .joinToString("\n")
        val signature = signature(day, amzDate, scope, canonical)
        return objectUrl(key).newBuilder().encodedQuery("$canonicalQuery&X-Amz-Signature=$signature").build().toString()
    }

    private fun sign(method: String, key: String, query: Map<String, String>, payload: ByteArray): Request.Builder {
        val amzDate = amzFormat().format(Date())
        val day = amzDate.take(8)
        val scope = "$day/$region/s3/aws4_request"
        val payloadHash = sha256(payload).hex()
        val canonicalQuery = canonicalQuery(query.toSortedMap())
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonical = listOf(
            method, path(key), canonicalQuery,
            "host:${hostHeader()}\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n",
            signedHeaders, payloadHash,
        ).joinToString("\n")
        val signature = signature(day, amzDate, scope, canonical)
        val url = objectUrl(key).newBuilder().encodedQuery(canonicalQuery.ifEmpty { null }).build()
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

    private fun objectUrl(key: String): HttpUrl =
        endpoint.newBuilder().addPathSegment(cfg.bucket).addPathSegments(key).build()

    private fun path(key: String) = objectUrl(key).encodedPath

    private fun hostHeader(): String =
        if (endpoint.port == HttpUrl.defaultPort(endpoint.scheme)) endpoint.host else "${endpoint.host}:${endpoint.port}"

    private fun canonicalQuery(q: Map<String, String>) =
        q.entries.joinToString("&") { "${awsEncode(it.key)}=${awsEncode(it.value)}" }

    private companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
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
