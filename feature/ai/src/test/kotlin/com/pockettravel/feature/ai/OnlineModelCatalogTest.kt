package com.pockettravel.feature.ai

import com.pockettravel.core.sync.OnlineModelList
import com.pockettravel.core.sync.OnlineModelsClient
import com.pockettravel.core.sync.parseOnlineModels
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

// Il file vero dell'APK, letto dai sorgenti: il test di Gradle parte dalla cartella del modulo.
internal fun bundledOnlineModels(): OnlineModelList =
    parseOnlineModels(File("src/main/assets/online-models.json").readBytes())

class OnlineModelCatalogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeClient(var result: () -> ByteArray) : OnlineModelsClient {
        override suspend fun fetchOnlineModels(): ByteArray = result()
    }

    private fun catalog(client: OnlineModelsClient, cache: File = File(folder.root, "online-models.json"), dispatcher: TestDispatcher) =
        OnlineModelCatalog(bundledOnlineModels(), cache, client, dispatcher)

    private val published = """
        {"publishedAt": 1800000000, "providers": {"CHATGPT": [
          {"id": "gpt-7-nuovo", "role": "auto", "temperature": false, "inputUsdPerMTok": 0.2, "outputUsdPerMTok": 0.8}
        ]}}
    """.trimIndent().toByteArray()

    @Test
    fun `l'elenco dell'APK ha un modello automatico per ogni servizio`() {
        val list = bundledOnlineModels()
        OnlineProvider.entries.forEach { provider -> assertTrue(provider.name, list.proposed(provider.name).orEmpty().isNotEmpty()) }
    }

    @Test
    fun `senza elenco scaricato vale quello dell'APK, Automatico per primo`() = runTest {
        val catalog = catalog(FakeClient { error("non chiamato") }, dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals("gpt-6-luna", catalog.autoModel(OnlineProvider.CHATGPT))
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5"), catalog.models(OnlineProvider.CLAUDE).map { it.id })
        assertEquals("gpt-6-luna", catalog.modelFor(OnlineProvider.CHATGPT, "  "))
        assertEquals("gpt-x", catalog.modelFor(OnlineProvider.CHATGPT, " gpt-x "))
    }

    @Test
    fun `gli altri modelli vengono dall'elenco scaricato, con la loro temperatura`() = runTest {
        val withOthers = """
            {"providers": {"CHATGPT": [
              {"id": "gpt-7-nuovo", "role": "auto", "temperature": false, "inputUsdPerMTok": 0.2, "outputUsdPerMTok": 0.8}
            ]}, "otherModels": {"CHATGPT": [
              {"id": "gpt-4.1", "temperature": true, "inputUsdPerMTok": 2, "outputUsdPerMTok": 8}
            ]}}
        """.trimIndent().toByteArray()
        val catalog = catalog(FakeClient { withOthers }, dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(emptyList<String>(), catalog.otherModels(OnlineProvider.CHATGPT).map { it.id })
        catalog.refresh()
        assertEquals(listOf("gpt-4.1"), catalog.otherModels(OnlineProvider.CHATGPT).map { it.id })
        assertTrue(catalog.sendsTemperature(OnlineProvider.CHATGPT, "gpt-4.1"))
        assertEquals(emptyList<String>(), catalog.otherModels(OnlineProvider.MISTRAL).map { it.id })
    }

    @Test
    fun `l'elenco scaricato sostituisce quello dell'APK e resta al riavvio`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cache = File(folder.root, "online-models.json")
        // Due volte: il secondo salvataggio sostituisce il file gia' scritto.
        catalog(FakeClient { published }, cache, dispatcher).apply { refresh(); refresh() }

        val restarted = catalog(FakeClient { error("non chiamato") }, cache, dispatcher)
        assertEquals("gpt-7-nuovo", restarted.autoModel(OnlineProvider.CHATGPT))
        // Un servizio assente dall'elenco scaricato prende i modelli di quello dell'APK.
        assertEquals("claude-haiku-5-5", restarted.autoModel(OnlineProvider.CLAUDE))
    }

    @Test
    fun `un errore di rete o un elenco non valido lasciano quello attuale`() = runTest {
        val client = FakeClient { throw IOException("offline") }
        val catalog = catalog(client, dispatcher = StandardTestDispatcher(testScheduler))
        runCatching { catalog.refresh() }
        client.result = { """{"providers": {"CHATGPT": []}}""".toByteArray() }
        runCatching { catalog.refresh() }
        assertEquals("gpt-6-luna", catalog.autoModel(OnlineProvider.CHATGPT))
        assertFalse(File(folder.root, "online-models.json").exists())
    }

    @Test
    fun `la temperatura viene dall'elenco, per gli altri nomi dalla regola`() = runTest {
        val catalog = catalog(FakeClient { error("non chiamato") }, dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(catalog.sendsTemperature(OnlineProvider.MISTRAL, "mistral-small-latest"))
        assertFalse(catalog.sendsTemperature(OnlineProvider.CLAUDE, "claude-haiku-5-5"))
        assertTrue(catalog.sendsTemperature(OnlineProvider.CLAUDE, "claude-sonnet-4-6"))
    }

    @Test
    fun `le foto solo ai modelli con vision, mai a un modello sconosciuto o di un elenco senza il campo`() = runTest {
        val catalog = catalog(FakeClient { published }, dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(catalog.supportsVision(OnlineProvider.GEMINI, "gemini-3.6-flash"))
        assertFalse(catalog.supportsVision(OnlineProvider.GROQ, "openai/gpt-oss-120b"))
        assertFalse(catalog.supportsVision(OnlineProvider.GEMINI, "gemini-sconosciuto"))
        catalog.refresh()
        // Prima l'elenco pubblicato e' in uso, poi il modello senza il campo non legge foto.
        assertEquals("gpt-7-nuovo", catalog.autoModel(OnlineProvider.CHATGPT))
        assertFalse(catalog.supportsVision(OnlineProvider.CHATGPT, "gpt-7-nuovo"))
    }
}
