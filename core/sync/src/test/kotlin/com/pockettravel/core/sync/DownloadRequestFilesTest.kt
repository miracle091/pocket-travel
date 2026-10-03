package com.pockettravel.core.sync

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadRequestFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `una voce oltre i 10 KB non entra nei dati di lavoro, ma passa da un file`() {
        val bigEntry = "x".repeat(30_000)
        assertTrue(runCatching { Data.Builder().putString("entry", bigEntry).build() }.isFailure)

        val files = DownloadRequestFiles(tmp.root)
        val name = files.write(bigEntry)
        Data.Builder().putString("entry_file", name).build()
        assertEquals(bigEntry, files.read(name))

        files.delete(name)
        assertNull(files.read(name))
    }

    @Test
    fun `i file vecchi di lavori mai partiti si tolgono alla richiesta successiva`() {
        val files = DownloadRequestFiles(tmp.root)
        val old = files.write("{}")
        val now = File(tmp.root, old).lastModified() + DownloadRequestFiles.MAX_AGE_MILLIS + 1
        val recent = files.write("{}", nowMillis = now)
        assertFalse(File(tmp.root, old).exists())
        assertTrue(File(tmp.root, recent).exists())
    }
}
