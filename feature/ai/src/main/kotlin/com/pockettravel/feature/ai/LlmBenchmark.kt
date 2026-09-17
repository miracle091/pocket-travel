package com.pockettravel.feature.ai

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Punteggio approssimato, non un vero eval linguistico: confronta la risposta con parole chiave
 * attese e una lunghezza minima plausibile per prompt, non un giudizio semantico. Nessun modello
 * giudice disponibile on-device per valutare la qualità in modo piu' rigoroso — serve solo a dare
 * un'idea relativa (questo modello risponde più a tema di quell'altro, su questo device), non un
 * voto assoluto.
 */
internal data class BenchmarkPrompt(val prompt: String, val expectedKeywords: List<String>, val minAnswerLength: Int)

data class BenchmarkResult(
    val modelId: String,
    // Non un vero conteggio di token (OnDeviceLlmEngine/LiteRT-LM non lo espone qui): parole
    // separate da spazi, un proxy piu' onesto da nominare "wordsPerSecond" che far finta di
    // precisione con "tokensPerSecond".
    val wordsPerSecond: Float,
    val totalLatencyMs: Long,
    val qualityScore: Int,
    val ranAt: Long,
)

@Singleton
class LlmBenchmark @Inject constructor(
    private val engine: OnDeviceLlmEngine,
) {
    suspend fun run(modelId: String): BenchmarkResult {
        val start = System.currentTimeMillis()
        val answers = PROMPTS.map { engine.generate(it.prompt).trim() }
        val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        return scoreBenchmarkAnswers(modelId, PROMPTS, answers, elapsedMs)
    }

    internal companion object {
        // Domande generiche sul dominio viaggio, non legate a una regione specifica: il
        // benchmark valuta il modello grezzo via OnDeviceLlmEngine.generate(), non la
        // pipeline RAG di TravelAssistant (che dipende dai contenuti di una regione scaricata).
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
        )
    }
}

// Estratta a parte per essere testabile in JVM puro: OnDeviceLlmEngine.generate() dipende da
// LiteRT-LM (nativo), non istanziabile in un unit test — stesso approccio di
// DeviceAiCapability.ramTierFor per lo stesso motivo.
internal fun scoreBenchmarkAnswers(
    modelId: String,
    prompts: List<BenchmarkPrompt>,
    answers: List<String>,
    elapsedMs: Long,
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
        qualityScore = qualityScore,
        ranAt = System.currentTimeMillis(),
    )
}
