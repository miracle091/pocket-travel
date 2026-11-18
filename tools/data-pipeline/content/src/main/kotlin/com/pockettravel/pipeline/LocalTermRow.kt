package com.pockettravel.pipeline

import org.json.JSONObject
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate

/**
 * Termine locale di una guida (piatto, bevanda, festa, usanza, abito, strumento): voce di Wikipedia del paese che la guida
 * nomina, con l'introduzione della voce come spiegazione (local_terms.py). [aliases]: altri nomi con cui compare nel testo.
 */
data class LocalTermRow(
    val term: String,
    val aliases: List<String>,
    val title: String,
    val summary: String,
    val sourceUrl: String,
    val revision: Long?,
)

/** {regionId: termini} dal file di local_terms.py; una regione letta senza termini ha la lista vuota. */
fun parseLocalTerms(jsonl: String): Map<String, List<LocalTermRow>> =
    jsonl.lineSequence().filter { it.isNotBlank() }.associate { line ->
        val obj = JSONObject(line)
        val terms = obj.getJSONArray("terms")
        obj.getString("regionId") to (0 until terms.length()).map { i ->
            val term = terms.getJSONObject(i)
            val aliases = term.getJSONArray("aliases")
            LocalTermRow(
                term = term.getString("term"),
                aliases = (0 until aliases.length()).map(aliases::getString),
                title = term.getString("title"),
                summary = term.getString("summary"),
                sourceUrl = term.getString("url"),
                revision = if (term.isNull("revision")) null else term.getLong("revision"),
            )
        }
    }

/**
 * Tabella guide_terms di guides.db: una riga per termine di [terms] ({regionId: termini}), gli alias separati da "\n".
 * L'indice FTS lo costruisce l'app all'import, come per guide_sections.
 */
fun writeLocalTermsTable(terms: Map<String, List<LocalTermRow>>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "guide_terms",
        createTableSql = "CREATE TABLE guide_terms (regionId TEXT NOT NULL, term TEXT NOT NULL, aliases TEXT NOT NULL, " +
            "title TEXT NOT NULL, summary TEXT NOT NULL, sourceUrl TEXT NOT NULL, revision INTEGER)",
        insertSql = "INSERT INTO guide_terms (regionId, term, aliases, title, summary, sourceUrl, revision) VALUES (?, ?, ?, ?, ?, ?, ?)",
        rows = terms.entries.sortedBy { it.key }.flatMap { (regionId, rows) -> rows.map { regionId to it } },
    ) { insert, (regionId, row) ->
        val texts = listOf(regionId, row.term, row.aliases.joinToString("\n"), row.title, row.summary, row.sourceUrl)
        texts.forEachIndexed { i, text -> insert.setString(i + 1, text) }
        insert.setObject(texts.size + 1, row.revision)
    }
}

/** Termini locali di [regionId] in un guides.db pubblicato; nessuno se il file non ha la tabella. */
fun readLocalTerms(db: File, regionId: String): List<LocalTermRow> =
    DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
        val sql = "SELECT term, aliases, title, summary, sourceUrl, revision FROM guide_terms WHERE regionId = ? ORDER BY rowid"
        val query = runCatching { conn.prepareStatement(sql) }.getOrNull() ?: return@use emptyList()
        query.use {
            it.setString(1, regionId)
            val rs = it.executeQuery()
            buildList {
                while (rs.next()) {
                    val revision = rs.getLong("revision").takeUnless { rs.wasNull() }
                    val aliases = rs.getString("aliases").split("\n").filter(String::isNotEmpty)
                    add(LocalTermRow(rs.getString("term"), aliases, rs.getString("title"), rs.getString("summary"), rs.getString("sourceUrl"), revision))
                }
            }
        }
    }

/**
 * Termini delle regioni [regionIds]: quelli di [fresh], o per le regioni che local_terms.py non ha letto in questa run
 * quelli gia' pubblicati ([published] della regione).
 */
fun localTermsOf(
    regionIds: List<String>,
    fresh: Map<String, List<LocalTermRow>>,
    published: (String) -> List<LocalTermRow>,
): Map<String, List<LocalTermRow>> = regionIds.associateWith { fresh[it] ?: published(it) }

/**
 * Data dell'ultima lettura (AAAA-MM-GG) delle regioni [regionIds]: [today] per quelle di [fresh], lette in questa run,
 * quella pubblicata ([published] della regione) per le altre; nessuna per le regioni mai lette. local_terms.py legge
 * per prime le regioni lette meno di recente (reading_order).
 */
fun localTermsReadDates(
    regionIds: List<String>,
    fresh: Map<String, List<LocalTermRow>>,
    today: LocalDate,
    published: (String) -> String?,
): Map<String, String> = regionIds.mapNotNull { id -> (if (id in fresh) today.toString() else published(id))?.let { id to it } }.toMap()

/**
 * Tabella guide_terms_read di guides.db, con le date di [dates]. L'app non la legge e sameGuidesContent non la confronta:
 * una data nuova da sola pubblica un guides.db solo se le date pubblicate sono vecchie (staleLocalTermsReadDates).
 */
fun writeLocalTermsReadTable(dates: Map<String, String>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "guide_terms_read",
        createTableSql = "CREATE TABLE guide_terms_read (regionId TEXT NOT NULL PRIMARY KEY, readAt TEXT NOT NULL)",
        insertSql = "INSERT INTO guide_terms_read (regionId, readAt) VALUES (?, ?)",
        rows = dates.entries.sortedBy { it.key },
    ) { insert, (regionId, readAt) ->
        insert.setString(1, regionId)
        insert.setString(2, readAt)
    }
}

/** Date di lettura dei termini in un guides.db; nessuna se il file non ha la tabella. */
fun readLocalTermsReadDates(db: File): Map<String, String> =
    DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
        val hasTable = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'guide_terms_read'").use { it.executeQuery().next() }
        if (!hasTable) return@use emptyMap()
        conn.prepareStatement("SELECT regionId, readAt FROM guide_terms_read").use {
            val rs = it.executeQuery()
            buildMap { while (rs.next()) put(rs.getString("regionId"), rs.getString("readAt")) }
        }
    }

// Oltre questa eta' della lettura piu' vecchia, date di lettura cambiate pubblicano un guides.db anche senza altri cambi.
const val TERMS_READ_MAX_AGE_DAYS = 28L

/**
 * True se le date di lettura di [outputDb] vanno pubblicate anche senza altri cambi: sono diverse da quelle di
 * [publishedDb], e quelle pubblicate mancano o la piu' vecchia ha piu' di TERMS_READ_MAX_AGE_DAYS. Senza, nelle settimane
 * in cui il guides.db pubblicato resta, le date nuove si perdono e la run dopo rilegge le stesse regioni: al piu' un
 * guides.db in piu' ogni quattro settimane.
 */
fun staleLocalTermsReadDates(outputDb: File, publishedDb: File, today: LocalDate): Boolean {
    val fresh = readLocalTermsReadDates(outputDb)
    val published = readLocalTermsReadDates(publishedDb)
    if (fresh == published) return false
    return published.isEmpty() || published.values.minOf(LocalDate::parse).isBefore(today.minusDays(TERMS_READ_MAX_AGE_DAYS))
}
