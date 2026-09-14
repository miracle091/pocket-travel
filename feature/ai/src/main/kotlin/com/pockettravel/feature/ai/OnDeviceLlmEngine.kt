package com.pockettravel.feature.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wrapper attorno a LiteRT-LM, scelto al posto di MediaPipe LLM Inference API perché quest'ultima
 * è ora in maintenance-only mode secondo la documentazione ufficiale Google.
 * Un solo motore per processo: `Engine.initialize()` richiede secondi e carica l'intero
 * modello in memoria, quindi va creato una volta sola e riusato tra le domande.
 */
@Singleton
class OnDeviceLlmEngine @Inject constructor(
    private val modelManager: LlmModelManager,
    private val coordinator: AiModelCoordinator,
) {
    private val mutex = Mutex()
    private var engine: Engine? = null

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        coordinator.withModelLock {
            mutex.withLock {
            val activeEngine = engine ?: createEngine().also { engine = it }
            activeEngine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE),
                ),
            ).use { conversation ->
                // Message (litertlm 0.16.1, diversa da 0.17.0) non ha una proprieta' `.text`:
                // il testo va estratto da contents.contents, la lista di parti Content
                // (verificato via javap sul jar reale, in 0.16.1 non c'e' documentazione
                // "getting started" separata).
                conversation.sendMessage(prompt).contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                }
            }
        }
    }

    /** Da chiamare quando il modello viene eliminato dall'utente, per rilasciare la memoria nativa. */
    suspend fun release() = coordinator.withModelLock { releaseWithoutLock() }

    suspend fun releaseAndDelete(): Boolean = coordinator.withModelLock {
        releaseWithoutLock()
        modelManager.deleteWithoutLock()
    }

    fun releaseWithoutLock() {
        engine?.close()
        engine = null
    }

    private fun createEngine(): Engine {
        check(modelManager.isDownloaded()) { "Modello IA non scaricato" }
        return Engine(EngineConfig(modelPath = modelManager.modelFile.absolutePath, backend = Backend.CPU()))
            .apply { initialize() }
    }

    private companion object {
        // Bassa per ridurre le allucinazioni su temi normativi/sanitari — vedi
        // "Esempi di prompt AI efficaci" nella specifica tecnica.
        const val TEMPERATURE = 0.3
        // topK/topP: SamplerConfig non ha default (i tre parametri sono obbligatori), la
        // specifica tecnica non ne fissa uno — valori dall'esempio "Getting started" ufficiale
        // di LiteRT-LM (docs/api/kotlin/getting_started.md), non ancora verificati su device reale.
        const val TOP_K = 10
        const val TOP_P = 0.95
    }
}
