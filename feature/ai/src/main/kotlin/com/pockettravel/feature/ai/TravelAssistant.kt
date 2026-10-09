package com.pockettravel.feature.ai

import android.content.Context
import android.content.res.Configuration
import android.location.Location
import com.pockettravel.core.data.CarRoute
import com.pockettravel.core.data.CarRouteCalculator
import com.pockettravel.core.data.CityRepository
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.FtsCorpusStats
import com.pockettravel.core.data.FtsMatchInfo
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.GuideSourceSite
import com.pockettravel.core.data.guideSourceSiteOf
import com.pockettravel.core.data.guideSourceUrlWithoutAudience
import com.pockettravel.core.data.isGuideSectionFor
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
import com.pockettravel.core.data.db.CityCoordinates
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.core.data.displayName
import com.pockettravel.core.data.officialSourceFor
import com.pockettravel.core.data.poiCategory
import com.pockettravel.core.data.urlFor
import com.pockettravel.core.data.vaccination.Trip
import com.pockettravel.core.data.vaccination.TripPurpose
import com.pockettravel.core.data.vaccination.VaccinationPreferences
import com.pockettravel.core.data.vaccination.VaccinationRepository
import com.pockettravel.core.data.vaccination.toSummaryText
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.label
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
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

/** Avanzamento di una risposta: il testo scritto finora, poi la risposta definitiva. */
sealed interface AssistantProgress {
    data class Partial(val text: String) : AssistantProgress
    data class Done(val answer: AssistantAnswer) : AssistantProgress
}

/**
 * Orchestrazione dell'assistente nei due motori: "Sul dispositivo" usa il RAG semplice sulle
 * guide della nazione e delle città in region.db (ricerca full-text come CONTESTO del prompt locale),
 * più la nota personale più pertinente se ce n'è una e, per le domande su cosa c'è "qui vicino" o sui mezzi
 * pubblici, i POI e le prossime partenze attorno all'ultima posizione nota, e per la distanza tra due citta' quella
 * calcolata dalle coordinate (e su strada con la rete stradale scaricata); "Online" invia solo la domanda a un servizio
 * esterno con la chiave personale dell'utente, senza contesto RAG e senza note — per questo non
 * produce citazioni di sezione. Con entrambi i motori, le sezioni trovate su dogane, salute o sicurezza
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
    private val emergencyNumbersRepository: EmergencyNumbersRepository,
    private val poiRepository: PoiRepository,
    private val lastKnownPosition: LastKnownPosition,
    private val transitRepository: TransitRepository,
    private val carRouteCalculator: CarRouteCalculator,
    @param:ApplicationContext private val context: Context,
) {
    /**
     * La risposta come flusso: sul dispositivo una serie di [AssistantProgress.Partial] (testo accumulato, al massimo
     * uno ogni PARTIAL_INTERVAL_MS) e in fondo [AssistantProgress.Done] con citazioni e banner; online solo il Done.
     * Gli errori escono dal flusso (anche a meta': il chiamante decide che farne del parziale); la cancellazione
     * del collector ferma la generazione.
     */
    fun ask(regionId: String, question: String, mode: AiEngineMode): Flow<AssistantProgress> = flow {
        // Lingua dell'interfaccia: prompt, testo di ripiego e citazioni (le guide installate la seguono).
        val language = currentGuidesLanguage()
        // Le citta' nominate nella domanda: le sezioni delle citta' candidate sono solo le loro (due per "quanto dista X da Y").
        val cities = namedCities(question, cityRepository.cityNamesFor(regionId), language)
        val city = cities.singleOrNull()
        val topicQuery = buildFtsQuery(question, regionId, city)
        val topicMatches = if (topicQuery.isBlank()) emptyList() else cityCandidates(regionId, topicQuery, cities)
        // Nessuna sezione della citta' nominata con le parole della domanda: si cerca anche il suo nome.
        val withCityName = city != null && topicQuery.isNotBlank() && topicMatches.isEmpty()
        val ftsQuery = if (withCityName) buildFtsQuery(question, regionId) else topicQuery
        val sections = if (ftsQuery.isBlank()) {
            emptyList()
        } else {
            val nationality = nationalityPreferences.nationality.value
            rankSections(
                guideRepository.searchCandidates(regionId, ftsQuery).filter { isGuideSectionFor(it.first.sourceUrl, nationality) },
                if (withCityName) cityCandidates(regionId, ftsQuery, cities) else topicMatches,
                MAX_SECTIONS,
                countryFirst = cities.isEmpty(),
                historyOrClimate = isHistoryOrClimateQuestion(question),
                language = language,
            )
        }
        val regulatedMatch = sections.firstOrNull { it.isRegulatedTopic() }
        // Per Viaggiare Sicuri il banner apre la pagina del paese della regione.
        val destination = regulatedMatch?.let { regionRepository.installed(regionId)?.countryCode }

        val withSource = { answer: AssistantAnswer ->
            answer.copy(
                showOfficialSourceBanner = regulatedMatch != null,
                officialSourceUrl = regulatedMatch?.let { officialSourceFor(it.category, nationalityPreferences.nationality.value)?.urlFor(destination) },
            )
        }
        when (mode) {
            AiEngineMode.ON_DEVICE -> {
                val emergency = emergencySection(regionId, question, language)
                val plan = planOnDevice(
                    question,
                    listOfNotNull(
                        cityDistanceSection(regionId, cities, question, language),
                        vaccinationSection(regionId, question, language),
                        emergency,
                        nearbyPoiSection(regionId, question, language),
                        transitSection(regionId, question, language),
                    ) + (emergency?.let { withoutEmergencyLine(sections, it.body) } ?: sections),
                    language,
                    focusStems(ftsQuery, city),
                )
                var text = ""
                engine.generateStream(plan.prompt).accumulated(PARTIAL_INTERVAL_MS).collect {
                    text = it
                    emit(AssistantProgress.Partial(it))
                }
                emit(AssistantProgress.Done(withSource(AssistantAnswer(text, plan.citations, showOfficialSourceBanner = false))))
            }
            AiEngineMode.ONLINE -> emit(AssistantProgress.Done(withSource(askOnline(question, language))))
        }
    }

    /** Candidati delle guide delle citta' [cities] (di ognuna, per "quanto dista X da Y"), o di tutte se e' vuota. */
    private suspend fun cityCandidates(regionId: String, ftsQuery: String, cities: List<String>) =
        cities.ifEmpty { listOf(null) }.flatMap { cityRepository.searchCandidates(regionId, ftsQuery, it) }

    /** Prompt per il modello locale e fonti da citare: calcolati dal contesto, non dalla risposta. */
    private class OnDevicePlan(val prompt: String, val citations: List<String>)

    private suspend fun planOnDevice(question: String, sections: List<AssistantSection>, language: String, focusStems: Set<String>): OnDevicePlan {
        // Ricerca sulla domanda in linguaggio naturale, non sulla ftsQuery (sintassi OR specifica
        // delle guide): NoteRepository.search fa la sua tokenizzazione, vedi rankNotesByQuery.
        val note = noteRepository.search(question, limit = 1).firstOrNull()
        val context = buildOnDeviceContext(sections, note, language = language, focusStems = focusStems)
        val prompt = PromptTemplates.onDevicePrompt(
            context = context.ifBlank { PromptTemplates.emptyContext(language) },
            question = question,
            language = language,
        )
        return OnDevicePlan(prompt, citedSections(sections, context).map { it.citation })
    }

    /**
     * Per una domanda sulla distanza o sul tempo di viaggio tra due citta' nominate ([cities]), la distanza in linea
     * d'aria dalle loro coordinate e, con la rete stradale della regione scaricata, il percorso in auto calcolato sul
     * telefono (al massimo CAR_ROUTE_TIMEOUT_MILLIS, poi solo la linea d'aria). Le guide riportano i km solo per
     * alcune coppie. Null senza le coordinate di una delle due (cities.db vecchio), e per il solo tempo di viaggio
     * senza percorso in auto: la linea d'aria non lo dice.
     */
    private suspend fun cityDistanceSection(regionId: String, cities: List<String>, question: String, language: String): AssistantSection? {
        val distance = isDistanceQuestion(question)
        val coordinates = if (cities.size == 2 && (distance || isTravelTimeQuestion(question))) {
            cities.mapNotNull { cityRepository.coordinatesFor(regionId, it) }
        } else {
            emptyList()
        }
        if (coordinates.size != 2) return null
        val (a, b) = coordinates
        val car = withTimeoutOrNull(CAR_ROUTE_TIMEOUT_MILLIS) { carRouteCalculator.carRoute(regionId, a, b) }
        return if (car == null && !distance) null else distanceSection(cities[0], cities[1], a, b, car, language)
    }

    private fun distanceSection(from: String, to: String, a: CityCoordinates, b: CityCoordinates, car: CarRoute?, language: String): AssistantSection {
        val straight = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, straight)
        val en = language == "en"
        return AssistantSection(
            body = cityDistanceContext(from, to, straight[0].toDouble(), car, language),
            category = GuideCategory.TRASPORTI,
            citation = when {
                car != null && en -> "Source: route calculated on the phone with BRouter, © OpenStreetMap contributors"
                car != null -> "Fonte: percorso calcolato sul telefono con BRouter, © contributori di OpenStreetMap"
                en -> "Source: coordinates of the cities from Wikidata (straight-line distance)"
                else -> "Fonte: coordinate delle città da Wikidata (distanza in linea d'aria)"
            },
        )
    }

    /**
     * Per una domanda sui vaccini, l'esito calcolato per il viaggio verso la nazione della regione dalla partenza
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
        val trip = Trip(departure = departure, destination = destination, destinationRegion = regionId, purpose = tripPurposeOf(question))
        val result = vaccinationRepository.evaluate(trip) ?: return null
        return AssistantSection(
            body = result.toSummaryText(trip, language),
            category = GuideCategory.SALUTE,
            citation = if (language == "en") "Source: Travel.gc.ca, TravelHealthPro (vaccinations)" else "Fonte: Travel.gc.ca, TravelHealthPro (vaccinazioni)",
        )
    }

    /**
     * Per una domanda sulle emergenze, i numeri di emergenza della regione (stessa riga dei Fatti rapidi), prima delle
     * sezioni della guida: cosi' arrivano al modello anche se la ricerca non classifica quella sezione. Null se la
     * domanda non parla di emergenze o se la regione non ha numeri (nessun numero centralizzato o pacchetto guide vecchio).
     */
    private suspend fun emergencySection(regionId: String, question: String, language: String): AssistantSection? {
        val numbers = (if (isEmergencyQuestion(question)) emergencyNumbersRepository.forRegion(regionId) else null) ?: return null
        return AssistantSection(
            body = emergencyNumbersContext(numbers, language),
            category = GuideCategory.FATTI_RAPIDI,
            citation = if (language == "en") "Source: emergency numbers (Travel.gc.ca, Wikipedia, Wikidata, gov.uk)" else "Fonte: numeri di emergenza (Travel.gc.ca, Wikipedia, Wikidata, gov.uk)",
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
        // Un aggiornamento del testo parziale ogni ~80 ms (~12 al secondo): fluido, senza ricomporre a ogni token.
        const val PARTIAL_INTERVAL_MS = 80L
        const val NEARBY_MAX_AGE_MILLIS = 15 * 60 * 1_000L
        // I più vicini: 30 POI con nome e orari supererebbero da soli il limite del contesto.
        const val NEARBY_CONTEXT_MAX = 12
        // Il percorso in auto tra due citta' lontane puo' richiedere minuti sul telefono (Milano-Roma 45 s sull'emulatore):
        // oltre questo tempo l'assistente risponde con la sola distanza in linea d'aria.
        const val CAR_ROUTE_TIMEOUT_MILLIS = 30_000L
    }
}

private val vaccinationWords = Regex("""vaccin|febbre gialla|yellow fever|polio|meningococc|meningitis|profilassi|certificat|hajj|umrah""", RegexOption.IGNORE_CASE)

/** True se la domanda parla di vaccini o certificati sanitari (italiano o inglese). */
internal fun isVaccinationQuestion(question: String): Boolean = vaccinationWords.containsMatchIn(question)

private val emergencyWords = Regex("""emergenz|ambulanz|polizia|pompier|vigili del fuoco|soccors|emergency|ambulance|police|fire brigade|fire department""", RegexOption.IGNORE_CASE)

/** True se la domanda parla di emergenze o di polizia, ambulanza, pompieri (italiano o inglese). */
internal fun isEmergencyQuestion(question: String): Boolean = emergencyWords.containsMatchIn(question)

/**
 * La riga dei numeri di emergenza per il contesto, uguale a quella dei Fatti rapidi della guida (la pipeline dei
 * contenuti la scrive con emergencyNumbersLine; gli esempi di training la copiano: da cambiare insieme).
 * "Generale" solo se la regione ha un numero unico.
 */
internal fun emergencyNumbersContext(numbers: EmergencyNumbers, language: String): String {
    val en = language == "en"
    val parts = buildList {
        numbers.general?.let { add("${if (en) "General" else "Generale"} $it") }
        add("${if (en) "Police" else "Polizia"} ${numbers.police}")
        add("${if (en) "Ambulance" else "Ambulanza"} ${numbers.ambulance}")
        add("${if (en) "Fire" else "Vigili del fuoco"} ${numbers.fire}")
    }
    return "${if (en) "Emergency numbers" else "Numeri di emergenza"}: ${parts.joinToString(", ")}"
}

/**
 * [sections] senza la riga dei numeri di emergenza [line] nei Fatti rapidi, quando e' gia' in testa al contesto: il
 * modello la vede una volta sola, come negli esempi di training (generate_sft.py --emergency).
 */
internal fun withoutEmergencyLine(sections: List<AssistantSection>, line: String): List<AssistantSection> =
    sections.map { section ->
        if (section.category != GuideCategory.FATTI_RAPIDI) section
        else section.copy(body = section.body.lines().filterNot { it.trim() == line }.joinToString("\n"))
    }.filter { it.body.isNotBlank() }

private val pilgrimageWords = Regex("""hajj|umrah""", RegexOption.IGNORE_CASE)

/** Hajj o Umrah nella domanda: senza lo scopo del viaggio il motore non mostra il MenACWY obbligatorio per i pellegrini. */
internal fun tripPurposeOf(question: String): TripPurpose? =
    if (pilgrimageWords.containsMatchIn(question)) TripPurpose.HAJJ_UMRAH else null

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

// "piu' vicina a Roma", "nearest pharmacy to the station": il riferimento e' un altro posto. "a me", "to me" restano vicino.
private val nearOtherPlace = Regex(
    """(pi(ù|u'|u) vicin\p{L}*|nearest|closest)(\s+\p{L}+){0,2}?\s+(a|ad|al|alla|allo|all'|ai|agli|alle|da|to|from)\s+(?!(me|noi|us|here|qui)\b)\p{L}""",
    RegexOption.IGNORE_CASE,
)

/** True se la domanda chiede cosa c'è attorno alla posizione dell'utente (italiano o inglese). */
internal fun isNearbyQuestion(question: String): Boolean =
    nearbyWords.containsMatchIn(question) && (nearOtherPlace.find(question) == null || hereWords.containsMatchIn(question))

// Parole che legano comunque la domanda alla posizione dell'utente, anche con un altro posto nominato.
private val hereWords = Regex("""qui vicino|vicino a me|qui intorno|qui attorno|near me|around here""", RegexOption.IGNORE_CASE)

/**
 * Le sezioni entrate nel contesto (selectContext ne lascia fuori quando lo spazio finisce): solo di quelle si citano le
 * fonti. Una sezione c'e' se l'inizio di uno dei suoi paragrafi compare nel contesto.
 */
internal fun citedSections(sections: List<AssistantSection>, context: String): List<AssistantSection> =
    sections.filter { section -> section.body.lines().any { it.isNotBlank() && it.take(CITATION_PROBE_CHARS) in context } }

private const val CITATION_PROBE_CHARS = 60

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

private const val TRANSIT_PROMPT_ROWS = 10

/**
 * Testo del tabellone delle fermate vicine per il contesto: le prossime partenze (ora della rete, minuti da adesso, mezzo,
 * linea e direzione), oppure che non ce ne sono o che gli orari sono scaduti; in fondo le reti scadute accanto a quelle
 * valide. Null se vicino non c'è nessuna fermata. I
 * nomi dei mezzi sono il vocabolario del prompt, non le etichette della mappa: gli esempi di training di generate_sft.py
 * --nearby (tools/data-pipeline/scripts/sft_nearby.py) copiano questo testo, da cambiare insieme.
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
        is TransitBoard.Departures -> {
            val lines = if (board.items.isEmpty()) {
                listOf(if (en) "No departures in the next hours from the stops nearby." else "Nessuna partenza nelle prossime ore dalle fermate qui vicino.")
            } else {
                val header = if (en) "Next departures from the stops nearby:" else "Prossime partenze dalle fermate qui vicino:"
                // Una riga per partenza, anche le successive di ogni linea, come negli esempi di training; al massimo
                // TRANSIT_PROMPT_ROWS: nelle fermate affollate il tabellone ha decine di linee, troppe per il prompt.
                val rows = board.items.flatMap { listOf(it) + it.later }.sortedBy { it.inMinutes }.take(TRANSIT_PROMPT_ROWS)
                listOf(header) + rows.map { departure ->
                    val time = "%02d:%02d".format(Locale.ROOT, departure.minuteOfDay / 60, departure.minuteOfDay % 60)
                    val wait = if (en) "in ${departure.inMinutes} min" else "tra ${departure.inMinutes} min"
                    val direction = departure.headsign?.let { if (en) " to $it" else " per $it" }.orEmpty()
                    val estimated = if (!departure.estimated) "" else if (en) " (estimated time)" else " (orario stimato)"
                    "$time ($wait) ${departure.mode.promptName(en)} ${departure.line}$direction$estimated"
                }
            }
            // Reti con fermate vicine ma orari scaduti: le loro partenze mancano dall'elenco, il modello lo deve sapere.
            (lines + board.expired.map { expiredFeedLine(it, en) }).joinToString("\n")
        }
    }
}

private fun expiredFeedLine(board: TransitBoard.Expired, en: Boolean): String {
    val name = board.feeds.firstOrNull()?.name
    return if (en) {
        "The timetables of ${name ?: "another network"} expired on ${board.validUntil}: " +
            if (board.estimated) "its departures are estimated from the previous week." else "its departures are not listed."
    } else {
        "Gli orari di ${name ?: "un'altra rete"} sono scaduti il ${board.validUntil.format(DateTimeFormatter.ofPattern("d/M/yyyy"))}: " +
            if (board.estimated) "le sue partenze sono stimate dalla settimana precedente." else "le sue partenze non sono nell'elenco."
    }
}

// "dista", "distante", "distanza", "lontana", "km", "how far", "miles"; il tempo di viaggio a parte (travelTimeWords).
private val distanceWords = Regex(
    """\b(dist(a|ano|ante|anti|anza|anze)|distance|distant|lontan\p{L}*|chilometri|km|far|kilomet\p{L}*|miles?)\b""",
    RegexOption.IGNORE_CASE,
)

// Solo il viaggio: "quanto tempo serve per visitare", "how long should I stay" chiedono quanto restare.
private val travelTimeWords = Regex(
    """quanto ci (si )?(vuole|mette)|quanto tempo (serve|ci vuole|ci si mette) per (andare|arrivare)|""" +
        """how long (does|is|will) (it take|the (trip|drive|journey))""",
    RegexOption.IGNORE_CASE,
)

/** True se la domanda chiede quanto e' lontano un posto (italiano o inglese). */
internal fun isDistanceQuestion(question: String): Boolean = distanceWords.containsMatchIn(question)

/** True se la domanda chiede quanto dura il viaggio (italiano o inglese). */
internal fun isTravelTimeQuestion(question: String): Boolean = travelTimeWords.containsMatchIn(question)

/**
 * Testo della distanza tra due citta' per il contesto: in linea d'aria dalle coordinate ([straightMeters]) e, se c'e',
 * il percorso in auto calcolato con la rete stradale scaricata ([car]); senza percorso, che su strada e' di piu'. Gli
 * esempi di training di generate_sft.py --distances (distance_context) copiano questo testo, da cambiare insieme.
 */
internal fun cityDistanceContext(from: String, to: String, straightMeters: Double, car: CarRoute?, language: String): String {
    val en = language == "en"
    val a = spokenCityName(from)
    val b = spokenCityName(to)
    val air = if (en) "$a and $b are ${kilometres(straightMeters)} km apart in a straight line." else "$a e $b distano ${kilometres(straightMeters)} km in linea d'aria."
    val road = when {
        car != null && en -> "By car the route is ${kilometres(car.distanceMeters)} km, about ${travelTime(car.durationSeconds)}."
        car != null -> "In auto il percorso è di ${kilometres(car.distanceMeters)} km, circa ${travelTime(car.durationSeconds)}."
        en -> "By road the distance is longer."
        else -> "Su strada la distanza è maggiore."
    }
    return "$air $road"
}

private fun kilometres(meters: Double): Long = maxOf(1L, Math.round(meters / METERS_PER_KM))

// "45 min", "2 h", "1 h 35 min": uguale in italiano e in inglese.
private fun travelTime(seconds: Double): String {
    val minutes = maxOf(1L, Math.round(seconds / SECONDS_PER_MINUTE))
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        hours == 0L -> "$rest min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

private const val METERS_PER_KM = 1000.0
private const val SECONDS_PER_MINUTE = 60.0
private const val MINUTES_PER_HOUR = 60L

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

/** Sezione di contesto per il prompt, dalla guida della nazione o da quella di una città. */
internal data class AssistantSection(
    val body: String,
    val category: GuideCategory,
    val citation: String,
)

/**
 * True per le sezioni che fanno mostrare il banner "Verifica sempre sulla fonte ufficiale". Con SICUREZZA il banner
 * compare nell'84% delle domande sulla sicurezza invece del 18% (88% invece del 25% in inglese), e nel 16% delle
 * altre domande invece del 10% (20% invece del 7% in inglese): misurato sulle guide pubblicate di cinque paesi
 * con la ricerca di tools/data-pipeline/scripts/eval_retrieval.py, il 2026-10-09.
 */
internal fun AssistantSection.isRegulatedTopic(): Boolean =
    category == GuideCategory.DOGANE || category == GuideCategory.SALUTE || category == GuideCategory.SICUREZZA

// CC BY-SA 4.0 (e la OGL-Canada per travel.gc.ca, la OGL v3.0 per gov.uk) impone di indicare la fonte: titolo + link alla pagina originale, non
// solo il nome del sito.
private fun GuideSection.toAssistantSection(language: String): AssistantSection {
    val site = when (guideSourceSiteOf(sourceUrl)) {
        GuideSourceSite.WIKIVOYAGE -> "Wikivoyage"
        GuideSourceSite.WIKIPEDIA -> "Wikipedia"
        GuideSourceSite.TRAVEL_GC_CA -> if (language == "en") "Government of Canada (travel.gc.ca)" else "Governo del Canada (travel.gc.ca)"
        GuideSourceSite.FCDO -> if (language == "en") "UK government (gov.uk)" else "Governo del Regno Unito (gov.uk)"
    }
    val url = guideSourceUrlWithoutAudience(sourceUrl)
    return AssistantSection(
        body = body,
        category = category,
        citation = if (language == "en") "Source: $site, section $title — $url" else "Fonte: $site, sezione $title — $url",
    )
}

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
 * Unisce i candidati della guida della nazione e delle guide delle città in un'unica classifica BM25 con le statistiche
 * delle due tabelle sommate (FtsCorpusStats: con quelle di ognuna una parola qualunque varrebbe molto di più nelle
 * poche sezioni della nazione che nelle centinaia delle città), tenendo solo le [limit] sezioni migliori in totale.
 * Con [countryFirst] true (la domanda non nomina una città) le sezioni della nazione vengono prima di quelle delle città,
 * con false (la nomina) dopo: senza il nome della città nella query (buildFtsQuery) le sezioni della nazione
 * scavalcherebbero le sue con le parole generiche della domanda ("treno", "musei"); con null conta solo il punteggio.
 * Senza [historyOrClimate] (isHistoryOrClimateQuestion) Storia e Clima valgono WIKIPEDIA_OFF_TOPIC_WEIGHT.
 * La ricerca (con buildFtsQuery, namedCities, focusStems e selectContext) e' replicata in
 * tools/data-pipeline/scripts/eval_retrieval.py, che la misura su dati pubblicati: va cambiata insieme.
 */
internal fun rankSections(
    guideMatches: List<Pair<GuideSection, FtsMatchInfo>>,
    cityMatches: List<Pair<CitySection, FtsMatchInfo>>,
    limit: Int,
    countryFirst: Boolean? = null,
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
        .sortedWith(compareBy<RankedSection> { countryFirst != null && it.fromCountry != countryFirst }.thenByDescending { it.score })
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

// Prima del nome di una citta' scritto in minuscolo ("a forli", "to porto"). Senza, una parola comune uguale al nome
// di una citta' di una sola parola non e' la citta': "mobile data" (Mobile), "mi sento male" (Male'), "split the bill".
// In inglese niente "a": e' l'articolo ("a nice beach").
private val placePrepositions = mapOf(
    "it" to setOf("a", "ad", "di", "da", "in", "per", "verso", "vicino"),
    "en" to setOf("in", "to", "from", "near", "around", "at", "of", "visit", "visiting"),
)

/**
 * Le citta' di [cities] nominate in [question] come parole intere, dal nome piu' lungo; un nome compreso in uno piu'
 * lungo gia' trovato non conta ("Porto" in "Porto Santo"). Un nome di una sola parola conta solo con l'iniziale
 * maiuscola o dopo una preposizione di luogo della lingua [language].
 */
internal fun namedCities(question: String, cities: List<String>, language: String = "it"): List<String> {
    val words = normalizedWords(question)
    val tokens = question.split(nonWord).filter { it.isNotEmpty() }
    val foldedTokens = tokens.map(::folded)
    val prepositions = placePrepositions[language] ?: placePrepositions.getValue("en")
    fun namedAsPlace(name: String) = foldedTokens.indices.any { i ->
        foldedTokens[i] == name && (tokens[i].first().isUpperCase() || foldedTokens.getOrNull(i - 1) in prepositions)
    }
    val found = cities.filter { city ->
        val name = normalizedWords(spokenCityName(city)).trim()
        spokenCityName(city).length >= MIN_CITY_NAME_CHARS && " $name " in words && (' ' in name || namedAsPlace(name))
    }.sortedByDescending { spokenCityName(it).length }
    return found.fold(emptyList()) { kept, city ->
        val name = normalizedWords(spokenCityName(city))
        if (kept.any { name in normalizedWords(spokenCityName(it)) }) kept else kept + city
    }
}

private const val STEM_CHARS = 5

/**
 * Radici (prime 5 lettere, senza accenti) delle parole della domanda in [ftsQuery], senza quelle della citta'
 * nominata [city]: scelgono i paragrafi di una sezione troppo lunga per il contesto ("estate" trova anche "estati").
 */
internal fun focusStems(ftsQuery: String, city: String?): Set<String> {
    val cityStems = city?.let { normalizedWords(spokenCityName(it)).split(' ').filter { w -> w.length >= 4 }.map { w -> w.take(STEM_CHARS) } }.orEmpty()
    return ftsQuery.split(" OR ").filter { it.isNotBlank() }.map { folded(it.removeSuffix("*")).take(STEM_CHARS) }.toSet() - cityStems.toSet()
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
 * rumore che compare in ogni sezione e confonde il ranking per rilevanza. Per la stessa ragione si
 * scartano le parole della citta' nominata [city] (le sezioni candidate sono gia' solo sue: rara
 * nella tabella, peserebbe piu' della domanda), salvo che restino solo quelle, e le parole
 * interrogative o di servizio (questionStopwords). Le parole di almeno 5 lettere si cercano per
 * prefisso (ftsPrefix): l'indice non ha radici, e "mangia" deve trovare "Dove mangiare".
 */
internal fun buildFtsQuery(question: String, regionId: String, city: String? = null): String {
    val regionNameTokens = regionId.split(Regex("[^\\p{L}\\p{N}]+")).map { it.lowercase() }.toSet()
    val words = question
        // Parole divise come le divide l'indice (unicode61): "dell'isola" e' "dell" e "isola", non "dellisola".
        .split(nonWord)
        // minuscolo: FTS riconosce AND/OR/NOT/NEAR come operatori solo in maiuscolo, e il confronto dei
        // termini ignora comunque maiuscole e minuscole
        .map { it.lowercase() }
        .filter { it.length >= 4 && it !in regionNameTokens && folded(it) !in questionStopwords }
    val cityWords = city?.let { normalizedWords(spokenCityName(it)).split(' ').toSet() }.orEmpty()
    return words.filter { folded(it) !in cityWords }.ifEmpty { words }
        .map(::ftsPrefix)
        .distinct()
        .joinToString(" OR ")
}

/**
 * [word] come prefisso FTS ("piatti" -> "piatt*", che trova anche "piatto"): le prime 5 lettere, 6 dalle parole di
 * 8 ("passaporto" -> "passap*", non "passa*" che trova "passare"); intera sotto le 5 ("roma" non trova "romantico").
 */
private fun ftsPrefix(word: String): String = when {
    word.length < STEM_CHARS -> word
    word.length < LONG_WORD_CHARS -> word.take(STEM_CHARS) + "*"
    else -> word.take(STEM_CHARS + 1) + "*"
}

private const val LONG_WORD_CHARS = 8

// Parole della domanda (senza accenti) che compaiono in quasi ogni sezione e non dicono di cosa si parla. "cosa",
// "dove" e "come" restano: sono nei titoli delle sezioni ("Cosa vedere", "Dove mangiare", "Come arrivare").
private val questionStopwords = setOf(
    "quale", "quali", "quanto", "quanta", "quanti", "quante", "quando", "perche", "sono", "della", "delle", "dello",
    "degli", "dell", "nella", "nelle", "nello", "negli", "nell", "alla", "alle", "allo", "agli", "dalla", "dalle", "dallo",
    "dagli", "sulla", "sulle", "sullo", "sugli", "questo", "questa", "questi", "queste", "quello", "quella", "quelli",
    "quelle", "anche", "molto", "molti", "molte", "posso", "puoi", "possono", "devo", "deve", "devono", "serve", "servono",
    "essere", "fatto", "avere", "hanno", "ogni", "tutto", "tutti", "tutte", "altro", "altri", "loro", "dire", "cosi",
    "ancora", "oppure", "mentre",
    "what", "which", "where", "when", "does", "there", "with", "from", "that", "this", "have", "should", "about", "much",
    "many", "could", "would", "your", "some", "into", "they", "them", "were", "been", "will", "also", "very", "need",
)

// maxChars di default ~2000 = ~500 token (stima 4 caratteri/token) per il chunk RAG.
internal fun truncateContext(context: String, maxChars: Int = 2_000): String =
    if (context.length <= maxChars) context else context.take(maxChars)
