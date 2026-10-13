package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** File delle celle degli indirizzi, controlli sugli argomenti e funzioni sullo spazio di RegionStorage che RegionStorageTest non copre. */
class RegionStorageAddressesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun storage(): RegionStorage =
        RegionStorage(File(tempFolder.root, "regions").apply { mkdirs() }, File(tempFolder.root, "staging").apply { mkdirs() })

    // Elenco delle celle degli indirizzi

    @Test
    fun `l'elenco delle celle vuoto fa il giro`() {
        assertEquals("{}", RegionStorage.encodeAddressCells(emptyMap()))
        assertEquals(emptyMap<String, String>(), RegionStorage.decodeAddressCells("{}"))
        assertEquals(emptyMap<String, String>(), RegionStorage.decodeAddressCells(""))
        assertEquals(emptyMap<String, String>(), RegionStorage.decodeAddressCells("   {  }  \n"))
    }

    @Test
    fun `le celle si scrivono in ordine di id e si rileggono uguali`() {
        val cells = mapOf("14/8700/5800" to "v2", "14/8700/5799" to "v1", "9/1/2" to "2026-10-01")

        val text = RegionStorage.encodeAddressCells(cells)

        assertEquals("""{"14/8700/5799":"v1","14/8700/5800":"v2","9/1/2":"2026-10-01"}""", text)
        assertEquals(cells, RegionStorage.decodeAddressCells(text))
    }

    @Test
    fun `la lettura ignora spazi e a capo attorno a chiavi e valori`() {
        val text = "{\n  \"a/1/2\" : \"v1\" ,\n  \"b/3/4\" : \"v2\"\n}\n"

        assertEquals(mapOf("a/1/2" to "v1", "b/3/4" to "v2"), RegionStorage.decodeAddressCells(text))
    }

    @Test
    fun `i due punti nel valore restano nel valore`() {
        assertEquals(mapOf("a/1/2" to "2026-10-01T10:30"), RegionStorage.decodeAddressCells("""{"a/1/2":"2026-10-01T10:30"}"""))
    }

    @Test
    fun `una voce senza due punti si salta e le altre restano`() {
        assertEquals(emptyMap<String, String>(), RegionStorage.decodeAddressCells("""{"a/1/2"}"""))
        assertEquals(mapOf("b/3/4" to "v2"), RegionStorage.decodeAddressCells("""{"a/1/2","b/3/4":"v2"}"""))
        assertEquals(emptyMap<String, String>(), RegionStorage.decodeAddressCells("not json"))
    }

    @Test
    fun `un file delle celle danneggiato non fa fallire la lettura delle celle installate`() {
        val storage = storage()
        File(storage.directoryFor("italia"), RegionStorage.ADDRESSES_CELLS_FILE).apply {
            parentFile?.mkdirs()
            writeText("""{"a/1/2","b/3/4":"v2"}""")
        }

        assertEquals(mapOf("b/3/4" to "v2"), storage.installedAddressCells("italia"))
    }

    @Test
    fun `le celle installate sono vuote senza il file e lette dal file con`() {
        val storage = storage()
        assertEquals(emptyMap<String, String>(), storage.installedAddressCells("italia"))

        File(storage.directoryFor("italia"), RegionStorage.ADDRESSES_CELLS_FILE).apply {
            parentFile?.mkdirs()
            writeText(RegionStorage.encodeAddressCells(mapOf("14/1/2" to "v7")))
        }

        assertEquals(mapOf("14/1/2" to "v7"), storage.installedAddressCells("italia"))
        assertEquals(emptyMap<String, String>(), storage.installedAddressCells("francia"))
    }

    // File di ricerca degli indirizzi

    @Test
    fun `il file di ricerca di una cella ha l'id senza barre`() {
        assertEquals("14-8700-5800.db", RegionStorage.addressSearchFileName("14/8700/5800"))
        assertEquals("14-8700-5800.db.xz", RegionStorage.addressSearchArchiveName("14/8700/5800"))
        assertEquals("senza-barre.db", RegionStorage.addressSearchFileName("senza-barre"))
        assertEquals(".db", RegionStorage.addressSearchFileName(""))
    }

    @Test
    fun `senza cartella di ricerca non ci sono file ne database unico`() {
        val storage = storage()

        assertEquals(emptyList<File>(), storage.addressSearchFiles("italia"))
        assertNull(storage.addressSearchDb("italia"))
    }

    @Test
    fun `i file di ricerca sono i db e gli xz delle celle in ordine di nome, senza il database unico`() {
        val storage = storage()
        val dir = File(storage.directoryFor("italia"), RegionStorage.ADDRESSES_SEARCH_DIR).apply { mkdirs() }
        listOf("b.db.xz", "a.db", RegionStorage.ADDRESSES_SEARCH_DB, "note.txt", "e.xz", "senza-estensione").forEach { File(dir, it).writeText("x") }
        File(dir, "cartella.db").mkdirs()

        assertEquals(listOf("a.db", "b.db.xz", "e.xz"), storage.addressSearchFiles("italia").map { it.name })
    }

    @Test
    fun `il database unico c'e' solo se e' un file`() {
        val storage = storage()
        val dir = File(storage.directoryFor("italia"), RegionStorage.ADDRESSES_SEARCH_DIR).apply { mkdirs() }

        File(dir, RegionStorage.ADDRESSES_SEARCH_DB).mkdirs()
        assertNull("una cartella non e' il database", storage.addressSearchDb("italia"))

        File(dir, RegionStorage.ADDRESSES_SEARCH_DB).deleteRecursively()
        File(dir, RegionStorage.ADDRESSES_SEARCH_DB).writeText("db")
        assertEquals(RegionStorage.ADDRESSES_SEARCH_DB, storage.addressSearchDb("italia")?.name)
    }

    // Controlli sugli argomenti

    @Test
    fun `un pacchetto sconosciuto o uno staging mancante non si attiva`() {
        val storage = storage()
        val staged = File(tempFolder.root, "staged.bin").apply { writeText("x") }

        assertThrows(IllegalArgumentException::class.java) { storage.activatePackage("italia", "altro.bin", staged, "v1") }
        assertThrows(IllegalArgumentException::class.java) {
            storage.activatePackage("italia", RegionStorage.MAP_FILE, File(tempFolder.root, "non-esiste"), "v1")
        }
        assertFalse("nessuna cartella creata dall'attivazione rifiutata", storage.directoryFor("italia").exists())
    }

    @Test
    fun `deletePackage rifiuta un nome sconosciuto e un pacchetto non installato conta come eliminato`() {
        val storage = storage()

        assertThrows(IllegalArgumentException::class.java) { storage.deletePackage("italia", "poi.db") }
        assertTrue(storage.deletePackage("italia", RegionStorage.MAP_FILE))
        assertTrue(storage.deletePackage("italia", RegionStorage.ROUTING_DIR))
    }

    @Test
    fun `versionedPmtiles vale solo per gli archivi PMTiles`() {
        val storage = storage()

        listOf(RegionStorage.ROUTING_DIR, RegionStorage.ADDRESSES_CELLS_FILE, RegionStorage.TRANSIT_DIR, "altro.pmtiles").forEach { name ->
            assertThrows(name, IllegalArgumentException::class.java) { storage.versionedPmtiles("italia", name) }
        }
    }

    @Test
    fun `regione e versione con separatori o punti non entrano nello staging`() {
        val storage = storage()

        assertThrows(IllegalArgumentException::class.java) { storage.stagingDirectoryFor("italia", "..") }
        assertThrows(IllegalArgumentException::class.java) { storage.stagingDirectoryFor("italia", "v1/../..") }
        assertThrows(IllegalArgumentException::class.java) { storage.stagingDirectoryFor("a\\b", "v1") }
        assertThrows(IllegalArgumentException::class.java) { storage.stagingDirectoryFor("", "v1") }
        assertThrows(IllegalArgumentException::class.java) { storage.deleteStaging("..") }
        assertThrows(IllegalArgumentException::class.java) { storage.cleanupStagingExcept("a/b", "v1") }
    }

    @Test
    fun `pulire lo staging di una regione che non ne ha non fa nulla`() {
        val storage = storage()

        storage.cleanupStagingExcept("nuova", "v1")
        storage.deleteStaging("nuova")

        assertEquals(emptyList<String>(), storage.stagingIds())
    }

    @Test
    fun `lo staging di una versione e' dentro quello della regione`() {
        val storage = storage()

        val staged = storage.stagingDirectoryFor("italia", "v1")

        assertEquals("v1", staged.name)
        assertEquals("italia", staged.parentFile?.name)
        assertEquals(File(tempFolder.root, "staging").canonicalFile, staged.parentFile?.parentFile)
    }

    // Spazio

    @Test
    fun `senza StorageManager lo spazio assegnabile e' quello libero e riservare non fa nulla`() {
        val dir = File(tempFolder.root, "regions").apply { mkdirs() }

        // Lo spazio libero cambia mentre il test gira: si confronta con una tolleranza.
        val difference = kotlin.math.abs(dir.allocatableBytes(null) - dir.usableSpace)
        assertTrue("differenza $difference byte", difference < 64L * 1024 * 1024)

        dir.reserveSpace(null, 1_000_000)
        dir.reserveSpace(null, 0)
        dir.reserveSpace(null, -5)
        storage().reserveSpace(1_000_000)
    }
}
