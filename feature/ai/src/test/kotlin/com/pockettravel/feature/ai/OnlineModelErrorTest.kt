package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

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
    fun `400 with code model_not_found is an unknown model`() {
        val error = errorOf(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"The model `x` does not exist","code":"model_not_found"}}"""))
        assertEquals(R.string.ai_error_model, askErrorMessage(error))
    }

    @Test
    fun `400 with type model_not_found is an unknown model`() {
        val error = errorOf(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"x","type":"model_not_found"}}"""))
        assertEquals(R.string.ai_error_model, askErrorMessage(error))
    }

    @Test
    fun `400 that only mentions the model keeps the generic message`() {
        val error = errorOf(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid value for parameter of model request"}}"""))
        assertFalse(error is OnlineModelNotFoundException)
        assertEquals(R.string.ai_error_answer, askErrorMessage(error))
    }

    @Test
    fun `400 with a body that is not JSON keeps the generic message`() {
        val error = errorOf(MockResponse().setResponseCode(400).setBody("model not found"))
        assertEquals(R.string.ai_error_answer, askErrorMessage(error))
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
    fun `cancelling the coroutine cancels the call instead of waiting for the response`() = runBlocking {
        // Nessuna risposta per 10 s: senza call.cancel() il job annullato resterebbe bloccato nell'attesa.
        server.enqueue(MockResponse().setBody("""{"choices":[]}""").setHeadersDelay(10, TimeUnit.SECONDS))
        val job = launch(Dispatchers.Default) {
            client.generate(server.url("/v1").toString(), apiKey = "test-key", model = "m", prompt = "ciao")
        }
        server.takeRequest()
        val start = System.currentTimeMillis()
        job.cancelAndJoin()
        assertTrue("l'annullamento deve essere immediato", System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun `successful answer returns the text and sends the key as Bearer`() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Risposta"}}]}"""))
        assertEquals("Risposta", generate())
        assertEquals("Bearer test-key", server.takeRequest().getHeader("Authorization"))
    }
}
