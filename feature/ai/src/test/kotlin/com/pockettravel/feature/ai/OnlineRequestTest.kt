package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Il corpo della richiesta: un messaggio system (istruzioni e contesto) e uno user (la domanda).
class OnlineRequestTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OnlineLlmClient(OkHttpClient(), json, Dispatchers.IO)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun sentMessages(system: String?): List<Pair<String, String>> {
        server.enqueue(sseResponse("ok"))
        val answer = runBlocking {
            client.answerText(server.url("/v1").toString(), apiKey = "k", model = "m", prompt = "Cosa vedere a Roma?", sendTemperature = true, system = system)
        }
        assertEquals("ok", answer)
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        return body.getValue("messages").jsonArray.map {
            it.jsonObject.getValue("role").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content
        }
    }

    @Test
    fun `the request has one system message and then the user question`() {
        val system = OnlinePromptTemplates.systemPrompt(OnlinePromptTemplates.contextBlock("Wikivoyage, sezione Cosa vedere", "Il Colosseo"))

        assertEquals(listOf("system" to system, "user" to "Cosa vedere a Roma?"), sentMessages(system))
    }

    @Test
    fun `without a system message the request is the user question alone`() {
        assertEquals(listOf("user" to "Cosa vedere a Roma?"), sentMessages(null))
    }
}
