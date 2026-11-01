package com.pockettravel.core.sync

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class GuidesSyncPolicyTest {

    @Test
    fun `in automatico con la stessa rete si tiene il lavoro in coda`() {
        assertEquals(ExistingWorkPolicy.KEEP, guidesSyncPolicy(true, NetworkType.UNMETERED, NetworkType.UNMETERED))
    }

    @Test
    fun `in automatico senza lavoro in coda si accoda`() {
        assertEquals(ExistingWorkPolicy.KEEP, guidesSyncPolicy(true, NetworkType.CONNECTED, null))
    }

    @Test
    fun `in automatico con una rete diversa si sostituisce il lavoro in coda`() {
        assertEquals(ExistingWorkPolicy.REPLACE, guidesSyncPolicy(true, NetworkType.CONNECTED, NetworkType.UNMETERED))
        assertEquals(ExistingWorkPolicy.REPLACE, guidesSyncPolicy(true, NetworkType.UNMETERED, NetworkType.CONNECTED))
    }

    @Test
    fun `su richiesta dell'utente si sostituisce sempre`() {
        assertEquals(ExistingWorkPolicy.REPLACE, guidesSyncPolicy(false, NetworkType.CONNECTED, NetworkType.CONNECTED))
    }
}
