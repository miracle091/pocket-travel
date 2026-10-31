package com.pockettravel.feature.ai

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.pockettravel.core.data.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Punteggio approssimato, non un vero eval linguistico: confronta la risposta con parole chiave
 * attese e una lunghezza minima plausibile per prompt, non un giudizio semantico. Nessun modello
 * giudice disponibile sul dispositivo per valutare la qualita' in modo piu' rigoroso — serve solo a dare
 * un'idea relativa (questo modello risponde piu' a tema di quell'altro, su questo dispositivo), non un
 * voto assoluto.
 */
internal data class BenchmarkPrompt(val prompt: String, val expectedKeywords: List<String>, val minAnswerLength: Int)

data class BenchmarkResult(
    val modelId: String,
    // Non un vero conteggio di token (OnDeviceLlmEngine/llama.cpp non lo espone qui): parole
    // separate da spazi, per questo il campo si chiama wordsPerSecond e non tokensPerSecond.
    val wordsPerSecond: Float,
    val totalLatencyMs: Long,
    // Tempo di Engine.initialize() (una tantum, non ripetuto se il modello e' gia' in memoria)
    // separato dalla generazione — vedi OnDeviceLlmEngine.generateAll(). 0 se il motore era
    // gia' caricato al momento del run (es. un secondo benchmark sullo stesso modello nella
    // stessa sessione).
    val loadTimeMs: Long,
    val qualityScore: Int,
    val ranAt: Long,
    // Telefono in surriscaldamento all'inizio o alla fine della misura: il sistema rallenta la CPU e la velocita'
    // misurata e' piu' bassa del solito. Solo per la misura appena fatta, non salvato.
    val overheating: Boolean = false,
    // Mediane di runTokens in token/s: domanda (pp512) e risposta (tg128). 0 nei risultati salvati prima di questa misura.
    val promptTokensPerSecond: Float = 0f,
    val genTokensPerSecond: Float = 0f,
)

@Singleton
class LlmBenchmark @Inject constructor(
    private val engine: OnDeviceLlmEngine,
    @ApplicationContext context: Context,
    private val aiSettingsStore: AiSettingsStore,
    private val modelManager: LlmModelManager,
    private val deviceAiCapability: DeviceAiCapability,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    /** Prima la velocita' in token/s ([runTokens]), poi la qualita' sulle domande di [PROMPTS], con il modello gia' caricato. */
    suspend fun run(modelId: String): BenchmarkResult {
        val tokens = runTokens()
        check(tokens.modelId == modelId) { "Benchmark di ${tokens.modelId} al posto di $modelId" }
        // Caricamento e generazioni sotto un unico lock: tutte le risposte vengono dallo stesso modello.
        val batch = engine.generateAll(PROMPTS.map { it.prompt })
        val answers = batch.answers.map { it.trim() }
        return scoreBenchmarkAnswers(modelId, PROMPTS, answers, batch.generationTimeMs.coerceAtLeast(1L), tokens.loadTimeMs ?: batch.loadTimeMs)
            .copy(
                overheating = tokens.overheating || isOverheating(thermalStatus()),
                promptTokensPerSecond = tokens.pp.median.toFloat(),
                genTokensPerSecond = tokens.tg.median.toFloat(),
            )
    }

    /**
     * Benchmark pp512/tg128 in token/s sul modello scelto (protocollo di [measureStable]), nello schema di
     * [BenchRecord]. Thread: i core veloci ([fastCoreCount]). Tiene il lock del modello per tutta la misura e azzera
     * la conversazione in corso: da lanciare a modello libero, a telefono fresco.
     */
    internal suspend fun runTokens(): BenchRecord {
        val definition = aiSettingsStore.selectedModelDefinition()
        val file = modelManager.modelFile(definition)
        val threads = fastCoreCount(withContext(ioDispatcher) { readCoreMaxFreqsKhz() }, deviceAiCapability.inferenceThreadCount())
        val hotBefore = isOverheating(thermalStatus())
        val sha256 = definition.sha256 ?: withContext(ioDispatcher) { sha256Of(file) }
        return engine.withBench { runner ->
            val pp = measureStable { tokensPerSecond(BENCH_N_PROMPT, runner.run(BENCH_N_PROMPT, 0, threads, BENCH_SEED).ppNanos) }
            val tg = measureStable { tokensPerSecond(BENCH_N_GEN, runner.run(0, BENCH_N_GEN, threads, BENCH_SEED).tgNanos) }
            BenchRecord(
                source = "app",
                modelId = definition.id,
                modelFile = file.name,
                modelSha256 = sha256,
                llamaCpp = runner.llamaVersion,
                seed = BENCH_SEED,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                threads = threads,
                nPrompt = BENCH_N_PROMPT,
                nGen = BENCH_N_GEN,
                pp = pp,
                tg = tg,
                overheating = hotBefore || isOverheating(thermalStatus()) || isThrottled(pp.samples) || isThrottled(tg.samples),
                loadTimeMs = runner.loadTimeMs,
                ranAt = System.currentTimeMillis(),
            )
        }
    }

    private fun thermalStatus(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager?.currentThermalStatus else null

    internal companion object {
        // Come llama-bench: pp512 e tg128. Il seme fissa i token casuali (ai_chat.cpp, bench_impl).
        const val BENCH_N_PROMPT = 512
        const val BENCH_N_GEN = 128
        const val BENCH_SEED = 42

        // Domande generiche sul dominio viaggio, non legate a una regione specifica: il
        // benchmark valuta il modello grezzo via OnDeviceLlmEngine.generate(), non la
        // scelta del contesto di TravelAssistant (che dipende dai contenuti di una regione scaricata).
        // Sei categorie diverse (documenti, fuso orario, sicurezza, visti, aritmetica breve,
        // usi e costumi) per un segnale di qualita' un po' piu' robusto: resta comunque
        // un'euristica su parole chiave, non un eval semantico vero.
        val PROMPTS = listOf(
            BenchmarkPrompt(
                prompt = "Quali documenti servono di solito per attraversare una frontiera internazionale?",
                expectedKeywords = listOf("passaporto"),
                minAnswerLength = 20,
            ),
            BenchmarkPrompt(
                prompt = "Perché è importante controllare il fuso orario di destinazione prima di un viaggio?",
                expectedKeywords = listOf("fuso", "ora"),
                minAnswerLength = 20,
            ),
            BenchmarkPrompt(
                prompt = "Dai tre consigli di sicurezza generali per chi viaggia all'estero.",
                expectedKeywords = listOf("sicur"),
                minAnswerLength = 20,
            ),
            BenchmarkPrompt(
                prompt = "Qual è la differenza tra un visto turistico e un visto di transito?",
                expectedKeywords = listOf("visto"),
                minAnswerLength = 20,
            ),
            // Aritmetica breve, non dominio viaggio in senso stretto: un differenziatore reale
            // tra modelli piccoli (135M-4B), non solo un test di conoscenza enciclopedica.
            BenchmarkPrompt(
                prompt = "Se il volo parte alle 14:00 e bisogna essere in aeroporto 2 ore prima, a che ora bisogna arrivare in aeroporto?",
                expectedKeywords = listOf("12"),
                minAnswerLength = 5,
            ),
            BenchmarkPrompt(
                prompt = "Quali comportamenti è meglio evitare quando si visita un luogo di culto in un altro paese?",
                expectedKeywords = listOf("rispett"),
                minAnswerLength = 20,
            ),
        )
    }
}

// Estratta a parte per essere testabile in JVM puro: OnDeviceLlmEngine.generate() dipende da
// llama.cpp (nativo), non istanziabile in un unit test — stesso approccio di
// DeviceAiCapability.ramTierFor per lo stesso motivo.
internal fun scoreBenchmarkAnswers(
    modelId: String,
    prompts: List<BenchmarkPrompt>,
    answers: List<String>,
    elapsedMs: Long,
    loadTimeMs: Long = 0L,
): BenchmarkResult {
    require(prompts.size == answers.size) { "prompts e answers devono avere la stessa dimensione" }

    var totalWords = 0
    var matchedKeywords = 0
    var expectedKeywords = 0
    var answersLongEnough = 0

    prompts.zip(answers).forEach { (item, answer) ->
        totalWords += answer.split(Regex("\\s+")).count { it.isNotBlank() }
        val lowerAnswer = answer.lowercase()
        matchedKeywords += item.expectedKeywords.count { lowerAnswer.contains(it.lowercase()) }
        expectedKeywords += item.expectedKeywords.size
        if (answer.length >= item.minAnswerLength) answersLongEnough++
    }

    val safeElapsedMs = elapsedMs.coerceAtLeast(1L)
    val wordsPerSecond = totalWords / (safeElapsedMs / 1000f)
    val keywordScore = if (expectedKeywords > 0) matchedKeywords.toFloat() / expectedKeywords else 0f
    val lengthScore = if (prompts.isNotEmpty()) answersLongEnough.toFloat() / prompts.size else 0f
    val qualityScore = ((keywordScore * 0.7f + lengthScore * 0.3f) * 100).toInt()

    return BenchmarkResult(
        modelId = modelId,
        wordsPerSecond = wordsPerSecond,
        totalLatencyMs = safeElapsedMs,
        loadTimeMs = loadTimeMs,
        qualityScore = qualityScore,
        ranAt = System.currentTimeMillis(),
    )
}

/** Stato termico da THERMAL_STATUS_MODERATE (2) in su: il sistema rallenta gia' la CPU; null prima di Android 10. */
internal fun isOverheating(thermalStatus: Int?): Boolean = thermalStatus != null && thermalStatus >= THERMAL_STATUS_MODERATE

// PowerManager.THERMAL_STATUS_MODERATE, scritto qui perche' la costante esiste solo da Android 10.
private const val THERMAL_STATUS_MODERATE = 2

// Frequenza massima di ogni core in kHz; vuota se il sistema non lascia leggere i file (allora vale il numero di thread predefinito).
private fun readCoreMaxFreqsKhz(): List<Long> =
    (File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) } ?: emptyArray())
        .mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }

private fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
