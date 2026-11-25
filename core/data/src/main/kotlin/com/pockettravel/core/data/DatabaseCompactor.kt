package com.pockettravel.core.data

import android.util.Log
import androidx.room3.PooledConnection
import androidx.room3.executeSQL
import androidx.sqlite.SQLiteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** La connessione di scrittura di region.db, fuori da ogni transazione: Room la da' con useWriterConnection, i test con sqlite-jdbc. */
fun interface WriterAccess {
    suspend fun use(block: suspend (PooledConnection) -> Unit)
}

/**
 * Restituisce al sistema lo spazio che region.db non usa piu'. SQLite non rimpicciolisce il file: le pagine delle righe
 * cancellate (una regione tolta, un pacchetto POI o citta') restano nella lista delle pagine libere e servono solo ai
 * prossimi inserimenti. [compactIfWasteful] fa VACUUM quando sono almeno [MIN_FREE_BYTES].
 *
 * Il VACUUM riscrive l'intero file (secondi, su un database da centinaia di MB) e non puo' stare dentro una transazione:
 * [exclusive] lo separa dalle installazioni, che tengono una transazione di scrittura lunga. Le altre scritture
 * (importazione delle guide) il VACUUM le aspetta da solo, perche' SQLite ha un solo writer.
 */
class DatabaseCompactor(
    private val writer: WriterAccess,
    private val ioDispatcher: CoroutineDispatcher,
    private val availableBytes: () -> Long,
) {
    private val lock = Mutex()

    /** Esegue [block] senza che [compactIfWasteful] parta nel frattempo (una installazione con la sua transazione). */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /**
     * Fa checkpoint del WAL e VACUUM se le pagine libere superano [MIN_FREE_BYTES] e il disco ha almeno il doppio dei
     * dati vivi di spazio libero (il VACUUM scrive una copia intera e il WAL prima di accorciare il file). Non fa nulla, senza errori, se
     * un'installazione e' in corso (la prossima rimozione riprovera') o se il VACUUM fallisce: lo spazio non restituito
     * non e' un guasto. Non va chiamata dentro una transazione Room: il VACUUM gira su un altro thread e aspetterebbe
     * la transazione che aspetta lui.
     */
    suspend fun compactIfWasteful() {
        if (!lock.tryLock()) return
        try {
            withContext(ioDispatcher) {
                try {
                    writer.use { compact(it) }
                } catch (error: SQLiteException) {
                    Log.w(TAG, "VACUUM di region.db non riuscito", error)
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun compact(db: PooledConnection) {
        suspend fun pragma(name: String): Long = db.usePrepared("PRAGMA $name") { if (it.step()) it.getLong(0) else 0L }
        val pageSize = pragma("page_size")
        val freeBytes = pragma("freelist_count") * pageSize
        if (freeBytes < MIN_FREE_BYTES) return
        // Il WAL prima: con le pagine ancora li' il file principale sembra piu' piccolo e il VACUUM lo raddoppierebbe.
        db.executeSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        // Il VACUUM scrive i dati vivi due volte (la copia ricostruita e il WAL) prima che il file si accorci: le pagine libere
        // non contano, e dopo una migrazione che ha lasciato mezzo file vuoto sono la meta'.
        val liveBytes = pragma("page_count") * pageSize - freeBytes
        if (availableBytes() < 2 * liveBytes) return
        db.executeSQL("VACUUM")
        // Il VACUUM in modalita' WAL passa dal log: svuotarlo, o resterebbe un -wal grande quanto il database.
        db.executeSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    private companion object {
        const val TAG = "DatabaseCompactor"
        const val MIN_FREE_BYTES = 32L * 1024 * 1024
    }
}
