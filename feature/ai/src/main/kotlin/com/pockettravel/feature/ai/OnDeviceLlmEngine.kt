package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import com.pockettravel.feature.ai.llamacpp.isModelLoaded
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wrapper attorno al bridge JNI di llama.cpp (com.pockettravel.feature.ai.llamacpp, vendorizzato
 * in third-party/llama-cpp). [InferenceEngineImpl] e' un singleton di processo (un solo backend
 * GGML/NDK caricato una volta): qui non lo si ricrea mai, si carica/scarica solo il MODELLO al
 * suo interno.
 */
@Singleton
class OnDeviceLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: LlmModelManager,
    private val coordinator: AiModelCoordinator,
    private val aiSettingsStore: AiSettingsStore,
) {
    private val mutex = Mutex()
    private val engine: InferenceEngine by lazy { InferenceEngineImpl.getInstance(context) }

    /**
     * Carica il modello se non è già in memoria, restituendo quanto è durato il caricamento — 0
     * se era già caricato. Usata dal benchmark per non confondere il tempo di caricamento (una
     * tantum, secondi) con la velocità di generazione: senza questo, il primo prompt dopo un
     * cambio di modello includerebbe silenziosamente `loadModel()` nel suo tempo, facendo
     * sembrare un modello appena cambiato più lento di uno già in uso nella sessione.
     */
    suspend fun ensureLoaded(): Long = withContext(Dispatchers.IO) {
        coordinator.withModelLock {
            mutex.withLock {
                if (engine.state.value.isModelLoaded) {
                    0L
                } else {
                    val start = System.currentTimeMillis()
                    loadModelIfNeeded()
                    System.currentTimeMillis() - start
                }
            }
        }
    }

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        coordinator.withModelLock {
            mutex.withLock {
                loadModelIfNeeded()
                // Reset esplicito: i dati di training (pocket_travel_sft.jsonl) non hanno mai
                // un turno "system", solo user+assistant — senza reset, sendUserPrompt
                // accumulerebbe la history tra una domanda e l'altra invece di restare un turno
                // singolo.
                engine.resetConversation()
                engine.sendUserPrompt(prompt).toList().joinToString(separator = "")
            }
        }
    }

    /** Da chiamare quando il modello viene eliminato dall'utente, per rilasciare la memoria nativa. */
    suspend fun release() = coordinator.withModelLock { releaseWithoutLock() }

    suspend fun releaseAndDelete(): Boolean = coordinator.withModelLock {
        releaseWithoutLock()
        modelManager.deleteWithoutLock(aiSettingsStore.selectedModelDefinition())
    }

    suspend fun releaseWithoutLock() {
        val state = engine.state.value
        // cleanUp() lancia IllegalStateException fuori da ModelReady/Error (es. nessun modello
        // ancora caricato): va chiamata solo se c'e' davvero qualcosa da scaricare.
        if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) {
            engine.cleanUp()
        }
    }

    private suspend fun loadModelIfNeeded() {
        if (engine.state.value.isModelLoaded) return
        // Un errore precedente (caricamento o generazione) lascia il motore in Error, da cui
        // loadModel() rifiuta di partire: senza questo reset ogni richiesta successiva fallirebbe
        // fino al riavvio dell'app. cleanUp() libera anche l'eventuale modello rimasto in memoria.
        if (engine.state.value is InferenceEngine.State.Error) engine.cleanUp()
        val definition = aiSettingsStore.selectedModelDefinition()
        check(modelManager.isDownloaded(definition)) { "Modello IA non scaricato" }
        engine.loadModel(modelManager.modelFile(definition).absolutePath, topK = TOP_K, topP = TOP_P)
    }

    private companion object {
        // topK/topP non ancora verificati su device reale. La temperature (0.3, bassa per ridurre
        // le allucinazioni su temi normativi/sanitari) e' fissata lato nativo
        // (ai_chat.cpp: DEFAULT_SAMPLER_TEMP), non e' un parametro di loadModel().
        const val TOP_K = 10
        const val TOP_P = 0.95f
    }
}
