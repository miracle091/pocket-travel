package com.pockettravel.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPackageDownloadWorkerTest {

    @Test
    fun `senza notifica in primo piano i primi tentativi proseguono e poi ci si arrende`() {
        assertFalse(RegionPackageDownloadWorker.givesUpWithoutForeground(0))
        assertFalse(RegionPackageDownloadWorker.givesUpWithoutForeground(1))
        assertTrue(RegionPackageDownloadWorker.givesUpWithoutForeground(2))
        assertTrue(RegionPackageDownloadWorker.givesUpWithoutForeground(10))
    }
}
