package com.pockettravel.core.data.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Una conversazione cancellata non resta nel file di region.db: l'SQLite di Android e' compilato con
 * SQLITE_SECURE_DELETE (`PRAGMA secure_delete` = 1, azzeramento completo), quindi dopo il checkpoint del WAL i suoi byte
 * non sono piu' nel file. Il test fallisce se una versione di Android o un cambio dell'app (per esempio `FAST`, che
 * azzera meno) lo tolgono.
 */
@RunWith(AndroidJUnit4::class)
class SecureDeleteDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanUp() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun laConversazioneCancellataNonRestaNelFile() {
        context.deleteDatabase(DB_NAME)
        val db = Room.databaseBuilder(context, RegionDatabase::class.java, DB_NAME).setDriver(AndroidSQLiteDriver()).build()
        try {
            runBlocking {
                val dao = db.conversationDao()
                dao.insertConversationWithTurn(
                    ConversationEntity("c1", MARKER, MARKER, createdAt = 1, updatedAt = 1),
                    ConversationTurnEntity(conversationId = "c1", encryptedPayload = MARKER, createdAt = 1),
                )
                dao.delete("c1")
                // In una transazione: la connessione principale, quella che scrive.
                val secureDelete = db.withWriteTransaction { usePrepared("PRAGMA secure_delete") { it.step(); it.getLong(0) } }
                assertEquals(1L, secureDelete)
                db.useWriterConnection { it.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { statement -> statement.step() } }
            }
        } finally {
            db.close()
        }
        val file = context.getDatabasePath(DB_NAME)
        val bytes = listOf(file, File(file.path + "-wal")).filter { it.exists() }.fold(ByteArray(0)) { all, f -> all + f.readBytes() }
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(MARKER))
    }

    private companion object {
        const val DB_NAME = "secure-delete-test.db"
        const val MARKER = "PT-SECURE-DELETE-MARKER-0123456789"
    }
}
