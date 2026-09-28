package com.demo.analytics.internal

import com.demo.analytics.AnalyticsEvent
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.GzipSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class HttpTransportTest {

    private val server = MockWebServer().apply { start() }
    private val transport = HttpTransport(server.url("/v1/events").toString())

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sendsGzippedJsonToTheEndpoint() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = transport.send(listOf(AnalyticsEvent("tap", mapOf("screen" to "home"))))

        assertEquals(SendResult.Success, result)
        val request = server.takeRequest()
        assertEquals("/v1/events", request.path)
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        val json = JsonParser.parseString(GzipSource(request.body).buffer().readUtf8()).asJsonObject
        val event = json.getAsJsonArray("events")[0].asJsonObject
        assertEquals("tap", event["name"].asString)
        assertEquals("home", event["props"].asJsonObject["screen"].asString)
        assertEquals(setOf("id", "name", "ts", "props"), event.keySet())
    }

    @Test
    fun serverErrorIsRetryableWithRetryAfter() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "2"))

        assertEquals(SendResult.Retryable(retryAfterMs = 2_000), transport.send(listOf(AnalyticsEvent("e"))))
    }

    @Test
    fun rateLimitIsRetryable() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        assertEquals(SendResult.Retryable(), transport.send(listOf(AnalyticsEvent("e"))))
    }

    @Test
    fun badRequestIsFatal() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        assertEquals(SendResult.Fatal, transport.send(listOf(AnalyticsEvent("e"))))
    }

    @Test(expected = IOException::class)
    fun droppedConnectionThrows() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        transport.send(listOf(AnalyticsEvent("e")))
    }

    @Test
    fun connectionIsReusedAcrossSends() = runTest {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200)) }

        transport.send(listOf(AnalyticsEvent("a")))
        transport.send(listOf(AnalyticsEvent("b")))

        server.takeRequest()
        assertEquals(1, server.takeRequest().sequenceNumber) // second request on the same socket
    }
}
