package com.pockettravel.app.regions

import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageCellularWarningTest {

    private fun pkg(kind: PackageKind, bytes: Long) =
        PackageUiState(kind = kind, status = RegionStatus.NOT_INSTALLED, downloadBytes = bytes, installedBytes = null)

    @Test
    fun `la mappa avvisa anche con peso zero`() {
        assertTrue(pkg(PackageKind.MAP, 0L).warnsOnCellular())
    }

    @Test
    fun `un pacchetto grande avvisa e uno piccolo no`() {
        assertTrue(pkg(PackageKind.ROUTING, LARGE_DOWNLOAD_WARNING_BYTES + 1).warnsOnCellular())
        assertFalse(pkg(PackageKind.POI, LARGE_DOWNLOAD_WARNING_BYTES).warnsOnCellular())
    }
}
