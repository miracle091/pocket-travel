package com.pockettravel.feature.ai

import java.time.Instant
import java.util.Locale

private const val BYTES_PER_MB = 1024.0 * 1024.0

private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_MB)

private fun time(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

/**
 * Testo esportato dal task manager: intestazione, letture (CSV), generazioni (CSV) e logcat del processo.
 * Le etichette sono in inglese e i numeri con il punto, per aprire il file in un foglio di calcolo o in uno script.
 */
internal fun buildTaskReport(
    header: List<String>,
    samples: List<TimedSnapshot>,
    generations: List<GenerationStats>,
    logcat: String,
): String = buildString {
    appendLine("# Pocket Travel - AI task manager")
    header.forEach { appendLine(it) }
    appendLine()
    appendLine("## samples")
    appendLine("time,pss_mb,native_pss_mb,native_heap_mb,avail_ram_mb,low_memory,cpu_percent,threads,model,generating")
    samples.forEach { appendLine(sampleRow(it)) }
    appendLine()
    appendLine("## generations")
    appendLine("ended_at,model,prompt_tokens,generated_tokens,context_used,context_size,first_token_ms,total_ms,tokens_per_s")
    generations.forEach { appendLine(generationRow(it)) }
    appendLine()
    appendLine("## logcat")
    append(logcat)
}

private fun sampleRow(sample: TimedSnapshot): String {
    val s = sample.snapshot
    return listOf(
        time(sample.atMs), mb(s.totalPssBytes), s.nativePssBytes?.let(::mb) ?: "", mb(s.nativeHeapBytes),
        mb(s.systemAvailableRamBytes), s.lowMemory, String.format(Locale.ROOT, "%.0f", s.cpuPercent),
        s.threadCount ?: "", s.runtime.loadedModelId ?: "", s.runtime.isGenerating,
    ).joinToString(",")
}

private fun generationRow(g: GenerationStats): String = listOf(
    time(g.endedAtMs), g.modelId ?: "", g.context?.promptTokens ?: "", g.context?.generatedTokens ?: "",
    g.context?.usedTokens ?: "", g.context?.contextSize ?: "", g.timeToFirstTokenMs ?: "", g.totalMs,
    g.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
).joinToString(",")
