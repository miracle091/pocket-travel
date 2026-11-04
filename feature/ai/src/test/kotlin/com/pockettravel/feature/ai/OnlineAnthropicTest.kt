package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Claude sulla Messages API nativa: forma della richiesta, testo dagli eventi text_delta, errori riconosciuti dal tipo.
class OnlineAnthropicTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OnlineLlmClient(OkHttpClient(), json, Dispatchers.IO)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun pieces(): List<String> = runBlocking {
        client.stream(
            server.url("/v1").toString(),
            apiKey = "sk-ant-k",
            model = "claude-haiku-5-5",
            prompt = "ciao",
            sendTemperature = true,
            system = "Sei una guida",
            api = OnlineApi.ANTHROPIC_MESSAGES,
        ).toList()
    }

    private fun errorOf(): Exception {
        try {
            pieces()
        } catch (error: Exception) {
            return error
        }
        fail("era atteso un errore")
        throw AssertionError()
    }

    private fun event(type: String, data: String) = "event: $type\ndata: $data\n\n"

    private fun textDelta(text: String) =
        event("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"$text"}}""")

    private val start = event("message_start", """{"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}""") +
        event("ping", """{"type":"ping"}""")
    private val stop = event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""") +
        event("message_stop", """{"type":"message_stop"}""")

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun errorBody(type: String) = """{"type":"error","error":{"type":"$type","message":"m"}}"""

    @Test
    fun `request uses the native headers, system at top level, max_tokens and no temperature`() {
        server.enqueue(sse(start + textDelta("ok") + stop))
        pieces()
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("sk-ant-k", request.getHeader("x-api-key"))
        assertEquals("2026-10-21", request.getHeader("anthropic-version"))
        assertNull(request.getHeader("Authorization"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Sei una guida", body.getValue("system").jsonPrimitive.content)
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
        assertTrue(body.getValue("max_tokens").jsonPrimitive.int > 0)
        assertFalse("temperature" in body)
        val messages = body.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `text comes from text_delta only, thinking is skipped, message_stop ends the answer`() {
        val thinking = event(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}""",
        )
        server.enqueue(sse(start + thinking + textDelta("La Valletta") + textDelta(" è la capitale.") + stop))
        assertEquals(listOf("La Valletta", " è la capitale."), pieces())
    }

    @Test
    fun `billing_error is credit exhausted even with a 400`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody(errorBody("billing_error")))
        assertTrue(errorOf() is OnlineCreditExhaustedException)
    }

    @Test
    fun `rate_limit_error is a busy service`() {
        // Retry-After oltre i 10 s: nessun nuovo tentativo, l'errore arriva subito.
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody(errorBody("rate_limit_error")))
        assertTrue(errorOf() is OnlineServiceBusyException)
    }

    @Test
    fun `overloaded_error is retried once, then a busy service`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(529).setHeader("Retry-After", "0").setBody(errorBody("overloaded_error"))) }
        assertTrue(errorOf() is OnlineServiceBusyException)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `not_found_error is an unknown model`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody(errorBody("not_found_error")))
        assertTrue(errorOf() is OnlineModelNotFoundException)
    }

    @Test
    fun `authentication_error is a rejected key`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody(errorBody("authentication_error")))
        assertTrue(errorOf() is OnlineApiKeyRejectedException)
    }

    @Test
    fun `error event after some text is an interrupted answer`() {
        server.enqueue(sse(start + textDelta("La") + event("error", errorBody("overloaded_error"))))
        assertTrue(errorOf() is OnlineAnswerInterruptedException)
    }

    @Test
    fun `error event before any text is retried like an HTTP error`() {
        server.enqueue(sse(start + event("error", errorBody("overloaded_error"))))
        server.enqueue(sse(start + textDelta("ok") + stop))
        assertEquals(listOf("ok"), pieces())
    }

    @Test
    fun `stream closed without message_stop after text is an interrupted answer`() {
        server.enqueue(sse(start + textDelta("La")))
        assertTrue(errorOf() is OnlineAnswerInterruptedException)
    }
}
