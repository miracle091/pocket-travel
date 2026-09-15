package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager

// Duplicato minimo di core/content/WikivoyageDumpParser.kt e WikivoyageSectionMapping.kt:
// quel modulo e' una libreria Android (com.android.library), non consumabile da un modulo
// Kotlin/JVM puro senza conflitti di variant Gradle — vedi il commento in
// maptiles/build.gradle.kts per lo stesso problema con :core:*. La logica e' poche righe
// di testo, non vale la pena riorganizzare i moduli app solo per condividerla.
private val headingToCategory = mapOf(
    "respect" to "USI_COSTUMI",
    "get in" to "DOGANE",
    "stay healthy" to "SALUTE",
    "stay safe" to "SICUREZZA",
    "get around" to "TRASPORTI",
    "talk" to "FRASI_UTILI",
    "sleep" to "ALLOGGIO",
    "eat" to "CIBO_BEVANDE",
    "drink" to "CIBO_BEVANDE",
    "buy" to "ACQUISTI",
    "connect" to "CONNETTIVITA",
    "cope" to "VITA_QUOTIDIANA",
)

// (?!=)/(?<!=) escludono i sotto-titoli ===/==== (3+ segni "="): senza, un "===Get in==="
// verrebbe trattato come un nuovo titolo di sezione (non mappato), troncando silenziosamente
// tutto il testo reale di Wikivoyage dopo la prima sottosezione — stesso fix di
// core/content/WikivoyageDumpParser.kt.
private val headingRegex = Regex("""^==(?!=)\s*(.+?)\s*(?<!=)==$""")
private val wikiFileLinkRegex = Regex("""(?is)\[\[(?:File|Image):.*?]]""")
private val wikiLinkRegex = Regex("""\[\[(?:[^|\]]*\|)?([^\]]+)]]""")
private val externalLinkWithTextRegex = Regex("""\[https?://\S+\s+([^\]]+)]""")
private val bareExternalLinkRegex = Regex("""\[https?://\S+]""")
private val boldItalicRegex = Regex("""'{2,3}""")
private val templateRegex = Regex("""\{\{[^}]*}}""")
private val htmlTagRegex = Regex("""<[^>]+>""")
private val subHeadingLineRegex = Regex("""(?m)^={3,}\s*(.+?)\s*={3,}$""")

data class GuideSectionRow(val category: String, val title: String, val body: String)

fun parseWikivoyageDump(dumpText: String): List<GuideSectionRow> {
    val sections = mutableListOf<GuideSectionRow>()
    var currentHeading: String? = null
    val currentBody = StringBuilder()

    fun flush() {
        val heading = currentHeading ?: return
        val category = headingToCategory[heading.lowercase()] ?: return
        val body = currentBody.toString()
            .replace(wikiFileLinkRegex, "")
            .replace(externalLinkWithTextRegex, "$1")
            .replace(bareExternalLinkRegex, "")
            .replace(wikiLinkRegex, "$1")
            .replace(boldItalicRegex, "")
            .replace(templateRegex, "")
            .replace(htmlTagRegex, "")
            .replace(subHeadingLineRegex, "$1")
            .trim()
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
    DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
        conn.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS guide_sections")
            statement.execute(
                """
                CREATE TABLE guide_sections (
                    regionId TEXT NOT NULL,
                    category TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    sourceUrl TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
        // Stesso fix di GeneratePoi.kt/writePoiDb: una transazione esplicita evita un commit
        // con fsync per ogni riga (qui sempre poche unita', ma coerente con l'altra tabella
        // dello stesso file).
        conn.autoCommit = false
        conn.prepareStatement(
            "INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES (?, ?, ?, ?, ?)"
        ).use { insert ->
            sections.forEach { section ->
                insert.setString(1, regionId)
                insert.setString(2, section.category)
                insert.setString(3, section.title)
                insert.setString(4, section.body)
                insert.setString(5, sourceUrl)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        conn.commit()
    }
}
