package com.pockettravel.core.sync

import com.pockettravel.core.data.PackageKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPackageDownloadTest {

    @Test
    fun `senza notifica in primo piano i primi tentativi proseguono e poi ci si arrende`() {
        val kinds = setOf(PackageKind.MAP, PackageKind.POI)
        assertFalse(RegionPackageDownload.givesUpWithoutForeground(0, kinds))
        assertFalse(RegionPackageDownload.givesUpWithoutForeground(1, kinds))
        assertTrue(RegionPackageDownload.givesUpWithoutForeground(2, kinds))
        assertTrue(RegionPackageDownload.givesUpWithoutForeground(10, kinds))
    }

    @Test
    fun `senza la mappa tra i pacchetti non ci si arrende mai`() {
        assertFalse(RegionPackageDownload.givesUpWithoutForeground(10, setOf(PackageKind.POI, PackageKind.ROUTING)))
    }

    @Test
    fun `un blocco della rete non toglie lo staging, un fallimento definitivo si`() {
        assertTrue(RegionPackageDownload.keepsStaging(DownloadOutcome.RETRY))
        assertTrue(RegionPackageDownload.keepsStaging(DownloadOutcome.DATA_SAVER_BLOCKED))
        assertTrue(RegionPackageDownload.keepsStaging(DownloadOutcome.NETWORK_BLOCKED))
        assertFalse(RegionPackageDownload.keepsStaging(DownloadOutcome.FAILURE))
        assertFalse(RegionPackageDownload.keepsStaging(DownloadOutcome.SUCCESS))
    }
}
