package com.pockettravel.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

// Le risposte registrate in resources/online (vedi il README): ognuna, passata al client, da' il risultato atteso.
class OnlineRecordedStreamsTest {

    private val apis = mapOf("chat-completions" to OnlineApi.CHAT_COMPLETIONS, "anthropic-messages" to OnlineApi.ANTHROPIC_MESSAGES)

    @Test
    fun `every recorded response gives its expected result`() {
        val root = File(requireNotNull(javaClass.getResource("/online")) { "mancano le risposte registrate" }.toURI())
        val fixtures = apis.flatMap { (dir, api) ->
            File(root, dir).listFiles().orEmpty().filter { !it.name.endsWith(".expected.txt") }.map { api to it }
        }
        assertTrue("poche risposte registrate", fixtures.size >= apis.size * 2)
        fixtures.forEach { (api, file) -> check(api, file) }
    }

    private fun check(api: OnlineApi, file: File) {
        val expected = File(file.parentFile, file.nameWithoutExtension + ".expected.txt").readLines()
            .filter { it.isNotBlank() }
            .associate { it.substringBefore(": ") to it.substringAfter(": ") }
        if ("text" !in expected && "error" !in expected) fail("${file.name}: manca il risultato atteso")
        val status = expected["status"]?.toInt() ?: 200
        val server = MockWebServer()
        server.start()
        try {
            val response = MockResponse().setResponseCode(status).setBody(file.readText())
            if (status == 200) response.setHeader("Content-Type", "text/event-stream") else response.setHeader("Retry-After", "60")
            expected.filterKeys { it.startsWith("header-") }.forEach { (key, value) -> response.setHeader(key.removePrefix("header-"), value) }
            server.enqueue(response)
            val client = OnlineLlmClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, Dispatchers.IO)
            val outcome = runCatching {
                runBlocking { client.stream(server.url("/v1").toString(), "k", "m", "ciao", sendTemperature = false, api = api).toList() }
            }
            assertEquals("${file.name}: errore", expected["error"], outcome.exceptionOrNull()?.javaClass?.simpleName)
            expected["text"]?.let { assertEquals("${file.name}: testo", it, outcome.getOrNull()?.joinToString("")) }
        } finally {
            server.shutdown()
        }
    }
}
