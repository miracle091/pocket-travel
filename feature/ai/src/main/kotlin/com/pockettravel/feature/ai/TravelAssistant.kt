package com.pockettravel.feature.ai

import android.content.Context
import android.content.res.Configuration
import android.location.Location
import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.FtsCorpusStats
import com.pockettravel.core.data.FtsMatchInfo
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.LastKnownPosition
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.Note
import com.pockettravel.core.data.NoteRepository
import com.pockettravel.core.data.Poi
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.TransitRepository
import com.pockettravel.core.data.bm25Score
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.core.data.displayName
import com.pockettravel.core.data.officialSourceFor
import com.pockettravel.core.data.poiCategory
import com.pockettravel.core.data.vaccination.Trip
import com.pockettravel.core.data.vaccination.VaccinationPreferences
import com.pockettravel.core.data.vaccination.VaccinationRepository
import com.pockettravel.core.data.vaccination.toSummaryText
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.label
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import java.time.format.DateTimeFormatter
import java.util.Locale
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
 * più la nota personale più pertinente se ce n'è una e, per le domande su cosa c'è "qui vicino" o sui mezzi
 * pubblici, i POI e le prossime partenze attorno all'ultima posizione nota; "Online" invia solo la domanda a un servizio
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
    private val regionRepository: RegionRepository,
    private val vaccinationRepository: VaccinationRepository,
    private val vaccinationPreferences: VaccinationPreferences,
    private val poiRepository: PoiRepository,
    private val lastKnownPosition: LastKnownPosition,
    private val transitRepository: TransitRepository,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun ask(regionId: String, question: String, mode: AiEngineMode): AssistantAnswer {
        // Lingua dell'interfaccia: prompt, testo di ripiego e citazioni (le guide installate la seguono).
        val language = currentGuidesLanguage()
        val ftsQuery = buildFtsQuery(question, regionId)
        // La citta' nominata nella domanda: le sezioni delle citta' candidate sono solo le sue.
        val city = if (ftsQuery.isBlank()) null else namedCity(question, cityRepository.cityNamesFor(regionId))
        val sections = if (ftsQuery.isBlank()) {
            emptyList()
        } else {
            rankSections(
                guideRepository.searchCandidates(regionId, ftsQuery),
                cityRepository.searchCandidates(regionId, ftsQuery, city),
                MAX_SECTIONS,
                countryFirst = city == null,
                historyOrClimate = isHistoryOrClimateQuestion(question),
                language = language,
            )
        }
        val regulatedMatch = sections.firstOrNull { it.isRegulatedTopic() }

        return when (mode) {
            AiEngineMode.ON_DEVICE -> askOnDevice(
                question,
                listOfNotNull(
                    vaccinationSection(regionId, question, language),
                    nearbyPoiSection(regionId, question, language),
                    transitSection(regionId, question, language),
                ) + sections,
                language,
                focusStems(ftsQuery, city),
            )
            AiEngineMode.ONLINE -> askOnline(question, language)
        }.copy(
            showOfficialSourceBanner = regulatedMatch != null,
            officialSourceUrl = regulatedMatch?.let { officialSourceFor(it.category, nationalityPreferences.nationality.value)?.url },
        )
    }

    private suspend fun askOnDevice(question: String, sections: List<AssistantSection>, language: String, focusStems: Set<String>): AssistantAnswer {
        // Ricerca sulla domanda in linguaggio naturale, non sulla ftsQuery (sintassi OR specifica
        // delle guide): NoteRepository.search fa la sua tokenizzazione, vedi rankNotesByQuery.
        val note = noteRepository.search(question, limit = 1).firstOrNull()
        val context = buildOnDeviceContext(sections, note, language = language, focusStems = focusStems)
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

    /**
     * Per una domanda sui vaccini, l'esito calcolato per il viaggio verso il paese della regione dalla partenza
     * scelta nella schermata Vaccinazioni (altrimenti dalla nazionalita')
     * (stesso testo che il dataset di training mette nel contesto), prima delle sezioni della guida: cosi' il
     * modello locale non deve indovinare obblighi e consigli. Null se la domanda non parla di vaccini, se mancano
     * la partenza o il codice paese della regione, se coincidono, o se il pacchetto guide non ha i dati delle vaccinazioni.
     */
    private suspend fun vaccinationSection(regionId: String, question: String, language: String): AssistantSection? {
        if (!isVaccinationQuestion(question)) return null
        val destination = regionRepository.installed(regionId)?.countryCode ?: return null
        val departure = vaccinationPreferences.departure ?: nationalityPreferences.nationality.value ?: return null
        if (departure.equals(destination, ignoreCase = true)) return null
        val trip = Trip(departure = departure, destination = destination)
        val result = vaccinationRepository.evaluate(trip) ?: return null
        return AssistantSection(
            body = result.toSummaryText(trip, language),
            category = GuideCategory.SALUTE,
            citation = if (language == "en") "Source: Travel.gc.ca, TravelHealthPro (vaccinations)" else "Fonte: Travel.gc.ca, TravelHealthPro (vaccinazioni)",
        )
    }

    /**
     * Per una domanda su cosa c'è "qui vicino", i POI della regione attorno all'ultima posizione nota al telefono, prima
     * delle sezioni della guida. Null se la domanda non lo chiede, senza permesso di posizione, se la posizione ha più di
     * 15 minuti (l'utente potrebbe essere altrove) o se attorno non c'è nessun POI della regione.
     */
    private suspend fun nearbyPoiSection(regionId: String, question: String, language: String): AssistantSection? {
        if (!isNearbyQuestion(question)) return null
        val (latitude, longitude) = lastKnownPosition.get(maxAgeMillis = NEARBY_MAX_AGE_MILLIS) ?: return null
        val nearby = poiRepository.nearby(listOf(regionId), latitude, longitude)
        val distances = FloatArray(1)
        val pois = nearby.pois.take(NEARBY_CONTEXT_MAX).map { poi ->
            Location.distanceBetween(latitude, longitude, poi.latitude, poi.longitude, distances)
            poi to distances[0].toInt()
        }
        // Le etichette nella lingua del prompt, che può differire da quella del Context dell'applicazione.
        val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }).resources
        val body = nearbyPoiContext(pois, nearby.radiusMeters, { resources.getString(it.label()) }, language) ?: return null
        return AssistantSection(
            body = body,
            category = GuideCategory.DA_SAPERE,
            // ODbL: va indicata la fonte dei dati OpenStreetMap.
            citation = if (language == "en") "Source: © OpenStreetMap contributors (points of interest nearby)" else "Fonte: © contributori di OpenStreetMap (punti di interesse vicini)",
        )
    }

    /**
     * Per una domanda sui mezzi pubblici, il tabellone delle fermate vicine all'ultima posizione nota (al più 15 minuti
     * fa), dagli orari GTFS installati per la regione. Null se la domanda non ne parla, senza posizione o senza fermate vicine.
     */
    private suspend fun transitSection(regionId: String, question: String, language: String): AssistantSection? {
        if (!isTransitQuestion(question)) return null
        val (latitude, longitude) = lastKnownPosition.get(maxAgeMillis = NEARBY_MAX_AGE_MILLIS) ?: return null
        val board = transitRepository.board(regionId, latitude, longitude)
        val body = transitContext(board, language) ?: return null
        val feeds = when (board) {
            is TransitBoard.Departures -> board.feeds
            is TransitBoard.Expired -> board.feeds
            TransitBoard.NoStops -> emptyList()
        }.joinToString(", ") { "${it.name} (${it.attribution})" }
        return AssistantSection(
            body = body,
            category = GuideCategory.TRASPORTI,
            citation = if (language == "en") "Source: timetables of $feeds" else "Fonte: orari di $feeds",
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
        const val NEARBY_MAX_AGE_MILLIS = 15 * 60 * 1_000L
        // I più vicini: 30 POI con nome e orari supererebbero da soli il limite del contesto.
        const val NEARBY_CONTEXT_MAX = 12
    }
}

private val vaccinationWords = Regex("""vaccin|febbre gialla|yellow fever|polio|meningococc|meningitis|profilassi|certificat|hajj|umrah""", RegexOption.IGNORE_CASE)

/** True se la domanda parla di vaccini o certificati sanitari (italiano o inglese). */
internal fun isVaccinationQuestion(question: String): Boolean = vaccinationWords.containsMatchIn(question)

// "vicino a <luogo>" no: chiede di un altro posto, non della posizione dell'utente.
private val nearbyWords = Regex(
    """qui vicino|vicino a me|qui intorno|qui attorno|nei dintorni|nelle vicinanze|pi(ù|u'|u) vicin|near me|nearby|nearest|closest|around here|close by""",
    RegexOption.IGNORE_CASE,
)

// Radici di parole di storia o clima, a inizio parola ("rain" non deve trovare "train").
private val historyClimateWords = Regex(
    """\b(stori|fondat|fondò|secol|guerr|antic|roman[oaie]?\b|mediev|clima|temperatur|piov|piogg|neve|nevic|cald|fredd|estat|""" +
        """invern|meteo|stagion|histor|found|centur|wars?\b|ancient|medieval|weather|rain|snow|hot\b|cold\b|summer|winter|season)""",
    RegexOption.IGNORE_CASE,
)

/**
 * True se la domanda parla di storia o di clima (italiano o inglese). Senza, le sezioni Storia e Clima di Wikipedia
 * contano poco nella classifica (rankSections): lunghe e piene di nomi di luoghi, altrimenti scavalcherebbero le
 * sezioni pratiche della citta' (misurato con tools/data-pipeline/scripts/eval_retrieval.py).
 */
internal fun isHistoryOrClimateQuestion(question: String): Boolean = historyClimateWords.containsMatchIn(question)

/** True se la domanda chiede cosa c'è attorno alla posizione dell'utente (italiano o inglese). */
internal fun isNearbyQuestion(question: String): Boolean = nearbyWords.containsMatchIn(question)

/**
 * Testo dei POI vicini per il contesto: un'intestazione col raggio di ricerca, poi una riga per categoria ([label], al
 * plurale) con i nomi nella lingua del prompt, la distanza in metri ([pois] già in ordine di distanza) e gli orari OSM se
 * ci sono. Le categorie seguono il loro POI più vicino. Null se non c'è nessun POI.
 */
internal fun nearbyPoiContext(pois: List<Pair<Poi, Int>>, radiusMeters: Int, label: (PoiCategory) -> String, language: String): String? {
    if (pois.isEmpty()) return null
    val header = if (language == "en") "Points of interest within $radiusMeters m of your position:" else "Punti di interesse entro $radiusMeters m dalla tua posizione:"
    val hours = if (language == "en") "hours" else "orari"
    val lines = pois.groupBy { (poi, _) -> poi.poiCategory() }.map { (category, group) ->
        label(category) + ": " + group.joinToString(", ") { (poi, meters) ->
            poi.displayName(language) + " (" + listOfNotNull("$meters m", poi.openingHours?.let { "$hours $it" }).joinToString(", ") + ")"
        }
    }
    return (listOf(header) + lines).joinToString("\n")
}

private val transitWords = Regex(
    """\b(autobus|bus|tram|metro|metropolitana|treno|treni|filobus|traghetto|ferry|train|subway)\b|partenz|prossima corsa|departure|timetable""",
    RegexOption.IGNORE_CASE,
)

/** True se la domanda parla di mezzi pubblici o partenze (italiano o inglese). */
internal fun isTransitQuestion(question: String): Boolean = transitWords.containsMatchIn(question)

/**
 * Testo del tabellone delle fermate vicine per il contesto: le prossime partenze (ora della rete, minuti da adesso, mezzo,
 * linea e direzione), oppure che non ce ne sono o che gli orari sono scaduti. Null se vicino non c'è nessuna fermata. I
 * nomi dei mezzi sono il vocabolario del prompt (lo stesso del dataset di training), non le etichette della mappa.
 */
internal fun transitContext(board: TransitBoard, language: String): String? {
    val en = language == "en"
    return when (board) {
        TransitBoard.NoStops -> null
        is TransitBoard.Expired -> if (en) {
            "The installed public transport timetables expired on ${board.validUntil}."
        } else {
            "Gli orari dei mezzi pubblici installati sono scaduti il ${board.validUntil.format(DateTimeFormatter.ofPattern("d/M/yyyy"))}."
        }
        is TransitBoard.Departures -> if (board.items.isEmpty()) {
            if (en) "No departures in the next hours from the stops nearby." else "Nessuna partenza nelle prossime ore dalle fermate qui vicino."
        } else {
            val header = if (en) "Next departures from the stops nearby:" else "Prossime partenze dalle fermate qui vicino:"
            (listOf(header) + board.items.map { departure ->
                val time = "%02d:%02d".format(Locale.ROOT, departure.minuteOfDay / 60, departure.minuteOfDay % 60)
                val wait = if (en) "in ${departure.inMinutes} min" else "tra ${departure.inMinutes} min"
                val direction = departure.headsign?.let { if (en) " to $it" else " per $it" }.orEmpty()
                "$time ($wait) ${departure.mode.promptName(en)} ${departure.line}$direction"
            }).joinToString("\n")
        }
    }
}

private fun TransitMode.promptName(en: Boolean): String = when (this) {
    TransitMode.TRAM -> "Tram"
    TransitMode.METRO -> "Metro"
    TransitMode.TRAIN -> if (en) "Train" else "Treno"
    TransitMode.BUS -> if (en) "Bus" else "Autobus"
    TransitMode.TROLLEYBUS -> if (en) "Trolleybus" else "Filobus"
    TransitMode.FERRY -> if (en) "Ferry" else "Traghetto"
    TransitMode.CABLE -> if (en) "Cable car" else "Funivia"
    TransitMode.MONORAIL -> if (en) "Monorail" else "Monorotaia"
    TransitMode.OTHER -> if (en) "Line" else "Linea"
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

// Storia e Clima delle citta' vengono dalla voce di Wikipedia, il resto da Wikivoyage: il sito si legge dal link.
private fun CitySection.toAssistantSection(language: String): AssistantSection {
    val site = if (sourceUrl.substringAfter("://").substringBefore('/').endsWith("wikipedia.org")) "Wikipedia" else "Wikivoyage"
    return AssistantSection(
        body = body,
        category = category,
        citation = if (language == "en") "Source: $site, section $title ($city) — $sourceUrl" else "Fonte: $site, sezione $title ($city) — $sourceUrl",
    )
}

/**
 * Unisce i candidati della guida del paese e delle guide delle città in un'unica classifica BM25 con le statistiche
 * delle due tabelle sommate (FtsCorpusStats: con quelle di ognuna una parola qualunque varrebbe molto di più nelle
 * poche sezioni del paese che nelle centinaia delle città), tenendo solo le [limit] sezioni migliori in totale.
 * Con [countryFirst] (la domanda non nomina una città) le sezioni del paese vengono prima di quelle delle città;
 * senza [historyOrClimate] (isHistoryOrClimateQuestion) Storia e Clima valgono WIKIPEDIA_OFF_TOPIC_WEIGHT.
 * La ricerca (con namedCity, focusStems e selectContext) e' replicata in tools/data-pipeline/scripts/eval_retrieval.py,
 * che la misura su dati pubblicati: va cambiata insieme.
 */
internal fun rankSections(
    guideMatches: List<Pair<GuideSection, FtsMatchInfo>>,
    cityMatches: List<Pair<CitySection, FtsMatchInfo>>,
    limit: Int,
    countryFirst: Boolean = false,
    historyOrClimate: Boolean = true,
    language: String = "it",
): List<AssistantSection> {
    val tables = listOfNotNull(guideMatches.firstOrNull()?.second, cityMatches.firstOrNull()?.second)
    if (tables.isEmpty()) return emptyList()
    val stats = FtsCorpusStats.of(tables)
    val cityWeight = { category: GuideCategory ->
        if (!historyOrClimate && category in WIKIPEDIA_CATEGORIES) WIKIPEDIA_OFF_TOPIC_WEIGHT else 1.0
    }
    val ranked = guideMatches.map { (section, info) -> RankedSection(section.toAssistantSection(language), bm25Score(info, stats), fromCountry = true) } +
        cityMatches.map { (section, info) ->
            RankedSection(section.toAssistantSection(language), bm25Score(info, stats) * cityWeight(section.category), fromCountry = false)
        }
    return ranked
        .sortedWith(compareBy<RankedSection> { countryFirst && !it.fromCountry }.thenByDescending { it.score })
        .take(limit)
        .map { it.section }
}

private class RankedSection(val section: AssistantSection, val score: Double, val fromCountry: Boolean)

// Sezioni delle citta' da Wikipedia, e il loro peso quando la domanda non parla di storia o clima: restano candidate
// (se non c'e' altro), ma non scavalcano le sezioni pratiche.
private val WIKIPEDIA_CATEGORIES = setOf(GuideCategory.STORIA, GuideCategory.CLIMA)
private const val WIKIPEDIA_OFF_TOPIC_WEIGHT = 0.1

/** Nome della citta' come lo scrive chi chiede: senza il disambiguatore di Wikivoyage ("Porto (Portogallo)"). */
private fun spokenCityName(city: String): String = city.substringBefore(" (").trim()

// Minuscolo, senza accenti e con le parole separate da un solo spazio, anche agli estremi: "Forlì" e "forli"
// coincidono e "Bra" non si trova dentro "Braga".
private val combiningMarks = Regex("""\p{Mn}+""")
private val nonWord = Regex("""[^\p{L}\p{N}]+""")

/** [text] in minuscolo e senza accenti ("Città" -> "citta"), come confronta l'indice FTS (unicode61). */
private fun folded(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(combiningMarks, "")

private fun normalizedWords(text: String): String =
    folded(text).split(nonWord).filter { it.isNotEmpty() }.joinToString(" ", prefix = " ", postfix = " ")

// Nomi piu' corti (es. "Ne", "Lu") sono quasi sempre parole comuni nella domanda, non la citta'.
private const val MIN_CITY_NAME_CHARS = 3

/** La citta' di [cities] nominata in [question] (il nome piu' lungo che vi compare come parole intere), o null. */
internal fun namedCity(question: String, cities: List<String>): String? {
    val words = normalizedWords(question)
    return cities
        .filter { spokenCityName(it).length >= MIN_CITY_NAME_CHARS && normalizedWords(spokenCityName(it)) in words }
        .maxByOrNull { spokenCityName(it).length }
}

private const val STEM_CHARS = 5

/**
 * Radici (prime 5 lettere, senza accenti) delle parole della domanda in [ftsQuery], senza quelle della citta'
 * nominata [city]: scelgono i paragrafi di una sezione troppo lunga per il contesto ("estate" trova anche "estati").
 */
internal fun focusStems(ftsQuery: String, city: String?): Set<String> {
    val cityStems = city?.let { normalizedWords(spokenCityName(it)).split(' ').filter { w -> w.length >= 4 }.map { w -> w.take(STEM_CHARS) } }.orEmpty()
    return ftsQuery.split(" OR ").filter { it.isNotBlank() }.map { folded(it).take(STEM_CHARS) }.toSet() - cityStems.toSet()
}

/**
 * Corpo del contesto RAG: le sezioni migliori più, se c'è, la nota personale più pertinente alla
 * domanda, etichettata "Nota personale:" — nello stesso limite di caratteri del contesto, non in
 * aggiunta (vedi truncateContext). Le sezioni entrano in ordine (selectContext): con [focusStems] una sezione
 * troppo lunga lascia i paragrafi che parlano della domanda invece del solo inizio.
 */
// La nota ha uno spazio suo (fino a NOTE_MAX_CHARS) dentro maxChars: messa dopo le sezioni, un
// contesto lungo la taglierebbe via tutta, proprio l'informazione piu' personale.
private const val NOTE_MAX_CHARS = 500

internal fun buildOnDeviceContext(
    sections: List<AssistantSection>,
    note: Note?,
    maxChars: Int = 2_000,
    language: String = "it",
    focusStems: Set<String> = emptySet(),
): String {
    // Etichetta nella lingua del prompt: i modelli inglesi la vedono cosi' nel training (generate_sft_dataset_en.py).
    val label = if (language == "en") "Personal note" else "Nota personale"
    val noteText = note?.let { truncateContext("$label: ${it.title}\n${it.body}", NOTE_MAX_CHARS) }
    val sectionsBudget = maxChars - (noteText?.let { it.length + 2 } ?: 0)
    val sectionsText = selectContext(sections.map { it.body }, focusStems, sectionsBudget.coerceAtLeast(0))
    return listOfNotNull(sectionsText.takeIf { it.isNotBlank() }, noteText).joinToString("\n\n")
}

// Sotto questo spazio residuo una sezione in piu' sarebbe solo un frammento.
private const val MIN_SECTION_CHARS = 50

/**
 * Le sezioni [bodies], in ordine di rilevanza, unite da riga vuota entro [maxChars]: ognuna prende dallo spazio
 * rimasto quello che le serve, intera se ci sta, altrimenti i paragrafi con piu' radici [focusStems] (relevantParagraphs).
 */
internal fun selectContext(bodies: List<String>, focusStems: Set<String>, maxChars: Int): String {
    val parts = mutableListOf<String>()
    var remaining = maxChars
    for (body in bodies) {
        if (remaining < MIN_SECTION_CHARS) break
        val part = relevantParagraphs(body, focusStems, remaining)
        if (part.isNotBlank()) {
            parts += part
            remaining -= part.length + 2
        }
    }
    return truncateContext(parts.joinToString("\n\n"), maxChars)
}

/**
 * [body] se sta in [budget]; altrimenti i suoi paragrafi (righe) con piu' radici [focusStems], a parita' i primi,
 * finche' ci stanno, rimessi nell'ordine del testo. Un solo paragrafo troppo lungo: il suo inizio.
 */
internal fun relevantParagraphs(body: String, focusStems: Set<String>, budget: Int): String {
    if (body.length <= budget) return body
    val paragraphs = body.lines().filter { it.isNotBlank() }
    val hits = paragraphs.map { p -> folded(p).let { text -> focusStems.count { it in text } } }
    val kept = mutableListOf<Int>()
    var used = 0
    for (i in paragraphs.indices.sortedWith(compareByDescending<Int> { hits[it] }.thenBy { it })) {
        val cost = paragraphs[i].length + 1
        if (used + cost <= budget) {
            kept += i
            used += cost
        }
    }
    return if (kept.isEmpty()) body.take(budget) else kept.sorted().joinToString("\n") { paragraphs[it] }
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
        // Parole divise come le divide l'indice (unicode61): "dell'isola" e' "dell" e "isola", non "dellisola".
        .split(nonWord)
        // minuscolo: FTS riconosce AND/OR/NOT/NEAR come operatori solo in maiuscolo, e il confronto dei
        // termini ignora comunque maiuscole e minuscole
        .map { it.lowercase() }
        .filter { it.length >= 4 && it !in regionNameTokens }
        .joinToString(" OR ")
}

// maxChars di default ~2000 = ~500 token (stima 4 caratteri/token) per il chunk RAG.
internal fun truncateContext(context: String, maxChars: Int = 2_000): String =
    if (context.length <= maxChars) context else context.take(maxChars)
