package com.pockettravel.core.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ricerca degli indirizzi ("Via Roma 12a", "12 Rue de Rivoli") nel database addresses-search.db unico di ogni
 * regione (pacchetto ADDRESSES, vedi RegionStorage.ADDRESSES_SEARCH_DB): una sola connessione per regione,
 * aperta direttamente in sola lettura, non passa da Room. Le connessioni restano aperte tra una ricerca e
 * l'altra (e con loro le query preparate: SQLite riusa il testo SQL gia' compilato, vedi searchAddressDb);
 * si chiudono quando il file e' stato sostituito o eliminato (aggiornamento o rimozione dei civici),
 * controllandolo a ogni ricerca. Regioni senza database di ricerca (dati installati prima) non danno risultati.
 */
@Singleton
class AddressSearchRepository @Inject constructor(
    private val regionStorage: RegionStorage,
) {
    // Il file aperto e com'era (data e dimensione) all'apertura; db null = formato non supportato o non apribile.
    private class Handle(val stamp: Pair<Long, Long>, val db: SQLiteDatabase?, val origin: AddressOrigin?)

    private val lock = Any()
    private val open = HashMap<File, Handle>()

    /** Al piu' [limit] indirizzi per [text] nelle [regionIds], nell'ordine delle regioni; su IO. */
    suspend fun search(regionIds: List<String>, text: String, limit: Int = DEFAULT_LIMIT): List<AddressResult> = withContext(Dispatchers.IO) {
        val query = parseAddressQuery(text)
        // Una sola ricerca alla volta: una connessione non va chiusa mentre un'altra ricerca la sta usando.
        synchronized(lock) {
            closeStale()
            val found = LinkedHashMap<Triple<String, String?, String?>, AddressResult>()
            for (regionId in regionIds) {
                val file = runCatching { regionStorage.addressSearchDb(regionId) }.getOrNull() ?: continue
                val handle = handleFor(file)
                val db = handle.db ?: continue
                runCatching { searchAddressDb(SqliteAddressQuery(db), handle.origin!!, regionId, query, limit) }.getOrDefault(emptyList())
                    .forEach { found.putIfAbsent(Triple(it.street, it.number?.lowercase(), it.city), it) }
                if (found.size >= limit) return@synchronized found.values.take(limit)
            }
            found.values.toList()
        }
    }

    // Chiude le connessioni dei file spariti o sostituiti da una versione nuova.
    private fun closeStale() {
        open.entries.removeAll { (file, handle) ->
            val current = file.takeIf { it.isFile }?.let { it.lastModified() to it.length() }
            (current != handle.stamp).also { if (it) handle.db?.close() }
        }
    }

    private fun handleFor(file: File): Handle = open.getOrPut(file) {
        val stamp = file.lastModified() to file.length()
        val db = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()
        val origin = db?.let { runCatching { readAddressOrigin(SqliteAddressQuery(it)) }.getOrNull() }
        if (db != null && origin != null) {
            Handle(stamp, db, origin)
        } else {
            db?.close()
            Handle(stamp, null, null)
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
    }
}

private class SqliteAddressQuery(private val db: SQLiteDatabase) : AddressQueryRunner {
    override fun <T> query(sql: String, args: List<String>, read: (AddressRow) -> T): List<T> =
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val row = CursorAddressRow(cursor)
            buildList { while (cursor.moveToNext()) add(read(row)) }
        }
}

private class CursorAddressRow(private val cursor: Cursor) : AddressRow {
    override fun string(column: Int): String? = if (cursor.isNull(column)) null else cursor.getString(column)
    override fun long(column: Int): Long = cursor.getLong(column)
}
