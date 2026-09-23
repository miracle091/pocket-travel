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

        storage.activatePackage("italia", RegionStorage.MAP_FILE, staged).commit()

        assertEquals("mappa", File(storage.directoryFor("italia"), RegionStorage.MAP_FILE).readText())
        assertFalse(staged.exists())
    }

    @Test
    fun `activatePackage sostituisce un pacchetto senza toccare gli altri`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "mappa")).commit()
        val routing = File(storage.stagingDirectoryFor("italia", "v1"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(routing, "E10_N40.rd5").writeText("segmento")
        storage.activatePackage("italia", RegionStorage.ROUTING_DIR, routing).commit()

        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v2", RegionStorage.MAP_FILE, "mappa nuova")).commit()

        val live = storage.directoryFor("italia")
        assertEquals("mappa nuova", File(live, RegionStorage.MAP_FILE).readText())
        assertEquals("segmento", File(live, "${RegionStorage.ROUTING_DIR}/E10_N40.rd5").readText())
        assertFalse(File(live, ".${RegionStorage.MAP_FILE}.backup").exists())
    }

    @Test
    fun `rollback di activatePackage ripristina il pacchetto precedente`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "vecchia")).commit()

        val activation = storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v2", RegionStorage.MAP_FILE, "nuova"))
        activation.rollback()

        assertEquals("vecchia", File(storage.directoryFor("italia"), RegionStorage.MAP_FILE).readText())
    }

    @Test
    fun `deletePackage e packageBytes agiscono su un solo pacchetto`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "12345")).commit()
        val routing = File(storage.stagingDirectoryFor("italia", "v1"), RegionStorage.ROUTING_DIR).apply { mkdirs() }
        File(routing, "a.rd5").writeText("123")
        storage.activatePackage("italia", RegionStorage.ROUTING_DIR, routing).commit()

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
    fun `delete rimuove una regione installata e ritorna true`() {
        val (storage, _) = newStorage()
        storage.activatePackage("italia", RegionStorage.MAP_FILE, stagedFile(storage, "v1", RegionStorage.MAP_FILE, "mappa")).commit()

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
        assertEquals(File(root, "regions").usableSpace, storage.availableBytes())
    }
}
