package com.pockettravel.app.regions

import androidx.work.Data
import androidx.work.WorkInfo
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RegionDownloadUiTest {
    private fun work(state: WorkInfo.State, progress: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), progress = progress)

    private fun progress(block: Data.Builder.() -> Unit) = Data.Builder().apply(block).build()

    @Test
    fun `senza lavoro non c'e' nessun download`() {
        val none: WorkInfo? = null

        assertEquals(RegionDownloadUi(isDownloading = false, installing = false, extractingMap = false, progress = 0f), none.downloadUi())
    }

    @Test
    fun `in coda o in corso e' un download, finito o fallito no`() {
        assertTrue(work(WorkInfo.State.ENQUEUED).downloadUi().isDownloading)
        assertTrue(work(WorkInfo.State.RUNNING).downloadUi().isDownloading)
        assertFalse(work(WorkInfo.State.SUCCEEDED).downloadUi().isDownloading)
        assertFalse(work(WorkInfo.State.FAILED).downloadUi().isDownloading)
        assertFalse(work(WorkInfo.State.CANCELLED).downloadUi().isDownloading)
    }

    @Test
    fun `la fase dei file avanza coi byte scaricati sul totale`() {
        val data = progress {
            putLong(RegionPackageDownloadWorker.KEY_TOTAL_BYTES, 200L)
            putLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 50L)
        }

        val ui = work(WorkInfo.State.RUNNING, data).downloadUi()

        assertEquals(0.25f, ui.progress, 0.0001f)
        assertFalse(ui.extractingMap)
        assertFalse(ui.installing)
    }

    @Test
    fun `senza il totale dei byte l'avanzamento resta a zero`() {
        val data = progress { putLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 50L) }

        assertEquals(0f, work(WorkInfo.State.RUNNING, data).downloadUi().progress, 0f)
    }

    @Test
    fun `con i byte della mappa l'avanzamento e' quello dell'estrazione, non dei file`() {
        val data = progress {
            putLong(RegionPackageDownloadWorker.KEY_TOTAL_BYTES, 200L)
            putLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 200L)
            putLong(RegionPackageDownloadWorker.KEY_MAP_BYTES_TOTAL, 1_000L)
            putLong(RegionPackageDownloadWorker.KEY_MAP_BYTES_DONE, 100L)
        }

        val ui = work(WorkInfo.State.RUNNING, data).downloadUi()

        assertTrue(ui.extractingMap)
        assertEquals(0.1f, ui.progress, 0.0001f)
    }

    @Test
    fun `l'installazione si segnala dal flag del lavoro`() {
        val data = progress { putBoolean(RegionPackageDownloadWorker.KEY_INSTALLING, true) }

        assertTrue(work(WorkInfo.State.RUNNING, data).downloadUi().installing)
    }
}
