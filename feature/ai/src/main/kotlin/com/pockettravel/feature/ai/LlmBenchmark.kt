package com.pockettravel.feature.ai

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
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
)

@Singleton
class LlmBenchmark @Inject constructor(
    private val engine: OnDeviceLlmEngine,
    @ApplicationContext context: Context,
) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    suspend fun run(modelId: String): BenchmarkResult {
        val hotBefore = isOverheating(thermalStatus())
        // Caricamento e generazioni sotto un unico lock: tutte le risposte vengono dallo stesso modello.
        val batch = engine.generateAll(PROMPTS.map { it.prompt })
        val answers = batch.answers.map { it.trim() }
        return scoreBenchmarkAnswers(modelId, PROMPTS, answers, batch.generationTimeMs.coerceAtLeast(1L), batch.loadTimeMs)
            .copy(overheating = hotBefore || isOverheating(thermalStatus()))
    }

    private fun thermalStatus(): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager?.currentThermalStatus else null

    internal companion object {
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
