package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// Credito esaurito, servizio occupato e un solo nuovo tentativo, con i corpi d'errore dei 4 servizi.
class OnlineErrorKindTest {

    private val server = MockWebServer()
    private val client = OnlineLlmClient(
        OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(),
        Json { ignoreUnknownKeys = true },
        Dispatchers.IO,
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun generate(): String = runBlocking {
        client.answerText(server.url("/v1").toString(), apiKey = "k", model = "m", prompt = "ciao", sendTemperature = true)
    }

    private fun errorOf(): Exception {
        try {
            generate()
        } catch (error: Exception) {
            return error
        }
        fail("era atteso un errore")
        throw AssertionError()
    }

    private val ok = sseResponse("Risposta")

    @Test
    fun `credit bodies of the four services are credit exhausted`() {
        val credit = listOf(
            429 to """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""",
            429 to """{"error":{"type":"insufficient_quota","code":"credit_balance_exhausted"}}""",
            429 to """{"error":{"code":"project_spend_limit_exceeded"}}""",
            402 to """{"type":"error","error":{"type":"billing_error","message":"x"}}""",
            400 to """{"error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}""",
            429 to """{"error":{"type":"rate_limit_error","details":{"error_code":"enforced_spend_limit_reached"}}}""",
            402 to """[{"error":{"code":402,"status":"RESOURCE_EXHAUSTED","message":"Your Prepay credit balance is depleted."}}]""",
            403 to """{"error":{"code":403,"status":"PERMISSION_DENIED","message":"Your billing account has an outstanding balance"}}""",
            429 to """{"error":{"status":"RESOURCE_EXHAUSTED","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""",
            402 to "Payment required",
        )
        credit.forEach { (code, body) -> assertEquals("$code $body", OnlineErrorKind.CREDIT, onlineErrorKind(code, body)) }
    }

    @Test
    fun `rate limits and overloads are temporary`() {
        val temporary = listOf(
            429 to """{"error":{"type":"rate_limit_error","code":"slow_down","message":"Rate limit reached for requests"}}""",
            503 to """{"error":{"type":"service_unavailable_error","code":"server_is_overloaded"}}""",
            529 to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            500 to "",
            504 to """{"error":{"status":"DEADLINE_EXCEEDED"}}""",
            429 to """{"object":"error","message":"Requests rate limit exceeded","type":"rate_limit_error"}""",
            // Gemini, chiave gratuita, 2026-10-31: limite al minuto, che parla anche di "billing details".
            429 to GEMINI_PER_MINUTE,
        )
        temporary.forEach { (code, body) -> assertEquals("$code $body", OnlineErrorKind.TEMPORARY, onlineErrorKind(code, body)) }
    }

    @Test
    fun `other client errors are permanent`() {
        assertEquals(OnlineErrorKind.PERMANENT, onlineErrorKind(400, """{"error":{"message":"invalid request"}}"""))
        assertEquals(OnlineErrorKind.PERMANENT, onlineErrorKind(422, """{"object":"error","type":"invalid_request_error"}"""))
    }

    @Test
    fun `retry delay comes from Retry-After, then from the body, then 2 s, and stops above 10 s`() {
        val now = 1_000_000_000_000L
        assertEquals(1_000L, retryDelayMillis("1", "", now))
        assertEquals(2_000L, retryDelayMillis(null, "", now))
        assertNull(retryDelayMillis("56", "", now))
        assertEquals(5_000L, retryDelayMillis("Sun, 09 Sep 2001 01:46:45 GMT", "", now))
        assertEquals(0L, retryDelayMillis("Sun, 09 Sep 2001 01:46:00 GMT", "", now))
        assertEquals(4_500L, retryDelayMillis(null, """{"details":[{"@type":"RetryInfo","retryDelay": "4.5s"}]}""", now))
        assertNull(retryDelayMillis(null, """{"retryDelay":"21s"}""", now))
        assertNull(retryDelayMillis(null, GEMINI_PER_MINUTE, now))
        assertEquals(2_000L, retryDelayMillis("non una data", "", now))
    }

    @Test
    fun `no network keeps the generic error, a timeout is temporary`() {
        assertTrue(transportError(UnknownHostException("api")) is UnknownHostException)
        assertTrue(transportError(SocketTimeoutException()) is OnlineServiceBusyException)
        assertTrue(transportError(IOException("unexpected end of stream")) is OnlineServiceBusyException)
    }

    @Test
    fun `credit exhausted is not retried and shows the credit message`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"type":"insufficient_quota"}}"""))
        server.enqueue(ok)
        val error = errorOf()
        assertEquals(R.string.ai_error_credit, askErrorMessage(error))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `503 with Retry-After 1 is retried once and then answers`() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
        server.enqueue(ok)
        val start = System.currentTimeMillis()
        assertEquals("Risposta", generate())
        assertTrue("attesa del Retry-After", System.currentTimeMillis() - start >= 900)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `two overloads in a row show the busy message after one retry`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(529).setHeader("Retry-After", "0")) }
        assertEquals(R.string.ai_error_busy, askErrorMessage(errorOf()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Retry-After above 10 s is not retried`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "56"))
        server.enqueue(ok)
        assertEquals(R.string.ai_error_busy, askErrorMessage(errorOf()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a timeout twice shows the busy message`() {
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        assertEquals(R.string.ai_error_busy, askErrorMessage(errorOf()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 403 with an outstanding balance is credit, a plain 403 is the key`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"billing account has an outstanding balance"}}"""))
        assertEquals(R.string.ai_error_credit, askErrorMessage(errorOf()))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"API key not valid"}}"""))
        assertEquals(R.string.ai_error_key_rejected, askErrorMessage(errorOf()))
    }

    private companion object {
        // Il corpo vero, accorciato, di un 429 di Gemini sul livello gratuito (endpoint compatibile, 2026-10-31).
        const val GEMINI_PER_MINUTE = """[{"error":{"code":429,"message":"You exceeded your current quota, please check your plan and """ +
            """billing details. Please retry in 16.6s.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/""" +
            """google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier",""" +
            """"quotaValue":"5"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"16s"}]}}]"""
    }
}
