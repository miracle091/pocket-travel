package com.pockettravel.pipeline

import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/*
 * Consigli di viaggio del Governo del Canada (travel.gc.ca) come sezioni della guida inglese, e quindi del contesto
 * dell'assistente. Fonte: il JSON open data data.international.gc.ca/travel-voyage/cta-cap-<iso2>.json (Open
 * Government Licence – Canada 2.0: copia, modifica e traduzione permesse, con l'attribuzione "Contains information
 * licensed under the Open Government Licence – Canada."; i termini del sito canada.ca non valgono per i dati aperti).
 * Il testo e' scritto per i canadesi solo in alcune parti, misurate il 2026-10-07 sulle 237 pagine: restano fuori
 * ingresso e uscita (passaporti canadesi), uffici consolari, i sottotitoli di excludedAdviceHeadings e, nel resto, le
 * frasi che nominano il Canada. Fuori anche i vaccini: li da' gia' la scheda Vaccinazioni, dalla stessa fonte.
 */

const val TRAVEL_ADVICE_HOST = "travel.gc.ca"

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
private val sentenceEndRegex = Regex("""(?<=[.!?])\s+""")
private val htmlOptions = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
private val headingTagRegex = Regex("""<h([1-6])[^>]*>(.*?)</h\1>""", htmlOptions)
// Paragrafo fatto solo di un link a un'altra pagina di travel.gc.ca ("Drugs, alcohol and travel"): fuori dal sito e' un titolo orfano.
private val linkOnlyParagraphRegex = Regex("""<p[^>]*>\s*<a\b[^>]*>.*?</a>\s*</p>""", htmlOptions)
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

/** Le frasi di [text] che non nominano il Canada. */
private fun withoutCanadianSentences(text: String): String =
    text.split(sentenceEndRegex).filterNot { canadianRegex.containsMatchIn(it) }.joinToString(" ").trim()

/**
 * Il corpo HTML di una parte di travel.gc.ca nel formato delle sezioni della guida (come cleanBody): sottotitoli
 * "▸ Titolo", elenchi "• voce", paragrafi separati da una riga vuota. Senza i sottotitoli esclusi (con quelli di livello
 * inferiore), senza le frasi e le voci che nominano il Canada, senza i sottotitoli rimasti vuoti.
 */
internal fun adviceHtmlToText(html: String): String {
    val marked = html
        .replace(linkOnlyParagraphRegex, "")
        .replace(headingTagRegex) { "\n$HEADING_MARK${it.groupValues[1]}${it.groupValues[2]}\n" }
        .replace(listItemRegex, "\n$ITEM_MARK")
        .replace(blockTagRegex, "\n")
        .replace(anyTagRegex, "")
    val blocks = mutableListOf<Block>()
    var skipLevel = 0 // 0: niente da saltare; altrimenti il livello del titolo escluso
    var pendingHeading: String? = null
    var nextIsItem = false
    for (raw in marked.lines()) {
        // \s di Java non comprende lo spazio non separabile (&nbsp;, frequente nei testi di travel.gc.ca)
        val line = decodeHtmlEntities(raw).replace(' ', ' ').replace(spacesRegex, " ").trim()
        if (line.isEmpty()) continue
        if (line[0] == HEADING_MARK) {
            val level = line[1].digitToInt()
            val heading = line.drop(2).trim()
            if (skipLevel != 0 && level <= skipLevel) skipLevel = 0
            if (skipLevel == 0 && isExcludedHeading(heading)) skipLevel = level
            pendingHeading = heading.takeIf { skipLevel == 0 && it.isNotEmpty() }
            nextIsItem = false
            continue
        }
        val item = nextIsItem || line[0] == ITEM_MARK
        val content = line.trimStart(ITEM_MARK).trim()
        if (content.isEmpty()) {
            nextIsItem = true // "<li><p>testo</p>": la voce continua sulla riga dopo
            continue
        }
        nextIsItem = false
        if (skipLevel != 0) continue
        val text = if (item) content.takeUnless { canadianRegex.containsMatchIn(it) }.orEmpty() else withoutCanadianSentences(content)
        if (text.isEmpty()) continue
        pendingHeading?.let { blocks += Heading(it) }
        pendingHeading = null
        blocks += if (item) Item(text) else Paragraph(text)
    }
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
            adviceHtmlToText(eng.optString(part.key)),
        ).filter { it.isNotBlank() }
        bodies.takeIf { it.isNotEmpty() }?.let {
            GuideSectionRow(part.category, "${part.title} (Government of Canada)", it.joinToString("\n\n") + footer, url)
        }
    }
}

/** True per le sezioni che vengono da travel.gc.ca (url della pagina del paese). */
fun isTravelAdvice(section: GuideSectionRow): Boolean = section.sourceUrl?.contains("://$TRAVEL_ADVICE_HOST/") == true

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

/**
 * Le sezioni di travel.gc.ca per regione da [tsv] ("regionId<TAB>cta-cap.json", file vuoto o assente se non scaricato
 * in questa run): dal JSON, con il livello di rischio e la data [today]; o, se manca o non si legge, quelle della regione
 * nel guides.db pubblicato ([publishedSections], [publishedMeta], con la loro data), cosi' un errore di rete non fa
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
        val sections = json?.let(::travelAdviceSections).orEmpty()
        val meta = json?.let { adviceState(it, today) }
        regionId to if (sections.isNotEmpty() && meta != null) {
            RegionAdvice(sections, meta)
        } else {
            if (json != null) println("guide: $regionId, consigli di travel.gc.ca illeggibili, ricopio quelli pubblicati")
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
