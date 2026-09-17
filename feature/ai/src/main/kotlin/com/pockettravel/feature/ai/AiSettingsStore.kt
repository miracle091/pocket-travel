package com.pockettravel.feature.ai

import android.content.Context
import androidx.core.content.edit
import com.pockettravel.core.data.crypto.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Impostazioni dell'assistente AI, incluse la modalità scelta e la chiave API personale
 * per il client online. La chiave API è cifrata con AES-GCM e una chiave gestita direttamente
 * da Android Keystore; non lascia mai il dispositivo.
 */
@Singleton
class AiSettingsStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val apiKeyStore = AndroidKeystoreSecretStore(context, API_KEY_ALIAS, API_KEY_PAYLOAD)
    private val hfTokenStore = AndroidKeystoreSecretStore(context, HF_TOKEN_ALIAS, HF_TOKEN_PAYLOAD)

    fun engineMode(): AiEngineMode =
        AiEngineMode.valueOf(prefs.getString(KEY_MODE, AiEngineMode.ON_DEVICE.name) ?: AiEngineMode.ON_DEVICE.name)

    fun setEngineMode(mode: AiEngineMode) = prefs.edit { putString(KEY_MODE, mode.name) }

    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    fun apiKey(): String? = apiKeyStore.read()

    fun setApiKey(key: String) = apiKeyStore.write(key)

    fun clearApiKey() = apiKeyStore.clear()

    // Il repo HuggingFace di alcuni modelli on-device è a licenza gated (vedi LlmModelDefinition.licenseUrl): serve
    // un token personale dell'utente, mai condiviso/incorporato nell'app — stessa cifratura
    // Keystore della chiave API online, alias separato per non mescolare i due segreti.
    fun hasHuggingFaceToken(): Boolean = !huggingFaceToken().isNullOrBlank()

    fun huggingFaceToken(): String? = hfTokenStore.read()

    fun setHuggingFaceToken(token: String) = hfTokenStore.write(token)

    fun clearHuggingFaceToken() = hfTokenStore.clear()

    fun baseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun model(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    // Un solo modello on-device installabile alla volta (vedi LlmModelManager.selectAndDownload):
    // questo e' l'id del modello scelto dall'utente in LlmModelCatalog.ALL, non necessariamente
    // ancora scaricato. Default al modello storico (l'unico esistente prima del catalogo) cosi'
    // un utente che ha gia' installato quel file continua a puntarci senza dover riscegliere.
    fun selectedModelId(): String = prefs.getString(KEY_SELECTED_MODEL_ID, DEFAULT_SELECTED_MODEL_ID) ?: DEFAULT_SELECTED_MODEL_ID

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
            qualityScore = prefs.getInt(benchmarkKey(modelId, KEY_BENCHMARK_QUALITY), 0),
            ranAt = ranAt,
        )
    }

    fun saveBenchmarkResult(result: BenchmarkResult) = prefs.edit {
        putFloat(benchmarkKey(result.modelId, KEY_BENCHMARK_WORDS_PER_SECOND), result.wordsPerSecond)
        putLong(benchmarkKey(result.modelId, KEY_BENCHMARK_LATENCY_MS), result.totalLatencyMs)
        putInt(benchmarkKey(result.modelId, KEY_BENCHMARK_QUALITY), result.qualityScore)
        putLong(benchmarkKey(result.modelId, KEY_BENCHMARK_RAN_AT), result.ranAt)
    }

    private fun benchmarkKey(modelId: String, field: String) = "${field}_$modelId"

    private companion object {
        const val PREFERENCES_NAME = "ai_online_settings_v2"
        const val KEY_MODE = "engine_mode"
        const val KEY_BASE_URL = "openai_base_url"
        const val KEY_MODEL = "openai_model"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val API_KEY_ALIAS = "pocket_travel_ai_api_key_v2"
        const val API_KEY_PAYLOAD = "api_key_payload"
        const val HF_TOKEN_ALIAS = "pocket_travel_hf_token_v1"
        const val HF_TOKEN_PAYLOAD = "hf_token_payload"
        const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        const val DEFAULT_SELECTED_MODEL_ID = "gemma3-1b-it"
        const val KEY_BENCHMARK_WORDS_PER_SECOND = "benchmark_words_per_second"
        const val KEY_BENCHMARK_LATENCY_MS = "benchmark_latency_ms"
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
