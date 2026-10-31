package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Lo streaming delle risposte online: fine con finish_reason o con [DONE], risposte troncate, errori a meta'.
class OnlineStreamTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OnlineLlmClient(OkHttpClient(), json, Dispatchers.IO)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun pieces(): List<String> = runBlocking {
        client.stream(server.url("/v1").toString(), apiKey = "k", model = "m", prompt = "ciao", sendTemperature = false).toList()
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

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test
    fun `pieces arrive in order and the request asks for streaming`() {
        server.enqueue(sseResponse("La capitale", " di Malta", " è La Valletta."))
        assertEquals(listOf("La capitale", " di Malta", " è La Valletta."), pieces())
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
    }

    @Test
    fun `Gemini ends with DONE alone and sends chunks without text`() {
        // Come Gemini il 2026-10-31: nessun finish_reason, un pezzo con la sola thought_signature, poi [DONE].
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"La\",\"role\":\"assistant\"},\"index\":0}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\" Valletta.\",\"role\":\"assistant\"},\"index\":0}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"extra_content\":{\"google\":{\"thought_signature\":\"x\"}}},\"index\":0}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        assertEquals("La Valletta.", pieces().joinToString(""))
    }

    @Test
    fun `finish_reason without DONE is a complete answer`() {
        server.enqueue(sseResponse("Risposta", done = false))
        assertEquals(listOf("Risposta"), pieces())
    }

    @Test
    fun `event lines and comments are skipped`() {
        server.enqueue(sse(": ping\n\nevent: delta\n" + sseBody("ok")))
        assertEquals(listOf("ok"), pieces())
    }

    @Test
    fun `a connection closed after some text is an interrupted answer, not retried`() {
        server.enqueue(sseResponse("Mezza ", "risposta", finishReason = null, done = false))
        server.enqueue(sseResponse("Seconda"))
        assertEquals(R.string.ai_error_interrupted, askErrorMessage(errorOf()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a connection closed before any text is retried once`() {
        server.enqueue(sse(""))
        server.enqueue(sseResponse("Risposta"))
        assertEquals(listOf("Risposta"), pieces())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an error in the middle of the stream is an interrupted answer`() {
        server.enqueue(sse(sseBody("Inizio", finishReason = null, done = false) + "data: {\"error\":{\"code\":529,\"type\":\"overloaded_error\"}}\n\n"))
        assertEquals(R.string.ai_error_interrupted, askErrorMessage(errorOf()))
    }

    @Test
    fun `an overload before any text is retried, then answers`() {
        server.enqueue(sse("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n"))
        server.enqueue(sseResponse("Risposta"))
        assertEquals(listOf("Risposta"), pieces())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a daily quota error in the stream, as a Gemini list, is credit exhausted`() {
        val body = "[{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"violations\":[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}]"
        server.enqueue(sse("data: $body\n\n"))
        assertEquals(R.string.ai_error_credit, askErrorMessage(errorOf()))
        assertEquals(1, server.requestCount)
    }
}
