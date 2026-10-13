package com.pockettravel.core.data

import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.pockettravel.core.data.db.CityDao
import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.EmergencyNumbersDao
import com.pockettravel.core.data.db.GuideDao
import com.pockettravel.core.data.db.InstalledRegionDao
import com.pockettravel.core.data.db.NoteDao
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PoiCodeDao
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.db.VaccinationDao
import com.pockettravel.core.data.db.jdbcSupportDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * DatabaseCompactor su un file SQLite vero (sqlite-jdbc): quando fa VACUUM, quando rinuncia per poco spazio libero sul disco o
 * perche' le pagine libere sono poche, e l'esclusione reciproca con le installazioni. Il ramo che inghiotte SQLiteException e' di
 * android.database.sqlite e non si puo' provare nei test JVM.
 */
class DatabaseCompactorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var file: File
    private lateinit var connection: Connection

    /** RegionDatabase che espone solo l'helper di apertura, sopra la connessione sqlite-jdbc del test. */
    private inner class FileBackedDatabase : RegionDatabase() {
        private val helper: SupportSQLiteOpenHelper = Proxy.newProxyInstance(
            SupportSQLiteOpenHelper::class.java.classLoader, arrayOf(SupportSQLiteOpenHelper::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getWritableDatabase" -> jdbcSupportDatabase(connection, file.absolutePath)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SupportSQLiteOpenHelper

        override val openHelper: SupportSQLiteOpenHelper get() = helper
        override fun guideDao(): GuideDao = throw UnsupportedOperationException()
        override fun poiDao(): PoiDao = throw UnsupportedOperationException()
        override fun poiCodeDao(): PoiCodeDao = throw UnsupportedOperationException()
        override fun installedRegionDao(): InstalledRegionDao = throw UnsupportedOperationException()
        override fun passportDao(): PassportDao = throw UnsupportedOperationException()
        override fun emergencyNumbersDao(): EmergencyNumbersDao = throw UnsupportedOperationException()
        override fun cityDao(): CityDao = throw UnsupportedOperationException()
        override fun noteDao(): NoteDao = throw UnsupportedOperationException()
        override fun diplomaticMissionDao(): DiplomaticMissionDao = throw UnsupportedOperationException()
        override fun vaccinationDao(): VaccinationDao = throw UnsupportedOperationException()
        override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()
        override fun clearAllTables(): Unit = throw UnsupportedOperationException()
    }

    @Before
    fun setUp() {
        file = folder.newFile("region.db")
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    }

    @After
    fun tearDown() = connection.close()

    private fun pragma(name: String): Long =
        connection.createStatement().use { s -> s.executeQuery("PRAGMA $name").use { it.next(); it.getLong(1) } }

    // Riempie il database di blob da 1 MiB e ne cancella la meta' piu' uno: restano [freeMegabytes] MiB di pagine libere.
    private fun fillAndDelete(totalMegabytes: Int, freeMegabytes: Int) {
        connection.createStatement().use { it.execute("CREATE TABLE blobs(id INTEGER PRIMARY KEY, data BLOB)") }
        connection.autoCommit = false
        connection.prepareStatement("INSERT INTO blobs(data) VALUES (zeroblob(?))").use { s ->
            repeat(totalMegabytes) { s.setInt(1, 1024 * 1024); s.executeUpdate() }
        }
        connection.commit()
        connection.autoCommit = true
        connection.prepareStatement("DELETE FROM blobs WHERE id <= ?").use { s ->
            s.setInt(1, freeMegabytes)
            s.executeUpdate()
        }
    }

    private fun freeBytes() = pragma("freelist_count") * pragma("page_size")

    @Test
    fun `con poche pagine libere non fa il VACUUM`() = runBlocking {
        fillAndDelete(totalMegabytes = 8, freeMegabytes = 4)
        val before = freeBytes()
        assertTrue(before in 1 until 32L * 1024 * 1024)

        DatabaseCompactor(FileBackedDatabase(), Dispatchers.IO) { Long.MAX_VALUE }.compactIfWasteful()

        assertEquals(before, freeBytes())
    }

    @Test
    fun `con almeno 32 MiB di pagine libere e disco a sufficienza accorcia il file`() = runBlocking {
        fillAndDelete(totalMegabytes = 40, freeMegabytes = 36)
        val sizeBefore = file.length()
        assertTrue(freeBytes() >= 32L * 1024 * 1024)

        DatabaseCompactor(FileBackedDatabase(), Dispatchers.IO) { Long.MAX_VALUE }.compactIfWasteful()

        assertEquals(0L, pragma("freelist_count"))
        assertTrue("${file.length()} < $sizeBefore", file.length() < sizeBefore / 4)
        // I dati vivi restano: 4 blob da 1 MiB.
        assertEquals(4L, connection.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM blobs").use { it.next(); it.getLong(1) } })
    }

    @Test
    fun `senza il doppio dei dati vivi di spazio libero rinuncia al VACUUM`() = runBlocking {
        fillAndDelete(totalMegabytes = 40, freeMegabytes = 36)
        val live = pragma("page_count") * pragma("page_size") - freeBytes()
        val before = freeBytes()

        // Un byte meno del doppio dei dati vivi: niente VACUUM.
        DatabaseCompactor(FileBackedDatabase(), Dispatchers.IO) { 2 * live - 1 }.compactIfWasteful()
        assertEquals(before, freeBytes())

        // Esattamente il doppio: si fa.
        DatabaseCompactor(FileBackedDatabase(), Dispatchers.IO) { 2 * live }.compactIfWasteful()
        assertEquals(0L, pragma("freelist_count"))
    }

    @Test
    fun `un database vuoto o senza pagine libere non da' errori`() = runBlocking {
        DatabaseCompactor(FileBackedDatabase(), Dispatchers.IO) { 0L }.compactIfWasteful()
        connection.createStatement().use { it.execute("CREATE TABLE t(x)") }
        DatabaseCompactor(FileBackedDatabase(), Dispatchers.IO) { 0L }.compactIfWasteful()
        assertEquals(0L, pragma("freelist_count"))
    }

    @Test
    fun `durante un'installazione compactIfWasteful torna subito senza toccare il database`() = runBlocking {
        fillAndDelete(totalMegabytes = 40, freeMegabytes = 36)
        val before = freeBytes()
        val compactor = DatabaseCompactor(FileBackedDatabase(), Dispatchers.IO) { Long.MAX_VALUE }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()

        val install = async { compactor.exclusive { started.complete(Unit); finish.await() } }
        started.await()
        compactor.compactIfWasteful()
        assertEquals("il VACUUM non deve partire mentre l'installazione e' in corso", before, freeBytes())

        finish.complete(Unit)
        install.await()
        compactor.compactIfWasteful()
        assertEquals(0L, pragma("freelist_count"))
    }

    @Test
    fun `exclusive restituisce il valore del blocco e rilascia il lucchetto anche se il blocco lancia`() = runBlocking {
        val compactor = DatabaseCompactor(FileBackedDatabase(), Dispatchers.IO) { Long.MAX_VALUE }
        assertEquals(42, compactor.exclusive { 42 })

        assertThrows(IllegalStateException::class.java) { runBlocking { compactor.exclusive { error("installazione fallita") } } }

        // Il lucchetto e' libero: una nuova esclusione non resta in attesa.
        assertEquals("ok", compactor.exclusive { "ok" })
    }

    @Test
    fun `un errore diverso da SQLiteException esce dal compattatore e libera il lucchetto`() = runBlocking {
        val broken = object : RegionDatabase() {
            override val openHelper: SupportSQLiteOpenHelper get() = throw IllegalStateException("database non aperto")
            override fun guideDao(): GuideDao = throw UnsupportedOperationException()
            override fun poiDao(): PoiDao = throw UnsupportedOperationException()
            override fun poiCodeDao(): PoiCodeDao = throw UnsupportedOperationException()
            override fun installedRegionDao(): InstalledRegionDao = throw UnsupportedOperationException()
            override fun passportDao(): PassportDao = throw UnsupportedOperationException()
            override fun emergencyNumbersDao(): EmergencyNumbersDao = throw UnsupportedOperationException()
            override fun cityDao(): CityDao = throw UnsupportedOperationException()
            override fun noteDao(): NoteDao = throw UnsupportedOperationException()
            override fun diplomaticMissionDao(): DiplomaticMissionDao = throw UnsupportedOperationException()
            override fun vaccinationDao(): VaccinationDao = throw UnsupportedOperationException()
            override fun createInvalidationTracker(): InvalidationTracker = throw UnsupportedOperationException()
            override fun clearAllTables(): Unit = throw UnsupportedOperationException()
        }
        val compactor = DatabaseCompactor(broken, Dispatchers.IO) { Long.MAX_VALUE }

        assertThrows(IllegalStateException::class.java) { runBlocking { compactor.compactIfWasteful() } }
        assertEquals(1, compactor.exclusive { 1 })
    }
}
