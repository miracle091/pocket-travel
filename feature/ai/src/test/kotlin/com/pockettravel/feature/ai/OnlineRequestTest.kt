package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
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

    @Test
    fun `with a photo the user message has the text part and the JPEG as a data URI`() {
        server.enqueue(sseResponse("ok"))
        runBlocking {
            client.stream(server.url("/v1").toString(), apiKey = "k", model = "m", prompt = "Cosa dice il cartello?", sendTemperature = true, photo = byteArrayOf(1, 2, 3)).toList()
        }
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image_url"), parts.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("Cosa dice il cartello?", parts[0].getValue("text").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", parts[1].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `with a photo the Messages API gets a base64 image block before the text`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ANTHROPIC_OK))
        runBlocking {
            client.stream(
                server.url("/v1").toString(),
                apiKey = "k",
                model = "claude-haiku-5-5",
                prompt = "Cosa dice il cartello?",
                sendTemperature = false,
                api = OnlineApi.ANTHROPIC_MESSAGES,
                photo = byteArrayOf(1, 2, 3),
            ).toList()
        }
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("image", "text"), parts.map { it.getValue("type").jsonPrimitive.content })
        val source = parts[0].getValue("source").jsonObject
        assertEquals(listOf("base64", "image/jpeg", "AQID"), listOf("type", "media_type", "data").map { source.getValue(it).jsonPrimitive.content })
    }

    private companion object {
        const val ANTHROPIC_OK = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0," +
            "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
    }
}
