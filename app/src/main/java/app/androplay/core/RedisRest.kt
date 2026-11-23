package app.androplay.core

import app.androplay.protocol.ProtocolJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTPS transport to the same logical Upstash database as the native Redis socket. */
class RedisRest(
    host: String,
    private val token: String,
    private val http: OkHttpClient = client,
) {
    private val base = HttpUrl.Builder().scheme("https").host(host).build()

    fun call(args: Array<out String>): Any? =
        result(execute(base, arguments(args)))

    fun batch(commands: List<Array<String>>, atomic: Boolean): List<Any?> {
        if (commands.isEmpty()) return emptyList()
        val url = base.newBuilder().addPathSegment(if (atomic) "multi-exec" else "pipeline").build()
        val response = execute(url, JsonArray(commands.map(::arguments)))
        if (response is JsonObject) {
            result(response)
            throw IOException("Redis HTTPS batch returned an invalid result")
        }
        val replies = response as? JsonArray ?: throw IOException("Redis HTTPS batch returned an invalid result")
        if (replies.size != commands.size) throw IOException("Redis HTTPS batch returned incomplete results")
        return replies.map(::result)
    }

    fun subscription(channels: List<String>): Call {
        val url = base.newBuilder().addPathSegment("subscribe").apply { channels.forEach(::addPathSegment) }.build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(ByteArray(0).toRequestBody())
            .build()
        return http.newCall(request)
    }

    private fun execute(url: HttpUrl, payload: JsonArray): JsonElement {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RedisError("Redis HTTPS request failed (HTTP ${response.code})")
            val body = response.body?.string() ?: throw IOException("Empty Redis HTTPS response")
            try {
                ProtocolJson.parseToJsonElement(body)
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid Redis HTTPS response", e)
            }
        }
    }

    private fun result(reply: JsonElement): Any? {
        val obj = reply as? JsonObject ?: throw IOException("Invalid Redis HTTPS result")
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
        if (error != null) throw RedisError(error)
        return value(obj["result"] ?: throw IOException("Missing Redis HTTPS result"))
    }

    private fun value(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonArray -> element.map(::value)
        is JsonPrimitive -> if (element.isString) element.content else element.longOrNull
            ?: throw IOException("Unexpected Redis HTTPS result type")
        is JsonObject -> throw IOException("Unexpected Redis HTTPS result type")
    }

    private fun arguments(args: Array<out String>) = JsonArray(args.map(::JsonPrimitive))

    companion object {
        private val JSON = "application/json".toMediaType()
        private val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun supports(url: RedisUrl): Boolean = url.tls && url.db == 0 && url.host.endsWith(".upstash.io", ignoreCase = true)
    }
}
