package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tabella diplomatic_missions di guides.db (e guides-en.db): ambasciate e consolati di tutto il mondo
 * da Wikidata, per completare gli OSM POI di una regione. Le righe arrivano dal TSV di
 * wikidata_missions.py (13 colonne nell'ordine della tabella, campo vuoto = NULL).
 *
 * Se il TSV manca o e' vuoto (Wikidata non ha risposto in questa run) si ricopia la tabella dal
 * guides.db pubblicato; se manca anche quella, guides.db esce senza la tabella (l'app la ignora)
 * e si scrive solo un avviso: le guide si pubblicano comunque.
 */
private val missionColumns = listOf(
    "wikidata", "sending", "host", "kind", "name", "name_en", "city", "address", "phone", "website", "email", "lat", "lon",
)

/** Latitudine e longitudine valide (numeri finiti nei limiti) solo in coppia: altrimenti entrambe null. */
private fun validCoordinates(lat: String?, lon: String?): Pair<String?, String?> {
    val la = lat?.toDoubleOrNull()
    val lo = lon?.toDoubleOrNull()
    return if (la != null && lo != null && la.isFinite() && lo.isFinite() && la in -90.0..90.0 && lo in -180.0..180.0) lat to lon else null to null
}

/** Una riga malformata (colonne sbagliate o campi obbligatori vuoti) si salta con un avviso: non deve fermare tutte le guide. */
fun readMissionsTsv(tsv: File): List<List<String?>> =
    tsv.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != missionColumns.size) {
            println("AVVISO: diplomatic_missions: riga con ${fields.size} colonne invece di ${missionColumns.size} saltata: $line")
            return@mapNotNull null
        }
        val values = fields.map { it.takeIf(String::isNotEmpty) }
        if (values.subList(0, 5).any { it == null }) {
            println("AVVISO: diplomatic_missions: riga con campi obbligatori vuoti saltata: $line")
            return@mapNotNull null
        }
        val (lat, lon) = validCoordinates(values[11], values[12])
        if (lat != values[11] || lon != values[12]) println("AVVISO: diplomatic_missions: coordinate non valide ignorate per ${values[0]}")
        // Un sito che non e' un URL http(s) si scarta: l'app lo aprirebbe come collegamento.
        val website = values[9]?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        values.toMutableList().also { it[9] = website; it[11] = lat; it[12] = lon }
    }

/**
 * Wikidata cambia ogni giorno: le sole missioni non bastano a pubblicare un nuovo guides.db (tutti
 * gli utenti lo riscaricherebbero), a meno che quelle pubblicate abbiano piu' di questi giorni.
 * La data delle missioni sta nella tabella guides_meta di guides.db.
 */
const val MISSIONS_MAX_AGE_DAYS = 14L

fun writeDiplomaticMissions(missionsTsv: File?, publishedDb: File?, outputDb: File, today: LocalDate = LocalDate.now(ZoneOffset.UTC)) {
    var rows = missionsTsv?.takeIf { it.length() > 0 }?.let(::readMissionsTsv)
    var date: LocalDate? = today
    if (rows == null) {
        rows = publishedDb?.let { readRows(it, "SELECT ${missionColumns.joinToString()} FROM diplomatic_missions") }
        if (rows == null) {
            println("AVVISO: missioni diplomatiche non disponibili (Wikidata non letto e nessuna tabella pubblicata): guides.db senza diplomatic_missions")
            return
        }
        println("guide: missioni diplomatiche di Wikidata non lette in questa run, ricopio le ${rows.size} pubblicate")
        // Le missioni ricopiate restano vecchie quanto erano: la data non si rinfresca.
        date = publishedDb?.let(::readMissionsDate)
    }
    writeDiplomaticMissionsTable(rows, outputDb)
    if (date != null) {
        writeSqliteTable(
            outputDb = outputDb,
            tableName = "guides_meta",
            createTableSql = "CREATE TABLE guides_meta (missions_date TEXT NOT NULL)",
            insertSql = "INSERT INTO guides_meta (missions_date) VALUES (?)",
            rows = listOf(date),
        ) { insert, value -> insert.setString(1, value.toString()) }
    }
}

/** Data (ISO) delle missioni di un guides.db; null se manca la tabella guides_meta o la data non e' leggibile. */
fun readMissionsDate(db: File): LocalDate? =
    readRows(db, "SELECT missions_date FROM guides_meta")?.firstOrNull()?.firstOrNull()
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * True se il guides.db appena generato non va pubblicato: identico al pubblicato, oppure differente solo per le missioni
 * diplomatiche e/o per i consigli di travel.gc.ca, con le missioni pubblicate piu' giovani di MISSIONS_MAX_AGE_DAYS (senza
 * data nel file pubblicato si considerano vecchie) e i consigli pubblicati piu' giovani di TRAVEL_ADVICE_MAX_AGE_DAYS senza
 * cambi di rischio (keepPublishedTravelAdvice).
 */
fun keepPublishedGuides(outputDb: File, publishedDb: File, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Boolean {
    if (sameGuidesContent(outputDb, publishedDb)) return true
    if (!sameGuidesContent(outputDb, publishedDb, includeMissions = false, includeTravelAdvice = false)) return false
    val missionsKept = sameMissions(outputDb, publishedDb) ||
        readMissionsDate(publishedDb)?.let { !it.isBefore(today.minusDays(MISSIONS_MAX_AGE_DAYS)) } == true
    return missionsKept && (sameTravelAdvice(outputDb, publishedDb) || keepPublishedTravelAdvice(outputDb, publishedDb, today))
}

fun writeDiplomaticMissionsTable(rows: List<List<String?>>, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "diplomatic_missions",
        createTableSql = """
            CREATE TABLE diplomatic_missions (
                wikidata TEXT NOT NULL PRIMARY KEY,
                sending TEXT NOT NULL,
                host TEXT NOT NULL,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                name_en TEXT,
                city TEXT,
                address TEXT,
                phone TEXT,
                website TEXT,
                email TEXT,
                lat REAL,
                lon REAL
            )
            """.trimIndent(),
        insertSql = "INSERT OR REPLACE INTO diplomatic_missions (${missionColumns.joinToString()}) VALUES (${missionColumns.joinToString { "?" }})",
        rows = rows,
    ) { insert, row ->
        row.forEachIndexed { index, value ->
            val column = index + 1
            when {
                value == null -> insert.setObject(column, null)
                index >= 11 -> value.toDoubleOrNull()?.let { insert.setDouble(column, it) } ?: insert.setObject(column, null)
                else -> insert.setString(column, value)
            }
        }
    }
    // L'indice a parte: createTableSql di writeSqliteTable e' un solo statement.
    DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
        conn.createStatement().use {
            it.execute("CREATE INDEX IF NOT EXISTS diplomatic_missions_pair ON diplomatic_missions(sending, host)")
        }
    }
}
