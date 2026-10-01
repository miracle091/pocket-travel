package com.pockettravel.core.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Il minimo di un database SQLite che serve a [mergeAddressSearchDbs], per non legarlo a android.database (testabile via JDBC). */
internal interface MergeDb {
    fun exec(sql: String)

    /** Prima colonna della prima riga come testo, null se non c'e' nessuna riga (o e' NULL). */
    fun queryString(sql: String): String?
}

// Stesso schema che la pipeline pubblica per ogni cella (formato 2), con l'origine unica di tutta la regione.
private const val FORMAT = "2"
private const val ADDRESS_COLUMNS =
    "(street_id INTEGER NOT NULL, number TEXT NOT NULL COLLATE NOCASE, dlat INTEGER NOT NULL, dlon INTEGER NOT NULL, PRIMARY KEY(street_id, number, dlat, dlon)) WITHOUT ROWID"

/**
 * Unisce i database di ricerca per cella ([cells], formato 2) in un solo database della regione, aperto in
 * [db] (vuoto, appena creato) con lo stesso schema: i nomi di via uguali in celle diverse (stessa via e stessa
 * citta') diventano un'unica via con un id globale, gli indirizzi si ricalcolano rispetto a un'origine sola
 * (la piu' a sud-ovest delle celle, cosi' dlat/dlon restano positivi) e si scrivono in ordine di chiave
 * primaria (pagine piene, file piu' piccolo). L'ordine si ottiene passando da [scratch], un file temporaneo
 * con la stessa chiave primaria che il chiamante cancella: in una tabella TEMP SQLite userebbe la memoria, che
 * per una regione con milioni di civici non basta. Alla fine ANALYZE, perche' il pianificatore scelga la
 * chiave primaria. Lancia IllegalStateException per una cella che non e' nel formato 2.
 */
internal fun mergeAddressSearchDbs(db: MergeDb, cells: List<File>, scratch: File) {
    require(cells.isNotEmpty()) { "Nessuna cella da unire" }
    db.exec("PRAGMA page_size=1024")
    // Il file si ricostruisce da capo se l'installazione si interrompe: niente fsync.
    db.exec("PRAGMA synchronous=OFF")
    db.queryString("PRAGMA journal_mode=DELETE")
    db.exec("CREATE TABLE street(id INTEGER PRIMARY KEY, name TEXT NOT NULL, key TEXT NOT NULL, city TEXT)")
    db.exec("CREATE INDEX street_key ON street(key)")
    db.exec("CREATE TABLE address $ADDRESS_COLUMNS")
    db.exec("ATTACH DATABASE '${sqlQuoted(scratch)}' AS scratch")
    db.queryString("PRAGMA scratch.journal_mode=OFF")
    db.exec("CREATE TABLE scratch.address $ADDRESS_COLUMNS")

    // Prima passata: formato e origine di ogni cella, e le vie (quelle gia' presenti, con lo stesso nome e la
    // stessa citta', non si ripetono).
    val origins = cells.map { cell ->
        withCell(db, cell) {
            check(db.queryString("SELECT value FROM cell.meta WHERE key = 'format'") == FORMAT) { "Formato non supportato: ${cell.name}" }
            val lat = checkNotNull(db.queryString("SELECT value FROM cell.meta WHERE key = 'origin_lat_e6'")?.toLongOrNull()) { "Origine mancante: ${cell.name}" }
            val lon = checkNotNull(db.queryString("SELECT value FROM cell.meta WHERE key = 'origin_lon_e6'")?.toLongOrNull()) { "Origine mancante: ${cell.name}" }
            db.exec(
                "INSERT INTO main.street(name, key, city) SELECT DISTINCT cs.name, cs.key, cs.city FROM cell.street cs " +
                    "WHERE NOT EXISTS (SELECT 1 FROM main.street s WHERE s.key = cs.key AND s.name = cs.name AND s.city IS cs.city)",
            )
            lat to lon
        }
    }
    val originLat = origins.minOf { it.first }
    val originLon = origins.minOf { it.second }

    // Seconda passata: gli indirizzi, con l'id globale della via e gli scarti rispetto all'origine unica.
    // OR IGNORE: lo stesso indirizzo in due celle (non dovrebbe) si tiene una volta sola.
    cells.forEachIndexed { index, cell ->
        val (lat, lon) = origins[index]
        withCell(db, cell) {
            db.exec(
                "INSERT OR IGNORE INTO scratch.address SELECT s.id, a.number, a.dlat + ${lat - originLat}, a.dlon + ${lon - originLon} FROM cell.address a " +
                    "JOIN cell.street cs ON cs.id = a.street_id JOIN main.street s ON s.key = cs.key AND s.name = cs.name AND s.city IS cs.city",
            )
        }
    }
    db.exec("INSERT INTO main.address SELECT street_id, number, dlat, dlon FROM scratch.address ORDER BY street_id, number, dlat, dlon")
    db.exec("DETACH DATABASE scratch")

    db.exec("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT) WITHOUT ROWID")
    db.exec("INSERT INTO meta VALUES ('format', '$FORMAT'), ('origin_lat_e6', '$originLat'), ('origin_lon_e6', '$originLon')")
    db.exec("ANALYZE")
}

private fun <T> withCell(db: MergeDb, cell: File, block: () -> T): T {
    db.exec("ATTACH DATABASE '${sqlQuoted(cell)}' AS cell")
    try {
        return block()
    } finally {
        db.exec("DETACH DATABASE cell")
    }
}

private fun sqlQuoted(file: File): String = file.path.replace("'", "''")

/**
 * Crea [target] unendo i database di ricerca [cells] con SQLite del device (vedi [mergeAddressSearchDbs]).
 * Bloccante: va chiamata fuori dal main thread.
 */
fun mergeAddressSearchOnDevice(cells: List<File>, target: File) {
    val scratch = File(target.path + ".scratch")
    target.delete()
    scratch.delete()
    try {
        // Senza collatori localizzati Android non crea android_metadata all'apertura: il file resta vuoto fino al
        // primo CREATE, e PRAGMA page_size ha ancora effetto.
        val flags = SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        SQLiteDatabase.openDatabase(target.path, null, flags).use { db -> mergeAddressSearchDbs(AndroidMergeDb(db), cells, scratch) }
    } finally {
        scratch.delete()
        File(scratch.path + "-journal").delete()
        File(target.path + "-journal").delete()
    }
}

private class AndroidMergeDb(private val db: SQLiteDatabase) : MergeDb {
    override fun exec(sql: String) = db.execSQL(sql)

    override fun queryString(sql: String): String? =
        db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
}
