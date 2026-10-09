package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
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
    appendLine("ended_at,model,prompt_tokens,generated_tokens,context_used,context_size,first_token_ms,total_ms,tokens_per_s,prompt_tokens_per_s")
    generations.forEach { appendLine(generationRow(it)) }
    appendLine()
    appendLine("## logcat")
    append(logcat)
}

/** Nome della variante CPU dal file: `libggml-cpu-android_armv8.6_1.so` diventa `android_armv8.6_1`. */
internal fun cpuVariantName(fileName: String): String =
    fileName.removePrefix("libggml-cpu").removePrefix("-").removeSuffix(".so").ifEmpty { "cpu" }

/** Riga dell'intestazione con la variante CPU di llama.cpp caricata, quella forzata, le estensioni e le varianti nell'APK. */
internal fun cpuBackendHeader(info: CpuBackendInfo?, available: List<String>): String {
    val variants = available.joinToString(" ") { cpuVariantName(it) }
    if (info == null) return "cpu_backend: not initialized, available=$variants"
    return listOf(
        "loaded=${info.loadedFile?.let(::cpuVariantName) ?: "none"}",
        "forced=${info.forcedFile?.let(::cpuVariantName) ?: "auto"}",
        "sve2=${if (info.sve2) 1 else 0}",
        "sme=${if (info.sme) 1 else 0}",
        "sve_vector_bits=${info.sveVectorBytes * BITS_PER_BYTE}",
        "available=$variants",
    ).joinToString(", ", prefix = "cpu_backend: ")
}

private const val BITS_PER_BYTE = 8

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
    g.promptTokensPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
).joinToString(",")
