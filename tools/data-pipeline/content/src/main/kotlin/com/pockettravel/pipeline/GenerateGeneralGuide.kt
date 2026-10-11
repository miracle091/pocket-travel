package com.pockettravel.pipeline

import java.io.File

/**
 * Guida generale di guides.db: le sezioni delle pagine tematiche di Wikivoyage (salute, documenti, mance,
 * truffe...) che l'assistente cerca insieme alla regione aperta, per le domande generali che le guide delle regioni non
 * coprono. Sono righe normali di guide_sections con le categorie esistenti: le app gia' installate le importano senza
 * cercarle, e sameGuidesContent le confronta come le altre. Non e' uno slug possibile in regions.sh (che non comincia
 * mai con "_"). La lista delle pagine e' GENERAL_GUIDE_PAGES in build-guides.sh.
 */
const val GENERAL_GUIDE_REGION_ID = "_general"

// Titoli che non rispondono a domande: rimandi, indici, note, elenchi per paese, attrazioni.
private val skippedGeneralGuideHeadings = setOf(
    "see also", "vedi anche", "articoli correlati", "contents", "note", "help page", "information by region", "informazioni per regione",
    "health-related attractions", "attrazioni legate alla salute",
)

private val redirectRegex = Regex("""^\s*#(REDIRECT|RINVIA)\b""", RegexOption.IGNORE_CASE)

/**
 * Sezioni della pagina tematica [page], tutte della categoria [category] e con il link [sourceUrl]: una per titolo "==",
 * con il titolo "<pagina>: <titolo>"; stessa pulizia delle guide (cleanBody). L'introduzione della pagina apre la prima
 * sezione: da sola e' corta, quindi vince la ricerca con una definizione ("Il jet lag e'...") invece dei consigli.
 */
fun parseGeneralGuidePage(page: String, category: String, dump: String, sourceUrl: String): List<GuideSectionRow> =
    foldIntroduction(page, parseGeneralGuideSections(page, category, dump, sourceUrl))

private fun foldIntroduction(page: String, sections: List<GuideSectionRow>): List<GuideSectionRow> {
    val intro = sections.firstOrNull()?.takeIf { it.title == page } ?: return sections
    val next = sections.getOrNull(1) ?: return sections
    return listOf(next.copy(body = "${intro.body}\n\n${next.body}")) + sections.drop(2)
}

private fun parseGeneralGuideSections(page: String, category: String, dump: String, sourceUrl: String): List<GuideSectionRow> {
    val sections = mutableListOf<GuideSectionRow>()
    var heading: String? = null
    val body = StringBuilder()

    fun flush() {
        if (heading?.lowercase() in skippedGeneralGuideHeadings) return
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
 * Guida della guida generale da [tsv], una riga per pagina "categoria<TAB>pagina<TAB>dump.txt<TAB>sourceUrl". Una pagina con il dump
 * vuoto (non scaricata in questa run) o diventata un rinvio riprende le sue sezioni da [published], quelle del guides.db pubblicato. null senza
 * nessuna sezione.
 */
fun readGeneralGuide(tsv: File, published: List<GuideSectionRow>): RegionGuide? {
    val sections = tsv.readLines().filter { it.isNotBlank() }.flatMap { line ->
        val (category, page, dumpPath, sourceUrl) = line.split('\t')
        val text = dumpPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.length() > 0 }?.readText()
        // action=raw non segue i rinvii: una pagina diventata rinvio va corretta in GENERAL_GUIDE_PAGES, intanto resta quella pubblicata.
        val redirect = text != null && redirectRegex.containsMatchIn(text)
        if (redirect) println("::warning::pagina della guida generale $page e' un rinvio, correggi GENERAL_GUIDE_PAGES")
        if (text != null && !redirect) {
            parseGeneralGuidePage(page, category, text, sourceUrl)
        } else {
            println("guide: pagina della guida generale $page non scaricata, ricopio le sezioni pubblicate")
            published.filter { it.sourceUrl == sourceUrl }
        }
    }
    return sections.firstOrNull()?.let { RegionGuide(GENERAL_GUIDE_REGION_ID, it.sourceUrl.orEmpty(), sections) }
}
