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
    appendLine(
        "time,pss_mb,native_pss_mb,native_heap_mb,avail_ram_mb,low_memory,cpu_percent,threads,model,generating," +
            "thermal_status,thermal_headroom,battery_c,charging",
    )
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
        s.thermalStatus?.let(::thermalStatusName) ?: "", s.thermalHeadroom?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "",
        s.batteryCelsius?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "", s.charging ?: "",
    ).joinToString(",")
}

// Nomi delle costanti PowerManager.THERMAL_STATUS_* (0 = NONE ... 6 = SHUTDOWN).
private val THERMAL_STATUS_NAMES = listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

/** Nome inglese di uno stato PowerManager.THERMAL_STATUS_*, o il numero se il sistema ne aggiunge uno nuovo. */
internal fun thermalStatusName(status: Int): String = THERMAL_STATUS_NAMES.getOrNull(status) ?: status.toString()

/** Riga dell'intestazione con il SoC: produttore e modello (Android 12+), altrimenti Build.HARDWARE. */
internal fun socHeader(manufacturer: String?, model: String?, hardware: String): String {
    val known = listOf(manufacturer, model).filter { !it.isNullOrBlank() && it != "unknown" }
    return "soc: " + (known.joinToString(" ").ifEmpty { "hardware=$hardware" })
}

/**
 * Riga dell'intestazione con la frequenza massima dei core in MHz, a gruppi di core consecutivi uguali (i cluster):
 * `cpu_max_mhz: 0-3=2000, 4-6=2800, 7=3300`; "unavailable" se il sistema non espone cpufreq (emulatore x86).
 */
internal fun cpuFrequencyHeader(maxKhz: List<Int?>): String {
    if (maxKhz.all { it == null }) return "cpu_max_mhz: unavailable"
    val groups = mutableListOf<Triple<Int, Int, Int?>>()
    maxKhz.forEachIndexed { core, khz ->
        val last = groups.lastOrNull()
        if (last != null && last.third == khz) groups[groups.lastIndex] = last.copy(second = core) else groups += Triple(core, core, khz)
    }
    return groups.joinToString(", ", prefix = "cpu_max_mhz: ") { (first, last, khz) ->
        val cores = if (first == last) "$first" else "$first-$last"
        "$cores=${khz?.let { it / KHZ_PER_MHZ } ?: "?"}"
    }
}

private const val KHZ_PER_MHZ = 1000

private fun generationRow(g: GenerationStats): String = listOf(
    time(g.endedAtMs), g.modelId ?: "", g.context?.promptTokens ?: "", g.context?.generatedTokens ?: "",
    g.context?.usedTokens ?: "", g.context?.contextSize ?: "", g.timeToFirstTokenMs ?: "", g.totalMs,
    g.tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
    g.promptTokensPerSecond?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
).joinToString(",")
