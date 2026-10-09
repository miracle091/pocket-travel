package com.pockettravel.pipeline

import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Consigli di viaggio del Governo del Canada (travel.gc.ca) come sezioni della guida inglese, e quindi del contesto
 * dell'assistente. Fonte: il JSON open data data.international.gc.ca/travel-voyage/cta-cap-<iso2>.json (Open
 * Government Licence – Canada 2.0: copia, modifica e traduzione permesse, con l'attribuzione "Contains information
 * licensed under the Open Government Licence – Canada."; i termini del sito canada.ca non valgono per i dati aperti).
 * Il testo e' scritto per i canadesi solo in alcune parti, misurate il 2026-10-07 sulle 237 pagine: restano fuori
 * ingresso e uscita (passaporti canadesi), uffici consolari, i sottotitoli di excludedAdviceHeadings e, nel resto, le
 * frasi che nominano il Canada. Fuori anche i vaccini: li da' gia' la scheda Vaccinazioni, dalla stessa fonte.
 *
 * Per i territori senza pagina su travel.gc.ca (Palestina, Pitcairn, Wallis e Futuna; misura del 2026-10-08) le stesse
 * quattro sezioni vengono dai consigli del Foreign, Commonwealth & Development Office del Regno Unito: GOV.UK Content API
 * www.gov.uk/api/content/foreign-travel-advice/<slug> (Open Government Licence v3.0: copia, adattamento, traduzione e uso
 * commerciale permessi, con l'attribuzione "Contains public sector information licensed under the Open Government Licence
 * v3.0." e senza far pensare a un avallo). Della pagina restano Safety and security, Regional risks e Health, senza le
 * frasi sui britannici, il Regno Unito o l'FCDO; fuori ingresso, assistenza consolare e assicurazioni, tutte per i britannici.
 */

const val TRAVEL_ADVICE_HOST = "travel.gc.ca"
const val FCDO_ADVICE_PREFIX = "https://www.gov.uk/foreign-travel-advice/"

private data class AdvicePart(val key: String, val category: String, val title: String)

// Livello di rischio e avvisi regionali ("advisories") in testa alla sicurezza.
private val adviceParts = listOf(
    AdvicePart("security", "SICUREZZA", "Safety and security"),
    AdvicePart("laws-culture", "USI_COSTUMI", "Laws and culture"),
    AdvicePart("disasters-climate", "SICUREZZA", "Natural disasters and climate"),
    AdvicePart("health", "SALUTE", "Health"),
)

// Sottotitoli per i canadesi (34-74% di frasi sul Canada) o sui vaccini; con loro i sottotitoli di livello inferiore.
internal val excludedAdviceHeadings = setOf(
    "useful links", "dual citizenship", "international child abduction", "transfer to a canadian prison",
    "relevant travel health notices", "routine vaccines", "pre-travel vaccines and medications",
)

private fun isExcludedHeading(heading: String): Boolean =
    heading.lowercase().let { it in excludedAdviceHeadings || it.startsWith("keep in mind") }

// Frasi sul Canada o in cui parla il governo canadese ("how we can help", "our ability to offer you consular services").
private val canadianRegex = Regex("""canad|global affairs|\bwe\b|\bour\b""", RegexOption.IGNORE_CASE)
// Fine frase: punto, ! o ? e una maiuscola, una cifra o una virgoletta dopo lo spazio, ma non dopo un'abbreviazione:
// dividendo anche li', da "illegal under U.S. federal laws ... across the Canada-U.S. border." il filtro toglierebbe solo
// i pezzi col Canada e resterebbe "illegal under U.S. border.".
private val sentenceEndRegex =
    Regex("""(?<=[.!?])(?<!\b(?:[A-Z]\.[A-Z]|St|Mt|Ft|Dr|Mr|Mrs|Ms|No|vs|e\.g|i\.e)\.)\s+(?=[\p{Lu}\d"“‘'(])""")
private val htmlOptions = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
private val headingTagRegex = Regex("""<h([1-6])[^>]*>(.*?)</h\1>""", htmlOptions)
// Paragrafo fatto solo di un link a un'altra pagina di travel.gc.ca ("Drugs, alcohol and travel"): fuori dal sito e' un titolo orfano.
// Il testo del link non contiene altri link o paragrafi: altrimenti da "<p><a>Zika virus</a> is a risk..." la corrispondenza
// arriverebbe al primo </a></p> dei paragrafi dopo, e li toglierebbe tutti.
private val linkOnlyParagraphRegex = Regex("""<p[^>]*>\s*<a\b[^>]*>(?:(?!</?(?:a|p)\b).)*</a>\s*</p>""", htmlOptions)
// "Learn more:" con i link che lo seguono, in fondo alle schede della salute: fuori dal sito sono titoli orfani.
private val learnMoreRegex = Regex(
    """<strong>\s*Learn more\s*:(?:\s|&nbsp;|<br\s*/?>)*</strong>(?:\s|&nbsp;|<br\s*/?>|</p>\s*<p[^>]*>)*""" +
        """(?:<a\b[^>]*>(?:(?!</?a\b).)*</a>(?:\s|&nbsp;|<br\s*/?>)*)*""",
    htmlOptions,
)
// Nome della malattia di una scheda a scomparsa della salute ("<details><summary>Dengue</summary>"): un sottotitolo.
private val summaryTagRegex = Regex("""<summary[^>]*>(.*?)</summary>""", htmlOptions)
private const val SUMMARY_LEVEL = 5
private val listItemRegex = Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE)
private val blockTagRegex = Regex("""</?(p|div|ul|ol|li|br|table|tr|td|th|section|details|summary|dl|dt|dd)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val anyTagRegex = Regex("""<[^>]+>""")
private val spacesRegex = Regex("""\s+""")
private val friendlyDateRegex = Regex("""^[A-Z][a-z]+ \d{1,2}, \d{4}""")

// Segni di uso privato: non compaiono nel testo, marcano le righe di titolo e di elenco fino alla resa finale.
private const val HEADING_MARK = ''
private const val ITEM_MARK = ''

private sealed interface Block {
    val text: String
}
private data class Heading(override val text: String) : Block
private data class Item(override val text: String) : Block
private data class Paragraph(override val text: String) : Block

// Frasi sul Regno Unito o in cui parla l'FCDO ("British nationals", "the NHS", "we cannot give advice").
private val britishRegex = Regex("""\bUK\b|british|united kingdom|fcdo|\bnhs\b|ghic|ehic|gov\.uk|\bwe\b|\bour\b""", RegexOption.IGNORE_CASE)

// Sottotitoli FCDO della salute con soli link (vaccini, salute mentale) o per chi va a vivere nel paese.
internal val excludedFcdoHeadings = setOf("vaccine recommendations and health risks", "travel and mental health", "healthcare for residents")

// Sottotitoli della salute di travel.gc.ca con testo uguale per quasi tutti i paesi (prevenzione per cibo e acqua, punture,
// animali, infezioni, turismo medico; misura del 2026-10-08: il 73% della salute e' fatto di righe identiche in almeno un
// paese su quattro). Nella salute compatta resta solo cio' che sta nelle schede delle malattie sotto di loro.
private val genericHealthHeadings = setOf(
    "safe food and water precautions", "tick and insect bite prevention", "animal precautions", "person-to-person infections",
    "medical tourism",
)

// Le poche frasi utili in cui parla il governo (misura del 2026-10-08: 6 su circa 475 tolte per "we"/"our"; le altre sono
// sull'assistenza consolare o due frasi fisse su arresti e compagnie aeree), riscritte in forma impersonale prima del
// filtro, che toglie il resto.
private val impersonalRewrites = listOf(
    Regex("""\bWe advise you not to\b""") to "Do not",
    Regex("""\bWe also strongly advise that you\b""") to "You should also",
    Regex("""\bwhere we advise (against|to)\b""") to "where the advice is $1",
    Regex("""\bagainst our advice\b""") to "against the advice",
)

// Frase di una scheda di malattia che dice se e quanto la malattia c'e' nel paese.
private val diseaseRiskRegex = Regex("""\brisk\b|occur|present in|reported|outbreak""", RegexOption.IGNORE_CASE)

/** Le frasi di [text] che non corrispondono a [foreign]. */
private fun withoutForeignSentences(text: String, foreign: Regex): String =
    text.split(sentenceEndRegex).filterNot { foreign.containsMatchIn(it) }.joinToString(" ").trim()

/**
 * Il corpo HTML di una parte dei consigli nel formato delle sezioni della guida (come cleanBody): sottotitoli
 * "▸ Titolo", elenchi "• voce", paragrafi separati da una riga vuota. Senza i sottotitoli [excluded] (con quelli di livello
 * inferiore), senza le frasi e le voci che corrispondono a [foreign] (di norma quelle sul Canada), senza i sottotitoli
 * rimasti vuoti. [compactHealth] (salute di travel.gc.ca): fuori il testo dei sottotitoli di genericHealthHeadings e, delle
 * schede delle malattie, tutto tranne il nome e la prima frase ("There is a risk of typhoid fever in this destination, but
 * the risk is low for most travellers."), che il resto spiega la malattia allo stesso modo in ogni paese.
 */
internal fun adviceHtmlToText(
    html: String,
    foreign: Regex = canadianRegex,
    excluded: (String) -> Boolean = ::isExcludedHeading,
    compactHealth: Boolean = false,
): String {
    val marked = html
        .replace(linkOnlyParagraphRegex, "")
        .replace(learnMoreRegex, "")
        .replace(headingTagRegex) { "\n$HEADING_MARK${it.groupValues[1]}${it.groupValues[2]}\n" }
        .replace(summaryTagRegex) { "\n$HEADING_MARK$SUMMARY_LEVEL${it.groupValues[1]}\n" }
        .replace(listItemRegex, "\n$ITEM_MARK")
        .replace(blockTagRegex, "\n")
        .replace(anyTagRegex, "")
    val blocks = mutableListOf<Block>()
    var skipLevel = 0 // 0: niente da saltare; altrimenti il livello del titolo escluso
    var pendingHeading: String? = null
    var nextIsItem = false
    var genericBlock = false // salute compatta: sotto un sottotitolo di genericHealthHeadings
    var diseaseBlock = false // salute compatta: dentro una scheda di malattia
    var diseaseKept = false // salute compatta: frase della scheda gia' presa
    var diseaseFallback: Block? = null // salute compatta: prima frase della scheda, se nessuna parla di rischio
    fun emit(block: Block) {
        pendingHeading?.let { blocks += Heading(it) }
        pendingHeading = null
        blocks += block
    }
    fun closeDisease() {
        if (diseaseBlock && !diseaseKept) diseaseFallback?.let(::emit)
        diseaseFallback = null
    }
    for (raw in marked.lines()) {
        // \s di Java non comprende lo spazio non separabile (&nbsp;, frequente nei testi di travel.gc.ca)
        val line = decodeHtmlEntities(raw).replace(' ', ' ').replace(spacesRegex, " ").trim()
        if (line.isEmpty()) continue
        if (line[0] == HEADING_MARK) {
            closeDisease()
            val level = line[1].digitToInt()
            val heading = line.drop(2).trim()
            if (skipLevel != 0 && level <= skipLevel) skipLevel = 0
            if (skipLevel == 0 && excluded(heading)) skipLevel = level
            diseaseBlock = level == SUMMARY_LEVEL
            diseaseKept = false
            if (!diseaseBlock) genericBlock = compactHealth && heading.lowercase() in genericHealthHeadings
            pendingHeading = heading.takeIf { skipLevel == 0 && it.isNotEmpty() && !(genericBlock && !diseaseBlock) }
            nextIsItem = false
            continue
        }
        val item = nextIsItem || line[0] == ITEM_MARK
        val content = impersonalRewrites.fold(line.trimStart(ITEM_MARK).trim()) { text, (regex, replacement) -> text.replace(regex, replacement) }
        if (content.isEmpty()) {
            nextIsItem = true // "<li><p>testo</p>": la voce continua sulla riga dopo
            continue
        }
        nextIsItem = false
        if (skipLevel != 0) continue
        val whole = if (item) content.takeUnless { foreign.containsMatchIn(it) }.orEmpty() else withoutForeignSentences(content, foreign)
        if (whole.isEmpty()) continue
        if (compactHealth && diseaseBlock) {
            // La frase sul paese ("For most travellers the risk of tuberculosis is low.") puo' venire dopo una definizione
            // uguale per tutti ("Tuberculosis is an infection...") o dopo un'etichetta senza punto ("Risk").
            if (diseaseKept) continue
            val sentences = whole.split(sentenceEndRegex).filter { it.last() in ".!?" }
            val riskSentence = sentences.firstOrNull { diseaseRiskRegex.containsMatchIn(it) }
            if (riskSentence != null) {
                emit(if (item) Item(riskSentence) else Paragraph(riskSentence))
                diseaseKept = true
            } else if (diseaseFallback == null && sentences.isNotEmpty()) {
                diseaseFallback = if (item) Item(sentences.first()) else Paragraph(sentences.first())
            }
            continue
        }
        if (compactHealth && genericBlock) continue
        emit(if (item) Item(whole) else Paragraph(whole))
    }
    closeDisease()
    return buildString {
        var previous: Block? = null
        for (block in blocks) {
            val separator = when {
                previous == null -> ""
                block is Heading -> "\n\n"
                previous is Heading || block is Item -> "\n" // l'elenco segue il paragrafo che lo introduce
                else -> "\n\n"
            }
            append(separator).append(
                when (block) {
                    is Heading -> "▸ ${block.text}"
                    is Item -> "• ${block.text}"
                    is Paragraph -> block.text
                },
            )
            previous = block
        }
    }
}

/**
 * Le sezioni della guida inglese da un cta-cap-<iso2>.json di travel.gc.ca: sicurezza (con il livello di rischio e gli
 * avvisi regionali in testa), leggi e cultura, catastrofi naturali e clima, salute; ognuna con la data dell'ultimo
 * aggiornamento in fondo e l'url della pagina del paese. Vuota se il JSON non si legge o non ha la parte inglese.
 */
fun travelAdviceSections(json: String): List<GuideSectionRow> {
    val data = runCatching { JSONObject(json).getJSONObject("data") }.getOrNull() ?: return emptyList()
    val eng = data.optJSONObject("eng") ?: return emptyList()
    val iso = data.optString("country-iso").lowercase().takeIf { it.length == 2 } ?: return emptyList()
    val url = "https://$TRAVEL_ADVICE_HOST/destinations/$iso"
    val updated = friendlyDateRegex.find(eng.optString("friendly-date"))?.value
    val footer = updated?.let { "\n\nLast updated by the Government of Canada: $it." }.orEmpty()
    return adviceParts.mapNotNull { part ->
        val bodies = listOfNotNull(
            adviceHtmlToText(eng.optString("advisories")).takeIf { part.key == "security" },
            adviceHtmlToText(eng.optString(part.key), compactHealth = part.key == "health"),
        ).filter { it.isNotBlank() }
        bodies.takeIf { it.isNotEmpty() }?.let {
            GuideSectionRow(part.category, "${part.title} (Government of Canada)", it.joinToString("\n\n") + footer, url)
        }
    }
}

private val h2StartRegex = Regex("""(?=<h2\b)""", RegexOption.IGNORE_CASE)
private val h2TextRegex = Regex("""^<h2[^>]*>(.*?)</h2>""", htmlOptions)
// Riquadro ripetuto in testa a ogni parte ("This travel advice covers Israel and Palestine."): una volta per sezione e' troppo.
private val fcdoExampleRegex = Regex("""<div class="example">(?:(?!</?div\b).)*</div>""", htmlOptions)
private val fcdoDateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

// Zone da evitare, in Warnings and insurance ("Areas where FCDO advises against travel", poi una h3 per zona): le uniche
// parti di quella pagina per chi non e' britannico. "FCDO advises against" diventa un consiglio diretto, perche' il
// filtro toglie le frasi che nominano l'FCDO.
private val fcdoAbbrRegex = Regex("""<abbr\b[^>]*>(.*?)</abbr>""", htmlOptions)
private val fcdoAreaRewrites = listOf(
    Regex("""Areas where FCDO advises against all but essential travel""") to "Areas to avoid except for essential travel",
    Regex("""Areas where FCDO advises against travel""") to "Areas to avoid",
    Regex("""FCDO advises against all but essential travel""") to "Avoid all but essential travel",
    Regex("""FCDO advises against all travel""") to "Avoid all travel",
)

// Lo spazio dopo "FCDO" e' a volte non separabile (Palestina, 2026-10-08): si normalizza prima di riscrivere.
private fun fcdoAreasToAvoid(warnings: String): String =
    warnings.replace(fcdoAbbrRegex) { it.groupValues[1] }.replace(' ', ' ').replace("&nbsp;", " ").split(h2StartRegex)
        .filter { h2TextRegex.find(it)?.groupValues?.get(1)?.trim()?.startsWith("Areas where FCDO advises against") == true }
        .joinToString("")
        .let { html -> fcdoAreaRewrites.fold(html) { text, (regex, replacement) -> text.replace(regex, replacement) } }

/** Paese che una pagina FCDO copre insieme a quello della regione, e i sottotitoli (h2/h3, minuscoli) solo suoi. */
private data class SharedCountry(val iso: String, val name: String, val region: String, val headings: Set<String>)

// La pagina FCDO della Palestina e' "Israel and Palestine" (decisione utente 2026-10-08): le parti solo israeliane vanno in
// una sezione a parte, mostrata solo a chi ha la nazionalita' israeliana; gli altri vedono un rimando alla guida di Israele.
private val fcdoSharedCountries = mapOf(
    "/foreign-travel-advice/palestine" to SharedCountry(
        "IL",
        "Israel",
        "Palestine",
        setOf(
            "conflict between lebanese hizballah and israel", "conflict between iran and israel",
            "conflict between the houthis (in yemen) and israel", "northern israel and occupied golan heights", "tel aviv",
            "israel-lebanon", "israel-syria border", "occupied golan heights", "border with egypt",
        ),
    ),
)

// Suffisso del link delle sezioni per una sola nazionalita' (o per tutte tranne una): l'app lo legge e lo toglie.
private const val FOR_NATIONALITY = "#for-nationality="
private const val NOT_FOR_NATIONALITY = "#not-for-nationality="

private val h23StartRegex = Regex("""(?=<h[23]\b)""", RegexOption.IGNORE_CASE)
private val h23TextRegex = Regex("""^<h([23])[^>]*>(.*?)</h\1>""", htmlOptions)

/** [html] diviso in (resto, parti sotto un sottotitolo h2/h3 di [headings], con i loro sottotitoli di livello inferiore). */
private fun splitOutHeadings(html: String, headings: Set<String>): Pair<String, String> {
    val kept = StringBuilder()
    val out = StringBuilder()
    var outLevel = 0
    for (chunk in html.split(h23StartRegex)) {
        val match = h23TextRegex.find(chunk)
        val level = match?.groupValues?.get(1)?.toInt() ?: 0
        val heading = match?.groupValues?.get(2)?.replace(anyTagRegex, "")?.trim()?.lowercase()
        if (outLevel != 0 && level != 0 && level <= outLevel) outLevel = 0
        if (outLevel == 0 && heading in headings) outLevel = level
        (if (outLevel != 0) out else kept).append(chunk)
    }
    return kept.toString() to out.toString()
}

// Livelli FCDO (details.alert_status), nell'ordine in cui si scrivono in testa alla sicurezza.
private val fcdoAlerts = listOf(
    "avoid_all_travel_to_whole_country" to "against all travel to the whole country",
    "avoid_all_but_essential_travel_to_whole_country" to "against all but essential travel to the whole country",
    "avoid_all_travel_to_parts" to "against all travel to parts of the country",
    "avoid_all_but_essential_travel_to_parts" to "against all but essential travel to parts of the country",
)

private fun fcdoAlertStatus(root: JSONObject): List<String> =
    root.optJSONObject("details")?.optJSONArray("alert_status")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

/** True se [json] e' una pagina della GOV.UK Content API (FCDO) e non un JSON di travel.gc.ca. */
private fun isFcdoJson(json: String): Boolean = runCatching { JSONObject(json).has("details") }.getOrDefault(false)

/**
 * Le sezioni della guida inglese da una pagina FCDO della GOV.UK Content API (foreign-travel-advice/<slug>), con le
 * stesse quattro parti di travel.gc.ca: Safety and security divisa per sottotitolo h2 (Laws and cultural differences in
 * leggi e cultura, Extreme weather and natural disasters in catastrofi e clima, il resto con Regional risks, il livello
 * di alert_status e le zone da evitare di Warnings and insurance in sicurezza), Health in salute. Vuota se il JSON non si
 * legge.
 */
fun fcdoAdviceSections(json: String): List<GuideSectionRow> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val parts = root.optJSONObject("details")?.optJSONArray("parts") ?: return emptyList()
    val path = root.optString("base_path").takeIf { it.startsWith("/foreign-travel-advice/") } ?: return emptyList()
    val bodies = (0 until parts.length()).mapNotNull(parts::optJSONObject)
        .associate { it.optString("title") to it.optString("body").replace(fcdoExampleRegex, "") }
    val safety = bodies["Safety and security"].orEmpty().split(h2StartRegex).groupBy { chunk ->
        when (h2TextRegex.find(chunk)?.groupValues?.get(1)?.trim()?.lowercase()) {
            "laws and cultural differences" -> "laws-culture"
            "extreme weather and natural disasters" -> "disasters-climate"
            else -> "security"
        }
    }.mapValues { (_, chunks) -> chunks.joinToString("") }
    val alerts = fcdoAlertStatus(root).toSet()
    val level = fcdoAlerts.filter { it.first in alerts }.map { it.second }
        .takeIf { it.isNotEmpty() }?.let { "▸ Travel advice level\nThe UK government advises ${it.joinToString(" and ")}." }
    val shared = fcdoSharedCountries[path]
    val (security, sharedHtml) = (fcdoAreasToAvoid(bodies["Warnings and insurance"].orEmpty()) + safety["security"].orEmpty() + bodies["Regional risks"].orEmpty())
        .let { if (shared != null) splitOutHeadings(it, shared.headings) else it to "" }
    val html = mapOf(
        "security" to security,
        "laws-culture" to safety["laws-culture"].orEmpty(),
        "disasters-climate" to safety["disasters-climate"].orEmpty(),
        "health" to bodies["Health"].orEmpty(),
    )
    val updated = runCatching { OffsetDateTime.parse(root.optString("public_updated_at")).format(fcdoDateFormat) }.getOrNull()
    val footer = updated?.let { "\n\nLast updated by the UK government: $it." }.orEmpty()
    val excluded = { heading: String -> heading.lowercase() in excludedFcdoHeadings }
    val safetyUrl = "https://www.gov.uk$path/safety-and-security"
    val sharedSections = shared?.let { country ->
        val text = adviceHtmlToText(sharedHtml, britishRegex, excluded).takeIf { it.isNotBlank() } ?: return@let emptyList()
        listOf(
            GuideSectionRow(
                "SICUREZZA",
                "${country.name}: areas and risks outside ${country.region} (UK government)",
                text + footer,
                "$safetyUrl$FOR_NATIONALITY${country.iso}",
            ),
            GuideSectionRow(
                "SICUREZZA",
                "${country.name} (UK government)",
                "This advice also covers ${country.name}. The areas of ${country.name} to avoid and the risks in the rest of " +
                    "${country.name} are in the ${country.name} guide." + footer,
                "$safetyUrl$NOT_FOR_NATIONALITY${country.iso}",
            ),
        )
    }.orEmpty()
    val sections = adviceParts.mapNotNull { part ->
        val bodies = listOfNotNull(
            level.takeIf { part.key == "security" },
            adviceHtmlToText(html.getValue(part.key), britishRegex, excluded),
        ).filter { it.isNotBlank() }
        val slug = if (part.key == "health") "health" else "safety-and-security"
        bodies.takeIf { it.isNotEmpty() }?.let {
            GuideSectionRow(part.category, "${part.title} (UK government)", it.joinToString("\n\n") + footer, "https://www.gov.uk$path/$slug")
        }
    }
    // Le sezioni dell'altro paese subito dopo la sicurezza della regione.
    return sections.take(1) + sharedSections + sections.drop(1)
}

/** True per le sezioni che vengono da travel.gc.ca o dall'FCDO (url della pagina del paese). */
fun isTravelAdvice(section: GuideSectionRow): Boolean =
    section.sourceUrl?.let { "://$TRAVEL_ADVICE_HOST/" in it || it.startsWith(FCDO_ADVICE_PREFIX) } == true

/**
 * Il testo di travel.gc.ca cambia per quasi tutti i paesi ogni due settimane, il livello di rischio di rado (misura del
 * 2026-10-07: nessun cambio in 228 paesi in 2-4 settimane), e il job delle guide gira ogni giorno: i soli consigli
 * cambiati pubblicano un nuovo guides-en.db (che tutti riscaricano) al piu' una volta ogni questi giorni, a meno che
 * cambi il livello di rischio o gli avvisi regionali di un paese (decisione utente 2026-10-07: una versione a settimana).
 */
const val TRAVEL_ADVICE_MAX_AGE_DAYS = 7L

/** Livello di rischio (0 normale ... 3 evitare ogni viaggio), avvisi regionali (0/1) e data di download dei consigli di una regione. */
data class TravelAdviceMeta(val advisoryState: Int, val regional: Int, val fetched: LocalDate)

data class RegionAdvice(val sections: List<GuideSectionRow>, val meta: TravelAdviceMeta?)

private fun adviceState(json: String, fetched: LocalDate): TravelAdviceMeta? = runCatching {
    val data = JSONObject(json).getJSONObject("data")
    TravelAdviceMeta(data.getInt("advisory-state"), data.optInt("has-regional-advisory"), fetched)
}.getOrNull()

// Livello FCDO sulla scala di travel.gc.ca: 3 nessun viaggio nel paese intero, 2 solo viaggi essenziali, 0 altrimenti;
// avvisi regionali se l'avviso riguarda solo parti del paese.
private fun fcdoAdviceState(json: String, fetched: LocalDate): TravelAdviceMeta? = runCatching {
    val alerts = fcdoAlertStatus(JSONObject(json))
    val state = when {
        "avoid_all_travel_to_whole_country" in alerts -> 3
        "avoid_all_but_essential_travel_to_whole_country" in alerts -> 2
        else -> 0
    }
    TravelAdviceMeta(state, if (alerts.any { it.endsWith("_to_parts") }) 1 else 0, fetched)
}.getOrNull()

/**
 * I consigli di viaggio per regione da [tsv] ("regionId<TAB>file JSON" di travel.gc.ca o dell'FCDO, riconosciuto dal
 * contenuto; vuoto o assente se non scaricato in questa run): dal JSON, con il livello di rischio e la data [today]; o,
 * se manca o non si legge, quelle della regione nel guides.db pubblicato ([publishedSections], [publishedMeta], con la loro data), cosi' un errore di rete non fa
 * sparire i consigli. Le regioni dello stesso paese (stati USA, regioni francesi) hanno le stesse.
 */
fun readTravelAdvice(
    tsv: File,
    publishedSections: (String) -> List<GuideSectionRow>,
    publishedMeta: Map<String, TravelAdviceMeta>,
    today: LocalDate,
): Map<String, RegionAdvice> =
    tsv.readLines().filter { it.isNotBlank() }.associate { line ->
        val regionId = line.substringBefore('\t')
        val json = line.substringAfter('\t', "").takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.length() > 0 }?.readText()
        val fcdo = json != null && isFcdoJson(json)
        val sections = json?.let { if (fcdo) fcdoAdviceSections(it) else travelAdviceSections(it) }.orEmpty()
        val meta = json?.let { if (fcdo) fcdoAdviceState(it, today) else adviceState(it, today) }
        regionId to if (sections.isNotEmpty() && meta != null) {
            RegionAdvice(sections, meta)
        } else {
            if (json != null) println("guide: $regionId, consigli di viaggio illeggibili, ricopio quelli pubblicati")
            RegionAdvice(publishedSections(regionId).filter(::isTravelAdvice), publishedMeta[regionId])
        }
    }

/** Le guide con le sezioni di travel.gc.ca di [advice] al posto di quelle che avevano (anche se ricopiate dal pubblicato). */
fun List<RegionGuide>.withTravelAdvice(advice: Map<String, RegionAdvice>): List<RegionGuide> = map { guide ->
    val regionAdvice = advice[guide.regionId] ?: return@map guide
    guide.copy(sections = guide.sections.filterNot(::isTravelAdvice) + regionAdvice.sections)
}

/** Tabella travel_advice_meta di guides.db (le app la ignorano): serve solo a keepPublishedGuides della run successiva. */
fun writeTravelAdviceMeta(advice: Map<String, RegionAdvice>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "travel_advice_meta",
        createTableSql = "CREATE TABLE travel_advice_meta (regionId TEXT NOT NULL, advisory_state INTEGER NOT NULL, " +
            "regional INTEGER NOT NULL, fetched TEXT NOT NULL)",
        insertSql = "INSERT INTO travel_advice_meta (regionId, advisory_state, regional, fetched) VALUES (?, ?, ?, ?)",
        rows = advice.mapNotNull { (regionId, regionAdvice) -> regionAdvice.meta?.let { regionId to it } },
    ) { insert, (regionId, meta) ->
        insert.setString(1, regionId)
        insert.setInt(2, meta.advisoryState)
        insert.setInt(3, meta.regional)
        insert.setString(4, meta.fetched.toString())
    }
}

/** travel_advice_meta di un guides.db per regione; vuota se la tabella manca (guides.db precedente o guida italiana). */
fun readTravelAdviceMeta(db: File): Map<String, TravelAdviceMeta> =
    readRows(db, "SELECT regionId, advisory_state, regional, fetched FROM travel_advice_meta").orEmpty().mapNotNull { row ->
        val fetched = row[3]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
        row[0]?.let { regionId -> regionId to TravelAdviceMeta(row[1]?.toIntOrNull() ?: 0, row[2]?.toIntOrNull() ?: 0, fetched) }
    }.toMap()

/** Le regioni di [fresh] con livello di rischio o avvisi regionali diversi da [published] (o assenti li'), con i due stati. */
fun travelAdviceRiskChanges(published: Map<String, TravelAdviceMeta>, fresh: Map<String, TravelAdviceMeta>): List<String> =
    fresh.mapNotNull { (regionId, meta) ->
        val old = published[regionId]
        if (old != null && old.advisoryState == meta.advisoryState && old.regional == meta.regional) {
            null
        } else {
            "$regionId: livello ${old?.advisoryState ?: "-"} -> ${meta.advisoryState}, avvisi regionali ${old?.regional ?: "-"} -> ${meta.regional}"
        }
    }

/**
 * True se i consigli pubblicati possono restare anche se il testo e' cambiato: scaricati (il piu' recente) da meno di
 * TRAVEL_ADVICE_MAX_AGE_DAYS giorni e nessun cambio di rischio. Senza travel_advice_meta pubblicata si considerano vecchi.
 */
fun keepPublishedTravelAdvice(outputDb: File, publishedDb: File, today: LocalDate): Boolean {
    val published = readTravelAdviceMeta(publishedDb)
    val newest = published.values.maxOfOrNull { it.fetched } ?: return false
    return newest.isAfter(today.minusDays(TRAVEL_ADVICE_MAX_AGE_DAYS)) &&
        travelAdviceRiskChanges(published, readTravelAdviceMeta(outputDb)).isEmpty()
}
