package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.ContextUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

private const val BYTES_PER_UNIT = 1024.0
internal const val NANOS_PER_MS = 1_000_000L
internal const val MS_PER_SECOND = 1000f
private const val PERCENT = 100f

// Generazioni tenute per l'esportazione del task manager.
private const val MAX_GENERATION_HISTORY = 100
private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

/**
 * Una generazione del modello: tempi misurati in Kotlin e [context] (token del prompt e generati, contesto
 * occupato) letto dal motore nativo a fine turno; [context] e' null se il motore non l'ha fornito.
 */
data class GenerationStats(
    val modelId: String?,
    val context: ContextUsage?,
    val timeToFirstTokenMs: Long?,
    val totalMs: Long,
    val endedAtMs: Long = 0L,
) {
    /** Token al secondo dopo il primo; null se la risposta ne ha meno di due o mancano i conteggi. */
    val tokensPerSecond: Float?
        get() = context?.let { tokensPerSecond(it.generatedTokens, timeToFirstTokenMs, totalMs) }

    /** Token del prompt letti al secondo, stimati dal tempo al primo token; null senza i conteggi. */
    val promptTokensPerSecond: Float?
        get() = context?.let { promptTokensPerSecond(it.promptTokens, timeToFirstTokenMs) }
}

/** Stato del motore visto da fuori: modello in memoria, tempo di caricamento e ultima generazione. */
data class LlmRuntimeStats(
    val loadedModelId: String? = null,
    val loadTimeMs: Long? = null,
    val isGenerating: Boolean = false,
    val lastGeneration: GenerationStats? = null,
    val history: List<GenerationStats> = emptyList(),
)

/**
 * Velocita' di lettura del prompt: token del prompt diviso il tempo al primo token, che comprende la lettura
 * del prompt e il calcolo del primo token generato (stima un po' per difetto); null senza dati.
 */
internal fun promptTokensPerSecond(promptTokens: Int, timeToFirstTokenMs: Long?): Float? {
    if (promptTokens <= 0 || timeToFirstTokenMs == null || timeToFirstTokenMs <= 0L) return null
    return promptTokens * MS_PER_SECOND / timeToFirstTokenMs
}

/** Velocita' di generazione escludendo l'attesa del primo token (caricamento del prompt). */
internal fun tokensPerSecond(pieces: Int, timeToFirstTokenMs: Long?, totalMs: Long): Float? {
    val decodeMs = totalMs - (timeToFirstTokenMs ?: return null)
    return if (pieces < 2 || decodeMs <= 0L) null else (pieces - 1) * MS_PER_SECOND / decodeMs
}

/**
 * Uso della CPU del processo in percentuale di un solo core (200 = due core pieni) tra due campioni di
 * tempo CPU e di tempo reale; 0 se l'intervallo e' vuoto.
 */
internal fun cpuPercent(cpuDeltaMs: Long, wallDeltaMs: Long): Float =
    if (wallDeltaMs <= 0L) 0f else cpuDeltaMs.coerceAtLeast(0L) * PERCENT / wallDeltaMs

/** Numero di thread dalle righe di `/proc/self/status` (riga `Threads:`); null se manca. */
internal fun parseThreadCount(statusLines: Sequence<String>): Int? =
    statusLines.firstOrNull { it.startsWith("Threads:") }
        ?.substringAfter(':')?.trim()?.toIntOrNull()

/** Dimensione leggibile in unita' binarie (1 KB = 1024 B), con una cifra decimale dai KB in su. */
internal fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    var value = bytes.coerceAtLeast(0L).toDouble()
    var unit = 0
    while (value >= BYTES_PER_UNIT && unit < BYTE_UNITS.lastIndex) {
        value /= BYTES_PER_UNIT
        unit++
    }
    return if (unit == 0) "${value.toLong()} B" else String.format(locale, "%.1f %s", value, BYTE_UNITS[unit])
}

/**
 * Raccoglie i frammenti della risposta (passandoli a [onPiece]) e ne registra in questo stato i tempi e
 * l'uso del contesto letto da [contextUsage] a fine turno, anche se la generazione viene cancellata o
 * fallisce. Restituisce il testo completo.
 */
internal suspend fun MutableStateFlow<LlmRuntimeStats>.collectTimed(
    pieces: Flow<String>,
    contextUsage: () -> ContextUsage?,
    onPiece: suspend (String) -> Unit,
): String {
    val answer = StringBuilder()
    val start = System.nanoTime()
    var firstPieceMs: Long? = null
    update { it.copy(isGenerating = true) }
    try {
        pieces.collect {
            if (firstPieceMs == null) firstPieceMs = (System.nanoTime() - start) / NANOS_PER_MS
            answer.append(it)
            onPiece(it)
        }
    } finally {
        val generation = GenerationStats(
            modelId = value.loadedModelId,
            context = contextUsage(),
            timeToFirstTokenMs = firstPieceMs,
            totalMs = (System.nanoTime() - start) / NANOS_PER_MS,
            endedAtMs = System.currentTimeMillis(),
        )
        update {
            it.copy(
                isGenerating = false,
                lastGeneration = generation,
                history = (it.history + generation).takeLast(MAX_GENERATION_HISTORY),
            )
        }
    }
    return answer.toString()
}
