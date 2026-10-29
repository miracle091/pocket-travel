package com.pockettravel.core.sync

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

// Quale download di una regione seguono le schermate quando ce n'e' piu' d'uno.
class CurrentDownloadTest {

    private fun work(state: WorkInfo.State, enqueuedAt: Long?) =
        WorkInfo(UUID.randomUUID(), state, setOfNotNull("region-download", "region-download:italia", enqueuedAt?.let { ENQUEUED_AT_TAG + it }))

    @Test
    fun `senza download non c'e' niente da seguire`() {
        assertNull(currentDownload(emptyList()))
    }

    @Test
    fun `uno in corso vince su quelli finiti`() {
        val running = work(WorkInfo.State.RUNNING, 1)
        assertEquals(running, currentDownload(listOf(work(WorkInfo.State.SUCCEEDED, 2), running)))
    }

    @Test
    fun `tra quelli finiti l'ultimo accodato, in qualunque ordine arrivino`() {
        val failed = work(WorkInfo.State.FAILED, 1_000)
        val succeeded = work(WorkInfo.State.SUCCEEDED, 2_000)
        assertEquals(succeeded, currentDownload(listOf(succeeded, failed)))
        assertEquals(succeeded, currentDownload(listOf(failed, succeeded)))
    }

    @Test
    fun `un download senza l'ora di accodamento conta come il piu' vecchio`() {
        val older = work(WorkInfo.State.FAILED, null)
        val newer = work(WorkInfo.State.SUCCEEDED, 5)
        assertEquals(newer, currentDownload(listOf(newer, older)))
    }
}
