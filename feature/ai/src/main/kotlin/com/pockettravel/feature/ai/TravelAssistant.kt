package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.officialSourceFor
import javax.inject.Inject

data class AssistantAnswer(
    val text: String,
    val sourceCitations: List<String>,
    val showOfficialSourceBanner: Boolean,
    val officialSourceUrl: String? = null,
)

/**
 * Orchestrazione dell'assistente nelle due modalità: "Sul dispositivo" usa il RAG semplice
 * sulle guide in region.db (ricerca full-text come CONTESTO del prompt locale); "Online" invia
 * solo la domanda a un servizio esterno con la chiave personale dell'utente, senza contesto RAG
 * — per questo non produce citazioni di sezione. In entrambe le modalità, le sezioni trovate su
 * dogane/salute attivano il banner "Verifica sempre sulla fonte ufficiale" con link diretto alla
 * fonte pertinente.
 */
class TravelAssistant @Inject constructor(
    private val guideRepository: GuideRepository,
    private val engine: OnDeviceLlmEngine,
    private val onlineLlmClient: OnlineLlmClient,
    private val aiSettingsStore: AiSettingsStore,
) {
    suspend fun ask(regionId: String, question: String, mode: AiEngineMode): AssistantAnswer {
        val ftsQuery = buildFtsQuery(question)
        val matches = if (ftsQuery.isBlank()) {
            emptyList()
        } else {
            guideRepository.searchInRegion(regionId, ftsQuery).take(MAX_SECTIONS)
        }
        val regulatedMatch = matches.firstOrNull { it.isRegulatedTopic() }

        return when (mode) {
            AiEngineMode.ON_DEVICE -> askOnDevice(question, matches)
            AiEngineMode.ONLINE -> askOnline(question)
        }.copy(
            showOfficialSourceBanner = regulatedMatch != null,
            officialSourceUrl = regulatedMatch?.let { officialSourceFor(it.category).url },
        )
    }

    private suspend fun askOnDevice(question: String, matches: List<GuideSection>): AssistantAnswer {
        val context = truncateContext(matches.joinToString("\n\n") { it.body })
        val prompt = PromptTemplates.onDevicePrompt(
            context = context.ifBlank { "Nessuna informazione disponibile per questa regione." },
            question = question,
        )
        return AssistantAnswer(
            text = engine.generate(prompt),
            // CC BY-SA 4.0 impone di indicare la fonte: titolo + link all'articolo
            // originale, non solo il nome "Wikivoyage".
            sourceCitations = matches.map { "Fonte: Wikivoyage, sezione ${it.title} — ${it.sourceUrl}" },
            showOfficialSourceBanner = false,
        )
    }

    private suspend fun askOnline(question: String): AssistantAnswer {
        val apiKey = aiSettingsStore.apiKey() ?: error("Nessuna chiave API online configurata")
        val text = onlineLlmClient.generate(
            baseUrl = aiSettingsStore.baseUrl(),
            apiKey = apiKey,
            model = aiSettingsStore.model(),
            prompt = OnlinePromptTemplates.onlinePrompt(question),
        )
        return AssistantAnswer(text = text, sourceCitations = emptyList(), showOfficialSourceBanner = false)
    }

    private companion object {
        const val MAX_SECTIONS = 3
    }
}

private fun GuideSection.isRegulatedTopic(): Boolean =
    category == GuideCategory.DOGANE || category == GuideCategory.SALUTE

/**
 * FTS4 MATCH non tollera bene una domanda in linguaggio naturale così com'è (punteggiatura,
 * parole troppo corte che sono quasi sempre rumore): si tengono solo i token di almeno 4
 * lettere/cifre, uniti con OR per allargare il richiamo invece di richiederli tutti.
 */
internal fun buildFtsQuery(question: String): String =
    question
        .split(Regex("\\s+"))
        .map { token -> token.filter { it.isLetterOrDigit() } }
        .filter { it.length >= 4 }
        .joinToString(" OR ")

// maxChars di default ~2000 = ~500 token (stima 4 caratteri/token) per il chunk RAG.
internal fun truncateContext(context: String, maxChars: Int = 2_000): String =
    if (context.length <= maxChars) context else context.take(maxChars)
