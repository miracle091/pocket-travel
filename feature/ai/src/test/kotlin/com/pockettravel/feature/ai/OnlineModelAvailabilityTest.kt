package com.pockettravel.feature.ai

import com.pockettravel.core.sync.ONLINE_MODEL_ROLE_AUTO
import com.pockettravel.core.sync.ONLINE_MODEL_ROLE_CAPABLE
import com.pockettravel.core.sync.OnlineModelEntry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineModelAvailabilityTest {

    private fun model(id: String, role: String = ONLINE_MODEL_ROLE_CAPABLE) =
        OnlineModelEntry(id = id, role = role, temperature = true, inputUsdPerMTok = 1.0, outputUsdPerMTok = 1.0)

    @Test
    fun `legge i nomi dei modelli, anche quelli di Gemini con models-`() {
        val body = """{"object": "list", "data": [{"id": "gpt-6-luna"}, {"id": "models/gemini-3.8-flash"}, {"type": "model"}]}"""
        assertEquals(setOf("gpt-6-luna", "gemini-3.8-flash"), parseModelIds(Json, body))
    }

    @Test
    fun `nasconde i modelli che la chiave non vede, mai Automatico ne' i nomi -latest`() {
        val models = listOf(model("auto-x", ONLINE_MODEL_ROLE_AUTO), model("pro-y"), model("large-latest"), model("visto"))
        assertEquals(listOf("auto-x", "large-latest", "visto"), visibleModels(models, setOf("visto")).map { it.id })
        assertEquals(models, visibleModels(models, null))
    }

    @Test
    fun `un nome vale disponibile anche con la sola versione datata`() {
        val models = listOf(model("auto-x", ONLINE_MODEL_ROLE_AUTO), model("claude-sonnet-5-5"), model("claude-sonnet-5"))
        assertEquals(
            listOf("auto-x", "claude-sonnet-5-5"),
            visibleModels(models, setOf("claude-sonnet-5-5-20261001")).map { it.id },
        )
    }

    @Test(expected = java.io.IOException::class)
    fun `una risposta senza data e' un errore, non un elenco vuoto`() {
        parseModelIds(Json, """{"object": "list"}""")
    }

    @Test
    fun `Claude usa x-api-key e chiede tutti i modelli, gli altri servizi Bearer`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data": [{"id": "claude-haiku-5-5"}]}"""))
            server.enqueue(MockResponse().setBody("""{"data": [{"id": "gpt-6-luna"}]}"""))
            val availability = OnlineModelAvailability(OkHttpClient(), Json, StandardTestDispatcher(testScheduler))
            val base = server.url("/v1").toString()

            assertEquals(setOf("claude-haiku-5-5"), availability.availableModels(OnlineProvider.CLAUDE, "k1", base))
            val claude = server.takeRequest()
            assertEquals("/v1/models?limit=1000", claude.path)
            assertEquals("k1", claude.getHeader("x-api-key"))
            assertEquals(null, claude.getHeader("Authorization"))

            assertEquals(setOf("gpt-6-luna"), availability.availableModels(OnlineProvider.CHATGPT, "k2", base))
            val openAi = server.takeRequest()
            assertEquals("/v1/models", openAi.path)
            assertEquals("Bearer k2", openAi.getHeader("Authorization"))
        }
    }

    @Test(expected = java.io.IOException::class)
    fun `una risposta di errore e' un'eccezione, non un elenco vuoto`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            OnlineModelAvailability(OkHttpClient(), Json, StandardTestDispatcher(testScheduler))
                .availableModels(OnlineProvider.MISTRAL, "k", server.url("/v1").toString())
        }
    }

    @Test
    fun `costo di 100 domande con 2500 token in ingresso e 300 in uscita`() {
        val entry = OnlineModelEntry("m", ONLINE_MODEL_ROLE_AUTO, true, inputUsdPerMTok = 2.0, outputUsdPerMTok = 10.0)
        assertEquals(0.8, entry.usdPer100Questions(), 1e-9)
    }
}
