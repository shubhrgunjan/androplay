package app.androplay.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLException

class RedisRestTest {
    @Test
    fun streamResultMatchesNativeRedisShape() {
        val redis = transport("/", """["XREAD","COUNT","200","STREAMS","ap:events","0-0"]""",
            """{"result":[["ap:events",[["3-0",["op","add","id","item"]]]]]}""")
        assertEquals(
            listOf(listOf("ap:events", listOf(listOf("3-0", listOf("op", "add", "id", "item"))))),
            redis.call(arrayOf("XREAD", "COUNT", "200", "STREAMS", "ap:events", "0-0")),
        )
    }

    @Test
    fun mutationBatchUsesAtomicEndpoint() {
        val redis = transport("/multi-exec", """[["SET","key","value"],["INCR","counter"]]""",
            """[{"result":"OK"},{"result":2}]""")
        assertEquals(listOf("OK", 2L), redis.batch(listOf(arrayOf("SET", "key", "value"), arrayOf("INCR", "counter")), true))
    }

    @Test
    fun fallbackRetainsHostAndRejectsNativeAuthOrTlsErrors() {
        val url = RedisUrl.parse("rediss://database.upstash.io:6379")
        val route = RedisRoute(url, "test-token")
        assertFalse(route.nativeUnavailable(RedisError("WRONGPASS invalid password")))
        assertFalse(route.nativeUnavailable(SSLException("invalid certificate")))
        assertFalse(route.useHttps)
        assertTrue(route.nativeUnavailable(IOException("native socket closed")))
        assertTrue(route.useHttps)
        assertEquals("database.upstash.io", route.rest!!.subscription(listOf("ap:board:one:cmd")).request().url.host)
    }

    private fun transport(path: String, jsonBody: String, responseJson: String): RedisRest {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https", request.url.scheme)
            assertEquals("database.upstash.io", request.url.host)
            assertEquals(path, request.url.encodedPath)
            assertEquals("Bearer test-token", request.header("Authorization"))
            val sent = Buffer()
            request.body!!.writeTo(sent)
            assertEquals(jsonBody, sent.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseJson.toResponseBody()).build()
        }.build()
        return RedisRest("database.upstash.io", "test-token", http)
    }
}
