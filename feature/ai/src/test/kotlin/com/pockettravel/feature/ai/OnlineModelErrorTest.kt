package com.pockettravel.feature.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Un nome di modello online sbagliato deve arrivare all'utente come ai_error_model, non come
// l'errore generico: dal codice HTTP del servizio (OnlineLlmClient) al messaggio (askErrorMessage).
class OnlineModelErrorTest {

    private val server = MockWebServer()
    private val client = OnlineLlmClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun generate(): String = runBlocking {
        client.generate(server.url("/v1").toString(), apiKey = "test-key", model = "modello-inesistente", prompt = "ciao")
    }

    private fun errorOf(response: MockResponse): Exception {
        server.enqueue(response)
        try {
            generate()
        } catch (error: Exception) {
            return error
        }
        fail("era atteso un errore")
        throw AssertionError()
    }

    @Test
    fun `404 from the service is an unknown model`() {
        val error = errorOf(MockResponse().setResponseCode(404))
        assertEquals(R.string.ai_error_model, askErrorMessage(error))
    }

    @Test
    fun `400 that mentions the model is an unknown model`() {
        val error = errorOf(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"The model `x` does not exist"}}"""))
        assertEquals(R.string.ai_error_model, askErrorMessage(error))
    }

    @Test
    fun `400 about something else keeps the generic message`() {
        val error = errorOf(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"invalid request"}}"""))
        assertFalse(error is OnlineModelNotFoundException)
        assertEquals(R.string.ai_error_answer, askErrorMessage(error))
    }

    @Test
    fun `server error keeps the generic message`() {
        val error = errorOf(MockResponse().setResponseCode(500))
        assertEquals(R.string.ai_error_answer, askErrorMessage(error))
    }

    @Test
    fun `successful answer returns the text and sends the key as Bearer`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Risposta"}}]}"""))
        assertEquals("Risposta", generate())
        assertEquals("Bearer test-key", server.takeRequest().getHeader("Authorization"))
    }
}
