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

    // Il repo HuggingFace del modello on-device è a licenza gated (vedi AiModelConfig): serve
    // un token personale dell'utente, mai condiviso/incorporato nell'app — stessa cifratura
    // Keystore della chiave API online, alias separato per non mescolare i due segreti.
    fun hasHuggingFaceToken(): Boolean = !huggingFaceToken().isNullOrBlank()

    fun huggingFaceToken(): String? = hfTokenStore.read()

    fun setHuggingFaceToken(token: String) = hfTokenStore.write(token)

    fun clearHuggingFaceToken() = hfTokenStore.clear()

    fun baseUrl(): String = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun model(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

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
