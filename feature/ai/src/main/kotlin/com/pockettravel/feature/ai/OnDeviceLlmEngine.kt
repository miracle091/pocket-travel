package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Motore di inferenza sul dispositivo: carica il modello scelto e genera le risposte. L'implementazione e'
 * [LlamaOnDeviceLlmEngine] (llama.cpp via JNI); nei test la sostituisce un fake.
 */
interface OnDeviceLlmEngine {
    /** Modello in memoria, tempo di caricamento e ultima generazione, per il task manager di debug. */
    val stats: StateFlow<LlmRuntimeStats>

    /** true mentre il modello si carica in memoria (alla prima domanda, secondi per i modelli piu' grandi). */
    val isLoadingModel: StateFlow<Boolean>

    /**
     * Variante CPU di llama.cpp caricata ed estensioni della CPU, per il task manager di debug. La prima lettura
     * crea il motore, che carica la libreria nativa (non il modello).
     */
    val cpuBackend: StateFlow<CpuBackendInfo?>

    /**
     * Carica il modello se non e' gia' in memoria, restituendo quanto e' durato il caricamento — 0
     * se era gia' caricato. Usata dal benchmark per non confondere il tempo di caricamento (una
     * tantum, secondi) con la velocita' di generazione: senza questo, il primo prompt dopo un
     * cambio di modello includerebbe silenziosamente `loadModel()` nel suo tempo, facendo
     * sembrare un modello appena cambiato piu' lento di uno gia' in uso nella sessione.
     */
    suspend fun ensureLoaded(): Long

    /** Esito di [generateAll]: tempo di caricamento (0 se gia' in memoria), tempo delle sole generazioni e risposte. */
    class GenerationBatch(val loadTimeMs: Long, val generationTimeMs: Long, val answers: List<String>)

    /**
     * Come [ensureLoaded] + [generate] per ogni prompt, ma sotto un unico lock: il benchmark misura
     * un solo modello, anche se nel frattempo l'utente ne sceglie un altro.
     */
    suspend fun generateAll(prompts: List<String>): GenerationBatch

    suspend fun generate(prompt: String): String

    /**
     * I token della risposta man mano che il modello li produce. Il lock del modello resta preso finche' il
     * flusso e' in corso: cancellare il collector interrompe la generazione e lo rilascia.
     */
    fun generateStream(prompt: String): Flow<String>

    /**
     * Come [generate], ma con l'output vincolato alla grammatica GBNF [grammar] e al massimo [maxTokens]
     * token; la grammatica si toglie sempre alla fine, anche dopo un errore o una cancellazione.
     */
    suspend fun generateWithGrammar(prompt: String, grammar: String, maxTokens: Int): String

    /** Da chiamare quando il modello viene eliminato dall'utente, per rilasciare la memoria nativa. */
    suspend fun release()

    /** Rilascia il modello e lo elimina da disco; false se il file non c'era. */
    suspend fun releaseAndDelete(): Boolean
}
