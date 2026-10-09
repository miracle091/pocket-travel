package com.pockettravel.app.regions

import androidx.work.WorkInfo
import com.pockettravel.feature.map.TransitUpdateResult
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitUpdateTest {
    private val requested = TransitUpdate.Requested(emptyFlow())

    @Test
    fun `scaricamento mentre si legge il catalogo e finche' il download non finisce`() {
        assertTrue(isTransitUpdating(TransitUpdate.Checking, null))
        assertTrue(isTransitUpdating(requested, null))
        assertTrue(isTransitUpdating(requested, WorkInfo.State.ENQUEUED))
        assertTrue(isTransitUpdating(requested, WorkInfo.State.RUNNING))
        assertFalse(isTransitUpdating(requested, WorkInfo.State.SUCCEEDED))
        assertFalse(isTransitUpdating(requested, WorkInfo.State.FAILED))
        assertFalse(isTransitUpdating(TransitUpdate.UpToDate, null))
        assertFalse(isTransitUpdating(TransitUpdate.Failed, null))
        assertFalse(isTransitUpdating(null, null))
    }

    @Test
    fun `messaggio se non c'e' una versione piu' recente o se qualcosa fallisce`() {
        assertEquals(TransitUpdateResult.UP_TO_DATE, transitUpdateResultOf(TransitUpdate.UpToDate, null))
        assertEquals(TransitUpdateResult.FAILED, transitUpdateResultOf(TransitUpdate.Failed, null))
        assertEquals(TransitUpdateResult.FAILED, transitUpdateResultOf(requested, WorkInfo.State.FAILED))
    }

    @Test
    fun `nessun messaggio durante il download o dopo che e' riuscito`() {
        assertNull(transitUpdateResultOf(null, null))
        assertNull(transitUpdateResultOf(TransitUpdate.Checking, null))
        assertNull(transitUpdateResultOf(requested, WorkInfo.State.RUNNING))
        assertNull(transitUpdateResultOf(requested, WorkInfo.State.SUCCEEDED))
        assertNull(transitUpdateResultOf(requested, WorkInfo.State.CANCELLED))
    }
}
