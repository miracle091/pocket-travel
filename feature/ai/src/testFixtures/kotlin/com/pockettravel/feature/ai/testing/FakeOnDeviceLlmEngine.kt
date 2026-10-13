package com.pockettravel.feature.ai.testing

import com.pockettravel.feature.ai.LlmRuntimeStats
import com.pockettravel.feature.ai.OnDeviceLlmEngine
import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** Motore sul dispositivo in memoria per i test JVM: risponde con [answer] e registra le chiamate. */
class FakeOnDeviceLlmEngine : OnDeviceLlmEngine {

    override val stats = MutableStateFlow(LlmRuntimeStats())
    override val isLoadingModel = MutableStateFlow(false)
    override val cpuBackend: MutableStateFlow<CpuBackendInfo?> = MutableStateFlow(null)

    /** Risposta a un prompt; di default il prompt stesso preceduto da "risposta: ". */
    var answer: (String) -> String = { "risposta: $it" }

    /** Tempo di caricamento restituito da [ensureLoaded] e da [generateAll]. */
    var loadTimeMs: Long = 0L

    /** Esito di [releaseAndDelete]. */
    var deleteResult: Boolean = true

    /** Se non nullo, ogni chiamata di generazione lo lancia. */
    var failure: Throwable? = null

    val generatedPrompts = mutableListOf<String>()
    val grammarCalls = mutableListOf<GrammarCall>()
    var ensureLoadedCalls = 0
        private set
    var releaseCalls = 0
        private set
    var releaseAndDeleteCalls = 0
        private set

    class GrammarCall(val prompt: String, val grammar: String, val maxTokens: Int)

    override suspend fun ensureLoaded(): Long {
        ensureLoadedCalls++
        return loadTimeMs
    }

    override suspend fun generateAll(prompts: List<String>): OnDeviceLlmEngine.GenerationBatch {
        failure?.let { throw it }
        generatedPrompts += prompts
        return OnDeviceLlmEngine.GenerationBatch(loadTimeMs, 0L, prompts.map(answer))
    }

    override suspend fun generate(prompt: String): String {
        failure?.let { throw it }
        generatedPrompts += prompt
        return answer(prompt)
    }

    override fun generateStream(prompt: String): Flow<String> {
        failure?.let { throw it }
        generatedPrompts += prompt
        return flowOf(answer(prompt))
    }

    override suspend fun generateWithGrammar(prompt: String, grammar: String, maxTokens: Int): String {
        failure?.let { throw it }
        grammarCalls += GrammarCall(prompt, grammar, maxTokens)
        return answer(prompt)
    }

    override suspend fun release() {
        releaseCalls++
    }

    override suspend fun releaseAndDelete(): Boolean {
        releaseAndDeleteCalls++
        return deleteResult
    }
}
