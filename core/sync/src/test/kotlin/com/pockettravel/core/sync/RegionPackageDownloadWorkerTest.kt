package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPackageDownloadWorkerTest {

    @Test
    fun `senza notifica in primo piano i primi tentativi proseguono e poi ci si arrende`() {
        val kinds = setOf(PackageKind.MAP, PackageKind.POI)
        assertFalse(RegionPackageDownloadWorker.givesUpWithoutForeground(0, kinds))
        assertFalse(RegionPackageDownloadWorker.givesUpWithoutForeground(1, kinds))
        assertTrue(RegionPackageDownloadWorker.givesUpWithoutForeground(2, kinds))
        assertTrue(RegionPackageDownloadWorker.givesUpWithoutForeground(10, kinds))
    }

    @Test
    fun `senza la mappa tra i pacchetti non ci si arrende mai`() {
        assertFalse(RegionPackageDownloadWorker.givesUpWithoutForeground(10, setOf(PackageKind.POI, PackageKind.ROUTING)))
    }
}
