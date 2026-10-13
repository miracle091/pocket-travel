package com.pockettravel.feature.ai

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.io.path.createTempDirectory

// Impostazioni dell'assistente senza la parte cifrata: la chiave API passa dal Keystore, che nei test JVM non c'e'.
class AiSettingsStoreTest {

    private val files = InMemoryPreferencesFactory()
    private val modelsDir = createTempDirectory("ai-settings-store-test").toFile()
    private var tier = RamTier.MINIMO
    private lateinit var previousLocale: Locale

    private fun newStore() = AiSettingsStore(files, modelsDir) { tier }

    private val raw get() = files.open("ai_online_settings_v2")

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ITALIAN)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        modelsDir.deleteRecursively()
    }

    @Test
    fun `senza scelte il motore e' sul dispositivo e il servizio online e' ChatGPT`() {
        val store = newStore()

        assertEquals(AiEngineMode.ON_DEVICE, store.engineMode())
        assertEquals(OnlineProvider.CHATGPT, store.onlineProvider())
        assertFalse(store.hasApiKey())
        assertFalse(store.hasApiKeyFlow.value)
    }

    @Test
    fun `il motore scelto resta per la prossima apertura, con la chiave di sempre`() {
        newStore().setEngineMode(AiEngineMode.ONLINE)

        assertEquals(AiEngineMode.ONLINE, newStore().engineMode())
        assertEquals("ONLINE", raw.getString("engine_mode", null))
    }

    @Test
    fun `il modello online e' per servizio e Automatico usa quello predefinito`() {
        val store = newStore()
        assertEquals(OnlineProvider.CHATGPT.autoModel, store.onlineModel())

        store.setOnlineModelSetting(OnlineProvider.CHATGPT, " gpt-x ")
        store.setOnlineModelSetting(OnlineProvider.MISTRAL, "mistral-y")

        assertEquals("gpt-x", store.onlineModel(OnlineProvider.CHATGPT))
        assertEquals("mistral-y", store.onlineModel(OnlineProvider.MISTRAL))
        assertEquals(OnlineProvider.GEMINI.autoModel, store.onlineModel(OnlineProvider.GEMINI))
        assertEquals(" gpt-x ", raw.getString("online_model_CHATGPT", null))
    }

    @Test
    fun `cambiare servizio toglie la chiave salvata, lasciarlo uguale no`() {
        raw.edit().putString("api_key_payload", "cifrato").apply()
        val store = newStore()

        store.setOnlineProvider(OnlineProvider.CHATGPT)
        assertEquals("cifrato", raw.getString("api_key_payload", null))

        store.setOnlineProvider(OnlineProvider.CLAUDE)

        assertNull(raw.getString("api_key_payload", null))
        assertEquals(OnlineProvider.CLAUDE, newStore().onlineProvider())
        assertEquals("CLAUDE", raw.getString("online_provider", null))
    }

    @Test
    fun `il modello scelto e' quello salvato se e' nel catalogo`() {
        val model = LlmModelCatalog.ALL.first { LlmModelCatalog.isUsableIn(it, "it") }
        newStore().setSelectedModelId(model.id)

        assertEquals(model.id, newStore().selectedModelId())
        assertEquals(model.id, raw.getString("selected_model_id", null))
    }

    @Test
    fun `senza scelta vale il modello gia' scaricato`() {
        val downloaded = LlmModelCatalog.ALL.last { LlmModelCatalog.isUsableIn(it, "it") }
        File(modelsDir, downloaded.fileName).writeText("x")

        assertEquals(downloaded.id, newStore().selectedModelId())
    }

    @Test
    fun `senza scelta ne' modelli scaricati vale il predefinito della fascia di RAM`() {
        tier = RamTier.MINIMO
        assertEquals(LlmModelCatalog.defaultFor(RamTier.MINIMO, "it").id, newStore().selectedModelId())

        tier = RamTier.AMPIA
        assertEquals(LlmModelCatalog.defaultFor(RamTier.AMPIA, "it").id, newStore().selectedModelId())
    }

    @Test
    fun `un id salvato che non e' piu' nel catalogo ripiega sul predefinito`() {
        raw.edit().putString("selected_model_id", "modello-rimosso").apply()

        assertEquals(LlmModelCatalog.defaultFor(RamTier.MINIMO, "it").id, newStore().selectedModelId())
    }

    @Test
    fun `un modello addestrato in un'altra lingua non vale come scelta`() {
        val english = LlmModelCatalog.ALL.firstOrNull { it.origin == ModelOrigin.ADDESTRATO && it.language == "en" }
        assumeNotNull(english)
        newStore().setSelectedModelId(requireNotNull(english).id)

        assertEquals(LlmModelCatalog.defaultFor(RamTier.MINIMO, "it").id, newStore().selectedModelId())
    }

    @Test
    fun `il benchmark si salva per modello con tipi e chiavi di sempre`() {
        val result = BenchmarkResult(
            modelId = "m1",
            wordsPerSecond = 12.5f,
            totalLatencyMs = 3_400L,
            loadTimeMs = 900L,
            qualityScore = 7,
            ranAt = 1_700_000_000_000L,
        )
        newStore().saveBenchmarkResult(result)

        assertEquals(result, newStore().benchmarkResult("m1"))
        assertNull(newStore().benchmarkResult("m2"))
        assertEquals(12.5f, raw.getFloat("benchmark_words_per_second_m1", 0f), 0f)
        assertEquals(3_400L, raw.getLong("benchmark_latency_ms_m1", 0L))
        assertEquals(900L, raw.getLong("benchmark_load_ms_m1", 0L))
        assertEquals(7, raw.getInt("benchmark_quality_m1", 0))
        assertEquals(1_700_000_000_000L, raw.getLong("benchmark_ran_at_m1", 0L))
    }

    @Test
    fun `allBenchmarkResults elenca solo i modelli del catalogo con un risultato`() {
        val store = newStore()
        val id = LlmModelCatalog.ALL.first().id
        store.saveBenchmarkResult(BenchmarkResult(id, 1f, 1L, 1L, 1, 1L))
        store.saveBenchmarkResult(BenchmarkResult("fuori-catalogo", 1f, 1L, 1L, 1, 1L))

        assertEquals(listOf(id), store.allBenchmarkResults().map { it.modelId })
    }
}
