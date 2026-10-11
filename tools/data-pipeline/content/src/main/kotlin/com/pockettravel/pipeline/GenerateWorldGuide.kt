package com.pockettravel.pipeline

import java.io.File

/**
 * Pseudo-regione "mondo" di guides.db: le sezioni delle pagine tematiche di Wikivoyage (salute, documenti, mance,
 * truffe...) che l'assistente cerca insieme alla regione aperta, per le domande generali che le guide delle regioni non
 * coprono. Sono righe normali di guide_sections con le categorie esistenti: le app gia' installate le importano senza
 * cercarle, e sameGuidesContent le confronta come le altre. Non e' uno slug possibile in regions.sh (che non comincia
 * mai con "_"). La lista delle pagine e' WORLD_PAGES in build-guides.sh.
 */
const val WORLD_REGION_ID = "_world"

// Titoli che non rispondono a domande: rimandi, indici, note, elenchi per paese, attrazioni.
private val skippedWorldHeadings = setOf(
    "see also", "vedi anche", "articoli correlati", "contents", "note", "help page", "information by region", "informazioni per regione",
    "health-related attractions", "attrazioni legate alla salute",
)

private val redirectRegex = Regex("""^\s*#(REDIRECT|RINVIA)\b""", RegexOption.IGNORE_CASE)

/**
 * Sezioni della pagina tematica [page], tutte della categoria [category] e con il link [sourceUrl]: l'introduzione con il
 * titolo della pagina, poi una per titolo "==" con il titolo "<pagina>: <titolo>"; stessa pulizia delle guide (cleanBody).
 */
fun parseWorldPage(page: String, category: String, dump: String, sourceUrl: String): List<GuideSectionRow> {
    val sections = mutableListOf<GuideSectionRow>()
    var heading: String? = null
    val body = StringBuilder()

    fun flush() {
        if (heading?.lowercase() in skippedWorldHeadings) return
        val text = cleanBody(body.toString())
        if (text.isNotBlank()) sections += GuideSectionRow(category, heading?.let { "$page: $it" } ?: page, text, sourceUrl)
    }

    dump.lineSequence().forEach { line ->
        val match = headingRegex.find(line.trim())
        if (match != null) {
            flush()
            heading = match.groupValues[1]
            body.setLength(0)
        } else {
            body.appendLine(line)
        }
    }
    flush()
    return sections
}

/**
 * Guida del mondo da [tsv], una riga per pagina "categoria<TAB>pagina<TAB>dump.txt<TAB>sourceUrl". Una pagina con il dump
 * vuoto (non scaricata in questa run) o diventata un rinvio riprende le sue sezioni da [published], quelle del guides.db pubblicato. null senza
 * nessuna sezione.
 */
fun readWorldGuide(tsv: File, published: List<GuideSectionRow>): RegionGuide? {
    val sections = tsv.readLines().filter { it.isNotBlank() }.flatMap { line ->
        val (category, page, dumpPath, sourceUrl) = line.split('\t')
        val text = dumpPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.length() > 0 }?.readText()
        // action=raw non segue i rinvii: una pagina diventata rinvio va corretta in WORLD_PAGES, intanto resta quella pubblicata.
        val redirect = text != null && redirectRegex.containsMatchIn(text)
        if (redirect) println("::warning::pagina del mondo $page e' un rinvio, correggi WORLD_PAGES")
        if (text != null && !redirect) {
            parseWorldPage(page, category, text, sourceUrl)
        } else {
            println("guide: pagina del mondo $page non scaricata, ricopio le sezioni pubblicate")
            published.filter { it.sourceUrl == sourceUrl }
        }
    }
    return sections.firstOrNull()?.let { RegionGuide(WORLD_REGION_ID, it.sourceUrl.orEmpty(), sections) }
}
