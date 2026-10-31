package com.pockettravel.feature.ai

import android.content.SharedPreferences
import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import com.pockettravel.core.data.crypto.KeystoreCipher
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.feature.ai.di.AiModelsDir
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Impostazioni dell'assistente IA, incluso il motore scelto e la chiave API personale
 * per il client online. La chiave API e' cifrata con AES-GCM e una chiave gestita direttamente
 * da Android Keystore; non lascia mai il dispositivo.
 */
@Singleton
class AiSettingsStore(
    preferencesFactory: PreferencesFactory,
    private val modelsDir: File,
    private val ramTier: () -> RamTier,
) {

    @Inject
    constructor(
        preferencesFactory: PreferencesFactory,
        @AiModelsDir modelsDir: File,
        deviceAiCapability: DeviceAiCapability,
    ) : this(preferencesFactory, modelsDir, deviceAiCapability::ramTier)

    private val prefs = preferencesFactory.open(PREFERENCES_NAME)
    private val apiKeyStore = AndroidKeystoreSecretStore(prefs, API_KEY_ALIAS, API_KEY_PAYLOAD)

    fun engineMode(): AiEngineMode =
        AiEngineMode.valueOf(prefs.getString(KEY_MODE, AiEngineMode.ON_DEVICE.name) ?: AiEngineMode.ON_DEVICE.name)

    fun setEngineMode(mode: AiEngineMode) = prefs.edit { putString(KEY_MODE, mode.name) }

    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    fun apiKey(): String? = apiKeyStore.read()

    // Segue setApiKey/clearApiKey (cambiare servizio passa da clearApiKey): serve a sapere subito
    // se l'assistente e' configurato (vedi AiAvailability).
    private val _hasApiKeyFlow = MutableStateFlow(hasApiKey())
    val hasApiKeyFlow: StateFlow<Boolean> = _hasApiKeyFlow.asStateFlow()

    fun setApiKey(key: String) {
        apiKeyStore.write(key)
        _hasApiKeyFlow.value = hasApiKey()
    }

    fun clearApiKey() {
        apiKeyStore.clear()
        _hasApiKeyFlow.value = false
    }

    /** Servizio del motore "Online" (ChatGPT se non ne e' mai stato scelto uno). */
    fun onlineProvider(): OnlineProvider =
        OnlineProvider.entries.firstOrNull { it.name == prefs.getString(KEY_PROVIDER, null) } ?: OnlineProvider.CHATGPT

    /**
     * Cambiando servizio la chiave salvata si cancella: e' di un altro servizio, mandarla al nuovo la
     * farebbe conoscere a chi non deve averla.
     */
    fun setOnlineProvider(provider: OnlineProvider) {
        if (provider == onlineProvider()) return
        clearApiKey()
        prefs.edit { putString(KEY_PROVIDER, provider.name) }
    }

    /** Scelta salvata per [provider] (ricordata per ogni servizio): "" = Automatico, altrimenti il nome del modello. */
    fun onlineModelSetting(provider: OnlineProvider = onlineProvider()): String =
        prefs.getString(KEY_MODEL_PREFIX + provider.name, null).orEmpty()

    fun setOnlineModelSetting(provider: OnlineProvider, setting: String) {
        prefs.edit { putString(KEY_MODEL_PREFIX + provider.name, setting) }
    }

    /** Consenso a mandare ai servizi online nota personale, dati dalla posizione (POI vicini, mezzi pubblici, previsioni), partenza e nazionalita': spento di default. */
    fun sharesPersonalData(): Boolean = prefs.getBoolean(KEY_SHARE_NOTE_POSITION, false)

    fun setSharesPersonalData(enabled: Boolean) = prefs.edit { putBoolean(KEY_SHARE_NOTE_POSITION, enabled) }

    // Un solo modello sul dispositivo installabile alla volta (vedi LlmModelManager.selectAndDownload):
    // questo e' l'id del modello scelto dall'utente in LlmModelCatalog.ALL, non necessariamente
    // ancora scaricato. Senza una scelta, o con un id salvato che non e' piu' nel catalogo dei modelli (es. un
    // modello rimosso), vale il modello gia' scaricato (uno solo alla volta: chi aveva scaricato il
    // predefinito di prima non deve riscaricarne un altro quando il predefinito cambia) o, se non ce
    // n'e', il predefinito della fascia di RAM del dispositivo, altrimenti selectedModelDefinition()
    // fallirebbe.
    // Un modello addestrato in un'altra lingua (es. uno italiano con l'app in inglese) non vale come scelta:
    // si ricade sul predefinito della lingua (LlmModelCatalog.visibleFor). Il file resta scaricato e si
    // elimina da Spazio di archiviazione.
    fun selectedModelId(): String {
        val language = currentGuidesLanguage()
        fun usable(id: String?) = id?.takeIf { stored -> LlmModelCatalog.ALL.any { it.id == stored && LlmModelCatalog.isUsableIn(it, language) } }
        return usable(prefs.getString(KEY_SELECTED_MODEL_ID, null))
            ?: usable(downloadedModelId())
            ?: LlmModelCatalog.defaultFor(ramTier(), language).id
    }

    private fun downloadedModelId(): String? =
        LlmModelCatalog.ALL.firstOrNull { File(modelsDir, it.fileName).exists() }?.id

    fun setSelectedModelId(modelId: String) = prefs.edit { putString(KEY_SELECTED_MODEL_ID, modelId) }

    // Un risultato per modello, non solo per l'ultimo eseguito: cambiare modello selezionato non
    // deve far perdere il benchmark gia' fatto per quello precedente, se l'utente torna indietro.
    fun benchmarkResult(modelId: String): BenchmarkResult? {
        val ranAt = prefs.getLong(benchmarkKey(modelId, KEY_BENCHMARK_RAN_AT), -1L)
        if (ranAt < 0) return null
        return BenchmarkResult(
            modelId = modelId,
            wordsPerSecond = prefs.getFloat(benchmarkKey(modelId, KEY_BENCHMARK_WORDS_PER_SECOND), 0f),
            totalLatencyMs = prefs.getLong(benchmarkKey(modelId, KEY_BENCHMARK_LATENCY_MS), 0L),
            loadTimeMs = prefs.getLong(benchmarkKey(modelId, KEY_BENCHMARK_LOAD_MS), 0L),
            qualityScore = prefs.getInt(benchmarkKey(modelId, KEY_BENCHMARK_QUALITY), 0),
            ranAt = ranAt,
            promptTokensPerSecond = prefs.getFloat(benchmarkKey(modelId, KEY_BENCHMARK_PROMPT_TPS), 0f),
            genTokensPerSecond = prefs.getFloat(benchmarkKey(modelId, KEY_BENCHMARK_GEN_TPS), 0f),
        )
    }

    // LlmModelCatalog.ALL e' un elenco fisso e noto: nessun bisogno di una chiave separata che
    // elenchi "quali modelli hanno un risultato" per la vista di confronto, basta scorrerlo.
    fun allBenchmarkResults(): List<BenchmarkResult> =
        LlmModelCatalog.ALL.mapNotNull { benchmarkResult(it.id) }

    fun saveBenchmarkResult(result: BenchmarkResult) = prefs.edit {
        putFloat(benchmarkKey(result.modelId, KEY_BENCHMARK_WORDS_PER_SECOND), result.wordsPerSecond)
        putLong(benchmarkKey(result.modelId, KEY_BENCHMARK_LATENCY_MS), result.totalLatencyMs)
        putLong(benchmarkKey(result.modelId, KEY_BENCHMARK_LOAD_MS), result.loadTimeMs)
        putInt(benchmarkKey(result.modelId, KEY_BENCHMARK_QUALITY), result.qualityScore)
        putLong(benchmarkKey(result.modelId, KEY_BENCHMARK_RAN_AT), result.ranAt)
        putFloat(benchmarkKey(result.modelId, KEY_BENCHMARK_PROMPT_TPS), result.promptTokensPerSecond)
        putFloat(benchmarkKey(result.modelId, KEY_BENCHMARK_GEN_TPS), result.genTokensPerSecond)
    }

    private fun benchmarkKey(modelId: String, field: String) = "${field}_$modelId"

    private companion object {
        const val PREFERENCES_NAME = "ai_online_settings_v2"
        const val KEY_MODE = "engine_mode"
        const val KEY_PROVIDER = "online_provider"
        const val KEY_MODEL_PREFIX = "online_model_"
        const val KEY_SHARE_NOTE_POSITION = "online_share_personal_data"
        const val API_KEY_ALIAS = "pocket_travel_ai_api_key_v2"
        const val API_KEY_PAYLOAD = "api_key_payload"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val KEY_BENCHMARK_WORDS_PER_SECOND = "benchmark_words_per_second"
        const val KEY_BENCHMARK_LATENCY_MS = "benchmark_latency_ms"
        const val KEY_BENCHMARK_LOAD_MS = "benchmark_load_ms"
        const val KEY_BENCHMARK_QUALITY = "benchmark_quality"
        const val KEY_BENCHMARK_RAN_AT = "benchmark_ran_at"
        const val KEY_BENCHMARK_PROMPT_TPS = "benchmark_prompt_tps"
        const val KEY_BENCHMARK_GEN_TPS = "benchmark_gen_tps"
    }
}

// Delega a KeystoreCipher (core:data), la stessa cifratura AES-256-GCM/Keystore generalizzata
// per servire anche la cassaforte — vedi KeystoreCipher per il ragionamento completo.
private class AndroidKeystoreSecretStore(
    private val prefs: SharedPreferences,
    keyAlias: String,
    private val payloadKey: String,
) {

    private val cipher = KeystoreCipher(keyAlias = keyAlias)

    fun read(): String? {
        val payload = prefs.getString(payloadKey, null) ?: return null
        return cipher.decrypt(payload)
    }

    fun write(value: String) {
        prefs.edit {
            putString(payloadKey, cipher.encrypt(value))
        }
    }

    fun clear() {
        prefs.edit {
            remove(payloadKey)
        }
    }
}
