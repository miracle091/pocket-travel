package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

// Intestazioni dei limiti di richieste: lette solo dove cambiano il comportamento.
class OnlineRateLimitTest {

    private val now = 1_000_000L

    @Test
    fun `durations and RFC 3339 dates are read, anything else is ignored`() {
        assertEquals(now + 1_000, parseRateLimitReset("1s", now))
        assertEquals(now + 360_000, parseRateLimitReset("6m0s", now))
        assertEquals(now + 1_500, parseRateLimitReset("1.5s", now))
        assertEquals(now + 20, parseRateLimitReset("20ms", now))
        assertEquals(now + 3_600_000 + 60_000, parseRateLimitReset("1h1m", now))
        assertEquals(Instant.parse("2026-11-14T10:00:00Z").toEpochMilli(), parseRateLimitReset("2026-11-14T10:00:00Z", now))
        assertEquals(Instant.parse("2026-11-14T10:00:00Z").toEpochMilli(), parseRateLimitReset("2026-11-14T12:00:00+02:00", now))
        assertNull(parseRateLimitReset("presto", now))
        assertNull(parseRateLimitReset("", now))
        assertNull(parseRateLimitReset("12", now))
    }

    @Test
    fun `both services' headers give remaining requests and reset`() {
        val openAi = rateLimit(mapOf("x-ratelimit-remaining-requests" to "0", "x-ratelimit-reset-requests" to "1s")::get, now)
        assertEquals(0, openAi.remaining)
        assertEquals(now + 1_000, openAi.resetAtMillis)
        val anthropic = rateLimit(
            mapOf("anthropic-ratelimit-requests-remaining" to "7", "anthropic-ratelimit-requests-reset" to "2026-11-14T10:00:00Z")::get,
            now,
        )
        assertEquals(7, anthropic.remaining)
        assertEquals(Instant.parse("2026-11-14T10:00:00Z").toEpochMilli(), anthropic.resetAtMillis)
        val none = rateLimit({ null }, now)
        assertNull(none.remaining)
        assertNull(none.resetAtMillis)
    }

    @Test
    fun `the reset header sets the retry delay after Retry-After`() {
        assertEquals(3_000L, retryDelayMillis(null, "", now, now + 3_000))
        assertEquals(1_000L, retryDelayMillis("1", "", now, now + 3_000))
        assertNull(retryDelayMillis(null, "", now, now + 360_000))
    }

    @Test
    fun `a successful answer with no requests left reports when they come back`() {
        val resets = answerWith(mapOf("x-ratelimit-remaining-requests" to "0", "x-ratelimit-reset-requests" to "6m0s"))
        assertEquals(1, resets.size)
        assertEquals(true, resets.single() > System.currentTimeMillis() + 300_000)
        assertEquals(emptyList<Long>(), answerWith(mapOf("x-ratelimit-remaining-requests" to "5", "x-ratelimit-reset-requests" to "6m0s")))
        assertEquals(emptyList<Long>(), answerWith(emptyMap()))
    }

    @Test
    fun `a 429 with a far reset is a request limit, not the daily quota`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"rate_limit_exceeded"}}""").setHeader("x-ratelimit-reset-requests", "6m0s"))
            val client = OnlineLlmClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, Dispatchers.IO)
            val error = runCatching { runBlocking { client.stream(server.url("/v1").toString(), "k", "m", "ciao", sendTemperature = false).toList() } }
                .exceptionOrNull() as OnlineDailyQuotaException
            assertEquals(false, error.daily)
            assertEquals(true, error.resetAtMillis != null)
        } finally {
            server.shutdown()
        }
        assertEquals(true, OnlineDailyQuotaException("Gemini", "m").daily)
    }

    private fun answerWith(headers: Map<String, String>): List<Long> {
        val server = MockWebServer()
        server.start()
        try {
            val body = """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""" + "\n\n"
            server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream").also { r -> headers.forEach { r.setHeader(it.key, it.value) } })
            val resets = mutableListOf<Long>()
            val client = OnlineLlmClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, Dispatchers.IO)
            runBlocking {
                client.stream(server.url("/v1").toString(), "k", "m", "ciao", sendTemperature = false, onRequestsExhausted = { resets += it }).toList()
            }
            return resets
        } finally {
            server.shutdown()
        }
    }
}
