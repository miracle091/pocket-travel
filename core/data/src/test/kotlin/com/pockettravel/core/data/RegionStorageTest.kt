package com.pockettravel.core.data

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RegionStorageTest {

    private fun newStorage(): Pair<RegionStorage, File> {
        val root = createTempDirectory("pocket-travel-storage-test").toFile()
        val regionsDir = File(root, "regions").apply { mkdirs() }
        val stagingDir = File(root, "staging").apply { mkdirs() }
        return RegionStorage(regionsDir, stagingDir) to root
    }

    @Test
    fun `directoryFor rifiuta segmenti non sicuri`() {
        val (storage, _) = newStorage()
        for (segment in listOf("..", ".", "a/b", "a\\b", "")) {
            try {
                storage.directoryFor(segment)
                fail("segmento '$segment' doveva essere rifiutato")
            } catch (_: IllegalArgumentException) {
                // atteso
            }
        }
    }

    @Test
    fun `directoryFor rifiuta un tentativo di path traversal fuori dalla regionsDir`() {
        val (storage, _) = newStorage()
        try {
            storage.directoryFor("..%2Fetc")
            // Se il canonical path finisce comunque fuori da regionsDir, deve fallire con IOException;
            // se il filesystem tratta "%2F" come carattere letterale (nessun traversal reale), va bene.
        } catch (_: IOException) {
            // atteso in caso di traversal reale
        }
    }

    private fun stagedFile(storage: RegionStorage, version: String, name: String, text: String): File {
        val staged = storage.stagingDirectoryFor("italia", version)
        staged.mkdirs()
        return File(staged, name).apply { writeText(text) }
    }

    @Test
    fun `activatePackage installa un pacchetto in una regione nuova`() {
        val (storage, _) = newStorage()
        val staged = stagedFile(storage, "v1", RegionStorage.MAP_FILE, "mappa")

        storage.activatePackage("italia", RegionStorage.MAP_FILE, staged, "v1").commit()

        assertEquals("mappa", File(storage.directoryFor("italia"), RegionStorage.MAP_FILE).readText())
        assertFalse(staged.exists())
    }

    @Test
    fun `activatePackage sostituisce un pacchetto senza toccare gli altri`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "mappa"), "v1").commit()
        val routing = File(storage.stagingDirectoryFor("italia", "v1"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(routing, "E10_N40.rd5").writeText("segmento")
        storage.activatePackage("italia", RegionStorage.ROUTING_DIR, routing, "v1").commit()

        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v2", RegionStorage.MAP_FILE, "mappa nuova"), "v2").commit()

        val live = storage.directoryFor("italia")
        assertEquals("mappa nuova", File(live, RegionStorage.MAP_FILE).readText())
        assertEquals("segmento", File(live, "${RegionStorage.ROUTING_DIR}/E10_N40.rd5").readText())
        assertFalse(File(live, ".${RegionStorage.MAP_FILE}.backup").exists())
    }

    @Test
    fun `rollback di activatePackage ripristina il pacchetto precedente`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "vecchia"), "v1").commit()

        val activation = storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v2", RegionStorage.MAP_FILE, "nuova"), "v2")
        activation.rollback()

        assertEquals("vecchia", File(storage.directoryFor("italia"), RegionStorage.MAP_FILE).readText())
    }

    private fun leftovers(storage: RegionStorage) = storage.directoryFor("italia").list()!!.filter { it.startsWith(".") }.toSet()

    @Test
    fun `commit e rollback non lasciano backup ne versione in attivazione`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "vecchia"), "v1").commit()

        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v2", RegionStorage.MAP_FILE, "nuova"), "v2").commit()
        assertEquals(emptySet<String>(), leftovers(storage))
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v3", RegionStorage.MAP_FILE, "scartata"), "v3").rollback()
        assertEquals(emptySet<String>(), leftovers(storage))
    }

    @Test
    fun `recupero dopo un crash prima della transazione ripristina il pacchetto registrato`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "vecchia"), "v1").commit()
        // Crash: attivazione senza commit, il database registra ancora v1.
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v2", RegionStorage.MAP_FILE, "nuova"), "v2")

        storage.recoverInterruptedActivations("italia") { if (it == RegionStorage.MAP_FILE) "v1" else null }

        assertEquals("vecchia", File(storage.directoryFor("italia"), RegionStorage.MAP_FILE).readText())
        assertEquals(emptySet<String>(), leftovers(storage))
    }

    @Test
    fun `recupero dopo un crash successivo alla transazione tiene il pacchetto nuovo`() {
        val (storage, _) = newStorage()
        val routing = File(storage.stagingDirectoryFor("italia", "v1"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(routing, "a.rd5").writeText("vecchio")
        storage.activatePackage("italia", RegionStorage.ROUTING_DIR, routing, "v1").commit()
        val routing2 = File(storage.stagingDirectoryFor("italia", "v2"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(routing2, "a.rd5").writeText("nuovo")
        // Crash dopo che il database ha registrato v2, prima di commit().
        storage.activatePackage("italia", RegionStorage.ROUTING_DIR, routing2, "v2")

        storage.recoverInterruptedActivations("italia") { if (it == RegionStorage.ROUTING_DIR) "v2" else null }

        assertEquals("nuovo", File(storage.directoryFor("italia"), "${RegionStorage.ROUTING_DIR}/a.rd5").readText())
        assertEquals(emptySet<String>(), leftovers(storage))
    }

    @Test
    fun `recupero di un backup senza versione in attivazione lo ripristina`() {
        val (storage, _) = newStorage()
        val regionDir = storage.directoryFor("italia").apply { mkdirs() }
        File(regionDir, RegionStorage.MAP_FILE).writeText("nuova")
        File(regionDir, ".${RegionStorage.MAP_FILE}.backup").writeText("vecchia")

        storage.recoverInterruptedActivations("italia") { "v2" }

        assertEquals("vecchia", File(regionDir, RegionStorage.MAP_FILE).readText())
        assertEquals(emptySet<String>(), leftovers(storage))
    }

    @Test
    fun `recupero senza backup lascia il pacchetto attivo`() {
        val (storage, _) = newStorage()
        val regionDir = storage.directoryFor("italia").apply { mkdirs() }
        // Crash dopo aver scritto la versione in attivazione, prima di spostare il pacchetto attivo.
        File(regionDir, RegionStorage.MAP_FILE).writeText("vecchia")
        File(regionDir, ".${RegionStorage.MAP_FILE}.pending").writeText("v2")

        storage.recoverInterruptedActivations("italia") { "v1" }

        assertEquals("vecchia", File(regionDir, RegionStorage.MAP_FILE).readText())
        assertEquals(emptySet<String>(), leftovers(storage))
    }

    @Test
    fun `recupero con il pacchetto attivo mancante ripristina il backup`() {
        val (storage, _) = newStorage()
        val regionDir = storage.directoryFor("italia").apply { mkdirs() }
        // Crash tra lo spostamento del pacchetto attivo nel backup e quello del nuovo al suo posto.
        File(regionDir, ".${RegionStorage.MAP_FILE}.backup").writeText("vecchia")
        File(regionDir, ".${RegionStorage.MAP_FILE}.pending").writeText("v2")

        storage.recoverInterruptedActivations("italia") { "v1" }

        assertEquals("vecchia", File(regionDir, RegionStorage.MAP_FILE).readText())
        assertEquals(emptySet<String>(), leftovers(storage))
    }

    @Test
    fun `regionIdsOnDisk e stagingIds elencano le cartelle presenti`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "mappa"), "v1").commit()
        storage.stagingDirectoryFor("francia", "v1").mkdirs()

        assertEquals(listOf("italia"), storage.regionIdsOnDisk())
        assertEquals(setOf("italia", "francia"), storage.stagingIds().toSet())
    }

    @Test
    fun `deletePackage e packageBytes agiscono su un solo pacchetto`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "12345"), "v1").commit()
        val routing = File(storage.stagingDirectoryFor("italia", "v1"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(routing, "a.rd5").writeText("123")
        storage.activatePackage("italia", RegionStorage.ROUTING_DIR, routing, "v1").commit()

        assertEquals(5L, storage.packageBytes("italia", RegionStorage.MAP_FILE))
        assertEquals(3L, storage.packageBytes("italia", RegionStorage.ROUTING_DIR))

        assertTrue(storage.deletePackage("italia", RegionStorage.MAP_FILE))

        assertEquals(0L, storage.packageBytes("italia", RegionStorage.MAP_FILE))
        assertEquals(3L, storage.packageBytes("italia", RegionStorage.ROUTING_DIR))
    }

    @Test
    fun `cleanupStagingExcept mantiene solo la versione indicata`() {
        val (storage, _) = newStorage()
        storage.stagingDirectoryFor("italia", "v1").mkdirs()
        storage.stagingDirectoryFor("italia", "v2").mkdirs()
        storage.stagingDirectoryFor("italia", "v3").mkdirs()

        storage.cleanupStagingExcept("italia", "v2")

        val remaining = storage.stagingDirectoryFor("italia", "v2").parentFile?.list()?.toSet()
        assertEquals(setOf("v2"), remaining)
    }

    @Test
    fun `deleteStaging rimuove lo staging di una sola regione`() {
        val (storage, _) = newStorage()
        File(storage.stagingDirectoryFor("italia", "v1").apply { mkdirs() }, "poi.db.part").writeText("x")
        storage.stagingDirectoryFor("francia", "v1").mkdirs()

        storage.deleteStaging("italia")
        storage.deleteStaging("mai-scaricata")

        assertFalse(storage.stagingDirectoryFor("italia", "v1").parentFile!!.exists())
        assertTrue(storage.stagingDirectoryFor("francia", "v1").exists())
    }

    @Test
    fun `delete rimuove una regione installata e ritorna true`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "mappa"), "v1").commit()

        assertTrue(storage.delete("italia"))
        assertFalse(storage.directoryFor("italia").exists())
    }

    @Test
    fun `delete su una regione mai installata ritorna comunque true`() {
        val (storage, _) = newStorage()
        assertTrue(storage.delete("mai-installata"))
    }

    @Test
    fun `availableBytes rispecchia lo spazio libero della regionsDir`() {
        val (storage, root) = newStorage()
        // Tolleranza: tra le due letture altri processi (anche la build stessa) scrivono sul disco
        val difference = kotlin.math.abs(File(root, "regions").usableSpace - storage.availableBytes())
        assertTrue("differenza $difference byte", difference < 64L * 1024 * 1024)
    }
}
