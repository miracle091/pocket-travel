package com.pockettravel.feature.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.sqrt

/*
 * Benchmark in token/s, comune ad app e pipeline (eval_gguf.py --bench scrive lo stesso formato).
 *
 * Schema JSON, versione 1 (un oggetto per misura):
 *   schema            1
 *   source            "app" o "eval_gguf"
 *   model.id          id del catalogo, o null
 *   model.file        nome del file .gguf
 *   model.sha256      impronta del GGUF (sha256 del file: quella del catalogo, o calcolata)
 *   llama_cpp         versione di llama.cpp, "b<build>-<commit>"
 *   sampler           "none": nessun campionamento, si decodificano token casuali del seme (come llama-bench)
 *   seed              seme dei token casuali; null nella pipeline: llama-bench usa rand() senza seme, quindi sempre gli
 *                     stessi token, ma non quelli dell'app. La velocita' non dipende da quali token si decodificano
 *   device            modello del dispositivo (app) o della macchina (pipeline)
 *   threads           thread usati
 *   n_prompt, n_gen   token di pp e di tg (512 e 128)
 *   warmup            esecuzioni di riscaldamento scartate (1)
 *   pp, tg            { median_tps, cv_percent, samples_tps: [...], unstable }
 *   unstable          true se pp o tg hanno CV oltre il 10% dopo le ripetizioni extra
 *   overheating       true se il telefono era in surriscaldamento o la velocita' e' calata oltre il 15% tra le prime e le
 *                     ultime ripetizioni (isThrottled); sempre false nella pipeline
 *   load_time_ms      caricamento del modello, fuori dalle misure (null se non misurato)
 *   comparison_key    "<sha256[:12]>/<llama_cpp>/<device>/t<threads>/pp<n>tg<n>": due risultati con la stessa
 *                     chiave si possono confrontare direttamente
 *   ran_at            millisecondi dall'epoca
 */

/** Misure ripetute di una grandezza: mediana, coefficiente di variazione in percentuale e se e' instabile. */
internal data class StableMeasure(val samples: List<Double>, val median: Double, val cvPercent: Double, val unstable: Boolean)

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty()) { "nessun valore" }
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** Deviazione standard campionaria diviso la media, in percentuale; 0 con meno di due valori o media 0. */
internal fun cvPercent(values: List<Double>): Double {
    val mean = if (values.size < 2) 0.0 else values.average()
    if (mean == 0.0) return 0.0
    val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    return sqrt(variance) / mean * PERCENT
}

private const val PERCENT = 100

internal const val BENCH_WARMUP_RUNS = 1
internal const val BENCH_RUNS = 5
internal const val BENCH_CV_EXTRA_PERCENT = 5.0
internal const val BENCH_CV_UNSTABLE_PERCENT = 10.0

/**
 * Protocollo di misura: [BENCH_WARMUP_RUNS] riscaldamento scartato, [BENCH_RUNS] ripetizioni; se il CV supera il 5%
 * altre [BENCH_RUNS]; se alla fine supera il 10% la misura e' instabile. [run] restituisce una misura (token/s).
 */
internal suspend fun measureStable(run: suspend () -> Double): StableMeasure {
    repeat(BENCH_WARMUP_RUNS) { run() }
    val samples = MutableList(BENCH_RUNS) { run() }
    if (cvPercent(samples) > BENCH_CV_EXTRA_PERCENT) repeat(BENCH_RUNS) { samples += run() }
    val cv = cvPercent(samples)
    return StableMeasure(samples.toList(), median(samples), cv, cv > BENCH_CV_UNSTABLE_PERCENT)
}

/**
 * True se la velocita' cala oltre [BENCH_THROTTLE_PERCENT] tra le prime e le ultime [THROTTLE_WINDOW] ripetizioni: la CPU
 * rallenta per il calore anche prima che lo stato termico lo segnali.
 */
internal fun isThrottled(samples: List<Double>): Boolean {
    if (samples.size < 2 * THROTTLE_WINDOW) return false
    val first = median(samples.take(THROTTLE_WINDOW))
    return median(samples.takeLast(THROTTLE_WINDOW)) < first * (1 - BENCH_THROTTLE_PERCENT / PERCENT)
}

internal const val BENCH_THROTTLE_PERCENT = 15.0
private const val THROTTLE_WINDOW = 2

/** Token al secondo di un'esecuzione che ha decodificato [tokens] token in [nanos] nanosecondi. */
internal fun tokensPerSecond(tokens: Int, nanos: Long): Double = tokens * NANOS_PER_SECOND / nanos.coerceAtLeast(1L)

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Quanti thread usare: i core veloci, cioe' quelli con frequenza massima ([maxFreqsKhz], da cpuinfo_max_freq) di almeno il
 * 70% della piu' alta: esclude i core piccoli, che con thread liberi rendono la misura instabile (Pixel 8: 1 + 4 core su 9).
 * Senza frequenze leggibili vale [fallback].
 */
internal fun fastCoreCount(maxFreqsKhz: List<Long>, fallback: Int): Int {
    val top = maxFreqsKhz.maxOrNull() ?: 0L
    if (top <= 0L) return fallback
    return maxFreqsKhz.count { it * FAST_CORE_DENOMINATOR >= top * FAST_CORE_NUMERATOR }.coerceAtLeast(1)
}

/** Soglia dei core veloci: 7/10 della frequenza massima piu' alta. */
private const val FAST_CORE_NUMERATOR = 7
private const val FAST_CORE_DENOMINATOR = 10

/** Risultato completo di un benchmark pp/tg, nello schema documentato in testa a questo file. */
internal data class BenchRecord(
    val source: String,
    val modelId: String?,
    val modelFile: String,
    val modelSha256: String,
    val llamaCpp: String,
    val seed: Int,
    val device: String,
    val threads: Int,
    val nPrompt: Int,
    val nGen: Int,
    val pp: StableMeasure,
    val tg: StableMeasure,
    val overheating: Boolean,
    val loadTimeMs: Long?,
    val ranAt: Long,
) {
    val unstable: Boolean get() = pp.unstable || tg.unstable

    val comparisonKey: String
        get() = "${modelSha256.take(KEY_SHA_LENGTH)}/$llamaCpp/$device/t$threads/pp${nPrompt}tg$nGen"

    fun toJson(): JsonObject = buildJsonObject {
        put("schema", SCHEMA_VERSION)
        put("source", source)
        put("model", buildJsonObject {
            put("id", modelId)
            put("file", modelFile)
            put("sha256", modelSha256)
        })
        put("llama_cpp", llamaCpp)
        put("sampler", SAMPLER)
        put("seed", seed)
        put("device", device)
        put("threads", threads)
        put("n_prompt", nPrompt)
        put("n_gen", nGen)
        put("warmup", BENCH_WARMUP_RUNS)
        put("pp", pp.toJson())
        put("tg", tg.toJson())
        put("unstable", unstable)
        put("overheating", overheating)
        put("load_time_ms", loadTimeMs)
        put("comparison_key", comparisonKey)
        put("ran_at", ranAt)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val SAMPLER = "none"
        const val KEY_SHA_LENGTH = 12
    }
}

private fun StableMeasure.toJson(): JsonObject = buildJsonObject {
    put("median_tps", median)
    put("cv_percent", cvPercent)
    put("samples_tps", buildJsonArray { samples.forEach { add(JsonPrimitive(it)) } })
    put("unstable", unstable)
}
