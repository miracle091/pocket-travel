package com.pockettravel.feature.ai

import android.content.Context
import androidx.core.content.edit
import com.pockettravel.core.data.crypto.KeystoreCipher
import com.pockettravel.core.sync.currentGuidesLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Impostazioni dell'assistente AI, incluse la modalità scelta e la chiave API personale
 * per il client online. La chiave API è cifrata con AES-GCM e una chiave gestita direttamente
 * da Android Keystore; non lascia mai il dispositivo.
 */
@Singleton
class AiSettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val apiKeyStore = AndroidKeystoreSecretStore(context, API_KEY_ALIAS, API_KEY_PAYLOAD)

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

    /** Servizio della modalita' "Online" (ChatGPT se mai scelto, come prima che si potesse scegliere). */
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

    fun baseUrl(): String = onlineProvider().baseUrl

    /** Scelta salvata per [provider] (ricordata per ogni servizio): "" = Automatico, altrimenti il nome del modello. */
    fun onlineModelSetting(provider: OnlineProvider = onlineProvider()): String =
        prefs.getString(KEY_MODEL_PREFIX + provider.name, null).orEmpty()

    /** Modello da usare per [provider]: quello scelto, o [OnlineProvider.autoModel] con Automatico. */
    fun onlineModel(provider: OnlineProvider = onlineProvider()): String =
        onlineModelSetting(provider).trim().ifEmpty { provider.autoModel }

    fun setOnlineModelSetting(provider: OnlineProvider, setting: String) {
        prefs.edit { putString(KEY_MODEL_PREFIX + provider.name, setting) }
    }

    fun model(): String = onlineModel()

    // Un solo modello on-device installabile alla volta (vedi LlmModelManager.selectAndDownload):
    // questo e' l'id del modello scelto dall'utente in LlmModelCatalog.ALL, non necessariamente
    // ancora scaricato. Senza una scelta, o con un id salvato che non e' piu' nel catalogo (es. un
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
            ?: LlmModelCatalog.defaultFor(DeviceAiCapability(context).ramTier(), language).id
    }

    // Stessa cartella di AiModule.provideAiModelsDir.
    private fun downloadedModelId(): String? {
        val modelsDir = File(context.filesDir, "models")
        return LlmModelCatalog.ALL.firstOrNull { File(modelsDir, it.fileName).exists() }?.id
    }

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
    }

    private fun benchmarkKey(modelId: String, field: String) = "${field}_$modelId"

    private companion object {
        const val PREFERENCES_NAME = "ai_online_settings_v2"
        const val KEY_MODE = "engine_mode"
        const val KEY_PROVIDER = "online_provider"
        const val KEY_MODEL_PREFIX = "online_model_"
        const val API_KEY_ALIAS = "pocket_travel_ai_api_key_v2"
        const val API_KEY_PAYLOAD = "api_key_payload"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val KEY_BENCHMARK_WORDS_PER_SECOND = "benchmark_words_per_second"
        const val KEY_BENCHMARK_LATENCY_MS = "benchmark_latency_ms"
        const val KEY_BENCHMARK_LOAD_MS = "benchmark_load_ms"
        const val KEY_BENCHMARK_QUALITY = "benchmark_quality"
        const val KEY_BENCHMARK_RAN_AT = "benchmark_ran_at"
    }
}

// Delega a KeystoreCipher (core:data), la stessa cifratura AES-256-GCM/Keystore generalizzata
// per servire anche il vault passaporti — vedi KeystoreCipher per il ragionamento completo.
private class AndroidKeystoreSecretStore(
    private val context: Context,
    keyAlias: String,
    private val payloadKey: String,
) {

    private val cipher = KeystoreCipher(keyAlias = keyAlias)

    fun read(): String? {
        val payload = preferences().getString(payloadKey, null) ?: return null
        return cipher.decrypt(payload)
    }

    fun write(value: String) {
        preferences().edit {
            putString(payloadKey, cipher.encrypt(value))
        }
    }

    fun clear() {
        preferences().edit {
            remove(payloadKey)
        }
    }

    private fun preferences() = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFERENCES_NAME = "ai_online_settings_v2"
    }
}
