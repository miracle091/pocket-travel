package com.pockettravel.pipeline

import java.io.File

// Duplicato minimo di core/content/WikivoyageDumpParser.kt e WikivoyageSectionMapping.kt:
// quel modulo e' una libreria Android (com.android.library), non consumabile da un modulo
// Kotlin/JVM puro senza conflitti di variant Gradle — vedi il commento in
// maptiles/build.gradle.kts per lo stesso problema con :core:*. La logica e' poche righe
// di testo, non vale la pena riorganizzare i moduli app solo per condividerla.
// Titoli IT (build-region.sh preferisce ora la pagina Wikivoyage italiana quando esiste, vedi
// quel file): stesso schema di sezioni delle voci EN sul template di pagina-nazione, titoli
// diversi. "Tenersi informati" mappa su VITA_QUOTIDIANA come il piu' vicino equivalente di "cope"
// (entrambe sezioni "vita pratica in loco" generiche) — non e' una traduzione letterale.
private val headingToCategory = mapOf(
    "respect" to "USI_COSTUMI",
    "rispettare le usanze" to "USI_COSTUMI",
    "get in" to "DOGANE",
    "come arrivare" to "DOGANE",
    "stay healthy" to "SALUTE",
    "situazione sanitaria" to "SALUTE",
    "stay safe" to "SICUREZZA",
    "sicurezza" to "SICUREZZA",
    "get around" to "TRASPORTI",
    "come spostarsi" to "TRASPORTI",
    "talk" to "FRASI_UTILI",
    "sleep" to "ALLOGGIO",
    "eat" to "CIBO_BEVANDE",
    "drink" to "CIBO_BEVANDE",
    "a tavola" to "CIBO_BEVANDE",
    "buy" to "ACQUISTI",
    "valuta e acquisti" to "ACQUISTI",
    "connect" to "CONNETTIVITA",
    "come restare in contatto" to "CONNETTIVITA",
    "cope" to "VITA_QUOTIDIANA",
    "tenersi informati" to "VITA_QUOTIDIANA",
)

// (?!=)/(?<!=) escludono i sotto-titoli ===/==== (3+ segni "="): senza, un "===Get in==="
// verrebbe trattato come un nuovo titolo di sezione (non mappato), troncando silenziosamente
// tutto il testo reale di Wikivoyage dopo la prima sottosezione — stesso fix di
// core/content/WikivoyageDumpParser.kt.
private val headingRegex = Regex("""^==(?!=)\s*(.+?)\s*(?<!=)==$""")
private val htmlCommentRegex = Regex("""(?s)<!--.*?-->""")
// Da tenere prima di htmlTagRegex: quest'ultimo toglie solo i tag <ref>/</ref>, lasciando il
// testo della citazione come prosa vagante in mezzo al corpo della sezione.
private val refTagRegex = Regex("""(?is)<ref\b[^>]*?/>|<ref\b[^>]*?>.*?</ref>""")
private val wikiFileLinkRegex = Regex("""(?is)\[\[(?:File|Image):.*?]]""")
private val wikiLinkRegex = Regex("""\[\[(?:[^|\]]*\|)?([^\]]+)]]""")
private val externalLinkWithTextRegex = Regex("""\[https?://\S+\s+([^\]]+)]""")
private val bareExternalLinkRegex = Regex("""\[https?://\S+]""")
private val boldItalicRegex = Regex("""'{2,3}""")
private val templateRegex = Regex("""\{\{[^}]*}}""")
private val htmlTagRegex = Regex("""<[^>]+>""")
private val subHeadingLineRegex = Regex("""^={3,}\s*(.+?)\s*={3,}$""")
// ";Termine" (lista di definizione wiki): Wikivoyage la usa come sottotitolo dentro un elenco
// (es. ";Vini rossi" sotto "Bere"), trattato come ===Termine===.
private val definitionTermLineRegex = Regex("""^;\s*(.+)$""")
private val listMarkerRegex =Regex("""^[*#:]+\s*""")
private val blankLinesRegex = Regex("""\n{3,}""")

// Mai presente in un testo reale: marca una riga di sottotitolo (===Money===) nel passaggio
// riga-per-riga di cleanBody, cosi' da poterla distinguere da una riga di corpo normale prima
// di convertirla nella forma finale "▸ Titolo" — o di scartarla se la sottosezione e' vuota
// (vedi commento su cleanBody).
private const val SUBHEADING_MARKER = ""

data class GuideSectionRow(val category: String, val title: String, val body: String)

fun parseWikivoyageDump(dumpText: String): List<GuideSectionRow> {
    val sections = mutableListOf<GuideSectionRow>()
    var currentHeading: String? = null
    val currentBody = StringBuilder()

    fun flush() {
        val heading = currentHeading ?: return
        val category = headingToCategory[heading.lowercase()] ?: return
        val body = cleanBody(currentBody.toString())
        if (body.isNotBlank()) {
            sections += GuideSectionRow(category = category, title = heading, body = body)
        }
    }

    dumpText.lineSequence().forEach { line ->
        val match = headingRegex.find(line.trim())
        if (match != null) {
            flush()
            currentHeading = match.groupValues[1]
            currentBody.setLength(0)
        } else {
            currentBody.appendLine(line)
        }
    }
    flush()

    return sections
}

// Il wikitext grezzo di Wikivoyage porta sintassi che non ha senso mostrare cosi' com'e' in una
// Text semplice (nessun renderer markdown lato app, vedi GuideScreen): citazioni <ref> il cui
// contenuto restava come prosa vagante, elenchi puntati/numerati con l'asterisco/cancelletto
// grezzo davanti, e sottotitoli ===Foo=== che — se la sottosezione era solo un template ormai
// tolto (es. {{Pricerange}} su "Money" in molte pagine paese) — restavano come parola orfana
// seguita da una riga vuota enorme.
private fun cleanBody(raw: String): String {
    val stripped = raw
        .replace(htmlCommentRegex, "")
        .replace(refTagRegex, "")
        .replace(wikiFileLinkRegex, "")
        .replace(externalLinkWithTextRegex, "$1")
        .replace(bareExternalLinkRegex, "")
        .replace(wikiLinkRegex, "$1")
        .replace(boldItalicRegex, "")
        .replace(templateRegex, "")
        .replace(htmlTagRegex, "")

    val markedLines = stripped.lineSequence().map { rawLine ->
        val line = rawLine.trim()
        val heading = (subHeadingLineRegex.find(line) ?: definitionTermLineRegex.find(line))?.groupValues?.get(1)
        if (heading != null) {
            "$SUBHEADING_MARKER$heading"
        } else {
            listMarkerRegex.replace(line) { match -> if (match.value.any { it == '*' || it == '#' }) "• " else "" }
        }
    }.toList()

    val kept = mutableListOf<String>()
    for (index in markedLines.indices) {
        val line = markedLines[index]
        if (line.startsWith(SUBHEADING_MARKER)) {
            val hasBody = markedLines.drop(index + 1)
                .takeWhile { !it.startsWith(SUBHEADING_MARKER) }
                .any { it.isNotBlank() }
            if (hasBody) kept += "▸ ${line.removePrefix(SUBHEADING_MARKER)}"
        } else {
            kept += line
        }
    }

    return kept.joinToString("\n")
        .replace(blankLinesRegex, "\n\n")
        .trim()
}

fun main(args: Array<String>) {
    require(args.size == 4) { "Uso: generateGuideContent <input dump.txt> <regionId> <sourceUrl> <output content.db>" }
    val dumpFile = File(args[0])
    val regionId = args[1]
    val sourceUrl = args[2]
    val outputDb = File(args[3])

    val sections = parseWikivoyageDump(dumpFile.readText())
    writeGuideDb(sections, regionId, sourceUrl, outputDb)
    println("guide_sections: ${sections.size} sezioni scritte in ${outputDb.path}")
}

/**
 * Schema minimo (non lo schema Room di GuideSectionEntity, niente FTS4): una tabella
 * "guide_sections" con le stesse colonne meno l'id autogenerato. L'app importa riga per
 * riga in region.db via GuideDao.insertAll(), che ripopola anche la shadow table FTS
 * come effetto collaterale dell'insert Room.
 *
 * outputDb e' content.db, condiviso con la tabella "poi" scritta da GeneratePoi.kt (le due tabelle
 * stavano in due file .db separati, accorpati in uno solo perche'
 * l'app li importa comunque entrambi nello stesso region.db) — non si cancella l'intero file,
 * solo la propria tabella, cosi' le due generazioni si compongono in qualunque ordine vengano
 * eseguite senza cancellarsi a vicenda.
 */
fun writeGuideDb(sections: List<GuideSectionRow>, regionId: String, sourceUrl: String, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "guide_sections",
        createTableSql = """
            CREATE TABLE guide_sections (
                regionId TEXT NOT NULL,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                sourceUrl TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES (?, ?, ?, ?, ?)",
        rows = sections,
    ) { insert, section ->
        insert.setString(1, regionId)
        insert.setString(2, section.category)
        insert.setString(3, section.title)
        insert.setString(4, section.body)
        insert.setString(5, sourceUrl)
    }
}
