package com.pockettravel.feature.ai

import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFailureGateTest {

    @Test
    fun `un FAILED visto alla riapertura senza un avvio non si mostra`() {
        assertFalse(DownloadFailureGate().reportsFailure(WorkInfo.State.FAILED))
    }

    @Test
    fun `un FAILED dopo un avvio visto si mostra, una volta sola`() {
        val gate = DownloadFailureGate()
        assertFalse(gate.reportsFailure(WorkInfo.State.ENQUEUED))
        assertFalse(gate.reportsFailure(WorkInfo.State.RUNNING))
        assertTrue(gate.reportsFailure(WorkInfo.State.FAILED))
        assertFalse(gate.reportsFailure(WorkInfo.State.FAILED))
    }

    @Test
    fun `un FAILED dopo un annullamento non si mostra`() {
        val gate = DownloadFailureGate()
        gate.reportsFailure(WorkInfo.State.RUNNING)
        gate.reportsFailure(WorkInfo.State.CANCELLED)
        assertFalse(gate.reportsFailure(WorkInfo.State.FAILED))
    }
}
