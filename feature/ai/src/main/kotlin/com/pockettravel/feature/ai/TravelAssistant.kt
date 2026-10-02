package com.pockettravel.feature.ai

import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.Note
import com.pockettravel.core.data.NoteRepository
import com.pockettravel.core.data.officialSourceFor
import com.pockettravel.core.sync.currentGuidesLanguage
import javax.inject.Inject

data class AssistantAnswer(
    val text: String,
    val sourceCitations: List<String>,
    val showOfficialSourceBanner: Boolean,
    val officialSourceUrl: String? = null,
)

/**
 * Orchestrazione dell'assistente nelle due modalità: "Sul dispositivo" usa il RAG semplice sulle
 * guide del paese e delle città in region.db (ricerca full-text come CONTESTO del prompt locale),
 * più la nota personale più pertinente se ce n'è una; "Online" invia solo la domanda a un servizio
 * esterno con la chiave personale dell'utente, senza contesto RAG e senza note — per questo non
 * produce citazioni di sezione. In entrambe le modalità, le sezioni trovate su dogane/salute
 * attivano il banner "Verifica sempre sulla fonte ufficiale" con link diretto alla fonte pertinente.
 */
class TravelAssistant @Inject constructor(
    private val guideRepository: GuideRepository,
    private val cityRepository: CityRepository,
    private val noteRepository: NoteRepository,
    private val engine: OnDeviceLlmEngine,
    private val onlineLlmClient: OnlineLlmClient,
    private val aiSettingsStore: AiSettingsStore,
    private val nationalityPreferences: NationalityPreferences,
) {
    suspend fun ask(regionId: String, question: String, mode: AiEngineMode): AssistantAnswer {
        // Lingua dell'interfaccia: prompt, testo di ripiego e citazioni (le guide installate la seguono).
        val language = currentGuidesLanguage()
        val ftsQuery = buildFtsQuery(question, regionId)
        val sections = if (ftsQuery.isBlank()) {
            emptyList()
        } else {
            mergeBestSections(
                guideRepository.searchInRegionScored(regionId, ftsQuery, MAX_SECTIONS),
                cityRepository.searchInRegionScored(regionId, ftsQuery, MAX_SECTIONS),
                MAX_SECTIONS,
                language,
            )
        }
        val regulatedMatch = sections.firstOrNull { it.isRegulatedTopic() }

        return when (mode) {
            AiEngineMode.ON_DEVICE -> askOnDevice(question, sections, language)
            AiEngineMode.ONLINE -> askOnline(question, language)
        }.copy(
            showOfficialSourceBanner = regulatedMatch != null,
            officialSourceUrl = regulatedMatch?.let { officialSourceFor(it.category, nationalityPreferences.nationality.value)?.url },
        )
    }

    private suspend fun askOnDevice(question: String, sections: List<AssistantSection>, language: String): AssistantAnswer {
        // Ricerca sulla domanda in linguaggio naturale, non sulla ftsQuery (sintassi OR specifica
        // delle guide): NoteRepository.search fa la sua tokenizzazione, vedi rankNotesByQuery.
        val note = noteRepository.search(question, limit = 1).firstOrNull()
        val context = buildOnDeviceContext(sections, note, language = language)
        val prompt = PromptTemplates.onDevicePrompt(
            context = context.ifBlank { PromptTemplates.emptyContext(language) },
            question = question,
            language = language,
        )
        return AssistantAnswer(
            text = engine.generate(prompt),
            sourceCitations = sections.map { it.citation },
            showOfficialSourceBanner = false,
        )
    }

    private suspend fun askOnline(question: String, language: String): AssistantAnswer {
        val apiKey = aiSettingsStore.apiKey() ?: error("Nessuna chiave API online configurata")
        val text = onlineLlmClient.generate(
            baseUrl = aiSettingsStore.baseUrl(),
            apiKey = apiKey,
            model = aiSettingsStore.model(),
            prompt = OnlinePromptTemplates.onlinePrompt(question, language),
        )
        return AssistantAnswer(text = text, sourceCitations = emptyList(), showOfficialSourceBanner = false)
    }

    private companion object {
        const val MAX_SECTIONS = 3
    }
}

/** Sezione di contesto per il prompt, dalla guida del paese o da quella di una città. */
internal data class AssistantSection(
    val body: String,
    val category: GuideCategory,
    val citation: String,
)

private fun AssistantSection.isRegulatedTopic(): Boolean =
    category == GuideCategory.DOGANE || category == GuideCategory.SALUTE

// CC BY-SA 4.0 impone di indicare la fonte: titolo + link all'articolo originale, non solo il
// nome "Wikivoyage".
private fun GuideSection.toAssistantSection(language: String) = AssistantSection(
    body = body,
    category = category,
    citation = if (language == "en") "Source: Wikivoyage, section $title — $sourceUrl" else "Fonte: Wikivoyage, sezione $title — $sourceUrl",
)

private fun CitySection.toAssistantSection(language: String) = AssistantSection(
    body = body,
    category = category,
    citation = if (language == "en") "Source: Wikivoyage, section $title ($city) — $sourceUrl" else "Fonte: Wikivoyage, sezione $title ($city) — $sourceUrl",
)

/**
 * Unisce i candidati della guida del paese e delle guide delle città in un'unica classifica per
 * punteggio matchinfo (stesso schema in GuideRepository e CityRepository: FTS4 non ha bm25()),
 * tenendo solo le [limit] sezioni migliori in totale.
 */
internal fun mergeBestSections(
    guideMatches: List<Pair<GuideSection, Double>>,
    cityMatches: List<Pair<CitySection, Double>>,
    limit: Int,
    language: String = "it",
): List<AssistantSection> =
    (guideMatches.map { (section, score) -> section.toAssistantSection(language) to score } +
        cityMatches.map { (section, score) -> section.toAssistantSection(language) to score })
        .sortedByDescending { (_, score) -> score }
        .take(limit)
        .map { (section, _) -> section }

/**
 * Corpo del contesto RAG: le sezioni migliori più, se c'è, la nota personale più pertinente alla
 * domanda, etichettata "Nota personale:" — nello stesso limite di caratteri del contesto, non in
 * aggiunta (vedi truncateContext).
 */
// La nota ha uno spazio suo (fino a NOTE_MAX_CHARS) dentro maxChars: prima veniva dopo le sezioni e
// un contesto lungo la tagliava via tutta, proprio l'informazione piu' personale.
private const val NOTE_MAX_CHARS = 500

internal fun buildOnDeviceContext(sections: List<AssistantSection>, note: Note?, maxChars: Int = 2_000, language: String = "it"): String {
    // Etichetta nella lingua del prompt: i modelli inglesi la vedono cosi' nel training (generate_sft_dataset_en.py).
    val label = if (language == "en") "Personal note" else "Nota personale"
    val noteText = note?.let { truncateContext("$label: ${it.title}\n${it.body}", NOTE_MAX_CHARS) }
    val sectionsBudget = maxChars - (noteText?.let { it.length + 2 } ?: 0)
    val sectionsText = truncateContext(sections.joinToString("\n\n") { it.body }, sectionsBudget.coerceAtLeast(0))
    return listOfNotNull(sectionsText.takeIf { it.isNotBlank() }, noteText).joinToString("\n\n")
}

/**
 * FTS4 MATCH non tollera bene una domanda in linguaggio naturale così com'è (punteggiatura,
 * parole troppo corte che sono quasi sempre rumore): si tengono solo i token di almeno 4
 * lettere/cifre, uniti con OR per allargare il richiamo invece di richiederli tutti. Si scartano
 * anche i token che fanno parte del nome della regione (es. "marino" per "san-marino"): il filtro
 * per regionId in searchInRegion restringe gia' alla regione giusta, quindi in query sono solo
 * rumore che compare in ogni sezione e confonde il ranking per rilevanza.
 */
internal fun buildFtsQuery(question: String, regionId: String): String {
    val regionNameTokens = regionId.split(Regex("[^\\p{L}\\p{N}]+")).map { it.lowercase() }.toSet()
    return question
        .split(Regex("\\s+"))
        // minuscolo: FTS riconosce AND/OR/NOT/NEAR come operatori solo in maiuscolo, e il confronto dei
        // termini ignora comunque maiuscole e minuscole
        .map { token -> token.filter { it.isLetterOrDigit() }.lowercase() }
        .filter { it.length >= 4 && it !in regionNameTokens }
        .joinToString(" OR ")
}

// maxChars di default ~2000 = ~500 token (stima 4 caratteri/token) per il chunk RAG.
internal fun truncateContext(context: String, maxChars: Int = 2_000): String =
    if (context.length <= maxChars) context else context.take(maxChars)
