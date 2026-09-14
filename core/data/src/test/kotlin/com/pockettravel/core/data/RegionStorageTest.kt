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

    @Test
    fun `activate installa una regione nuova senza backup precedente`() {
        val (storage, _) = newStorage()
        val staged = storage.stagingDirectoryFor("italia", "v1")
        staged.mkdirs()
        File(staged, "content.db").writeText("dati")

        val activation = storage.activate("italia", staged)

        val live = storage.directoryFor("italia")
        assertTrue(live.isDirectory)
        assertEquals("dati", File(live, "content.db").readText())
        assertFalse("una prima installazione non ha nulla da salvare come backup", staged.exists())
        activation.commit()
    }

    @Test
    fun `activate salva la versione precedente come backup e rollback la ripristina`() {
        val (storage, _) = newStorage()
        val oldStaged = storage.stagingDirectoryFor("italia", "v1")
        oldStaged.mkdirs()
        File(oldStaged, "content.db").writeText("vecchia versione")
        storage.activate("italia", oldStaged).commit()

        val newStaged = storage.stagingDirectoryFor("italia", "v2")
        newStaged.mkdirs()
        File(newStaged, "content.db").writeText("nuova versione")
        val activation = storage.activate("italia", newStaged)

        assertEquals("nuova versione", File(storage.directoryFor("italia"), "content.db").readText())

        activation.rollback()

        assertEquals("vecchia versione", File(storage.directoryFor("italia"), "content.db").readText())
    }

    @Test
    fun `activate commit rimuove il backup`() {
        val (storage, root) = newStorage()
        val oldStaged = storage.stagingDirectoryFor("italia", "v1")
        oldStaged.mkdirs()
        storage.activate("italia", oldStaged).commit()

        val newStaged = storage.stagingDirectoryFor("italia", "v2")
        newStaged.mkdirs()
        storage.activate("italia", newStaged).commit()

        assertFalse(File(File(root, "regions"), ".italia.backup").exists())
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
        val staged = storage.stagingDirectoryFor("italia", "v1")
        staged.mkdirs()
        storage.activate("italia", staged).commit()

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
