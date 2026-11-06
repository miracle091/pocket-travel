package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CityPackageCopiesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val storage by lazy { RegionStorage(tmp.newFolder("regions"), tmp.newFolder("staging")) }
    private val copies by lazy { CityPackageCopies(storage) }

    private fun downloaded(name: String, bytes: Int) = File(tmp.root, name).apply { writeBytes(ByteArray(bytes)) }

    private fun manifestFile(sha: Char, bytes: Int) = RegionManifestFile("cities.db.xz", "https://example.org/cities.db.xz", bytes.toLong(), sha.toString().repeat(64))

    @Test
    fun `la copia tenuta si ritrova per regione e SHA-256, non per un'altra regione`() {
        val italian = manifestFile('a', 10)
        storage.directoryFor("corsica").mkdirs()

        copies.keep("corsica", italian, downloaded("it.xz", 10))

        assertTrue(copies.has("corsica", italian))
        assertFalse(copies.has("corsica", manifestFile('b', 10)))
        assertFalse(copies.has("malta", italian))
    }

    @Test
    fun `una copia con la dimensione sbagliata non vale`() {
        storage.directoryFor("corsica").mkdirs()
        copies.keep("corsica", manifestFile('a', 10), downloaded("it.xz", 10))

        assertFalse(copies.has("corsica", manifestFile('a', 11)))
    }

    @Test
    fun `oltre il tetto si tolgono le copie usate meno di recente, in tutte le regioni`() {
        val half = (CityPackageCopies.MAX_BYTES / 2).toInt()
        val oldest = manifestFile('a', half)
        val middle = manifestFile('b', half)
        val newest = manifestFile('c', half)
        listOf("corsica", "malta").forEach { storage.directoryFor(it).mkdirs() }

        copies.keep("corsica", oldest, downloaded("1", half))
        copies.fileFor("corsica", oldest).setLastModified(1_000)
        copies.keep("malta", middle, downloaded("2", half))
        copies.fileFor("malta", middle).setLastModified(2_000)
        copies.keep("malta", newest, downloaded("3", half))

        assertEquals(listOf(false, true, true), listOf(copies.has("corsica", oldest), copies.has("malta", middle), copies.has("malta", newest)))
    }
}
