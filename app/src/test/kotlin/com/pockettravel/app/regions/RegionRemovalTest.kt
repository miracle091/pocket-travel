package com.pockettravel.app.regions

import com.pockettravel.app.R
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionRemovalTest {
    private val files = InMemoryPreferencesFactory()
    private val repository = FakeRegionRepository()
    private val scheduler = FakeRegionSyncScheduler()
    private val zones = RegionZonePreferences(files)
    private val recent = RecentRegionStore(files)

    private fun removal(regions: RegionRepository = repository, testScheduler: TestCoroutineScheduler) =
        RegionRemoval(regions, scheduler, zones, recent, StandardTestDispatcher(testScheduler))

    private fun installed(map: String? = "m1", routing: String? = "r1") = InstalledRegion(
        "italia", "Italia", "it", mapVersion = map, routingVersion = routing, poiVersion = null, poiExtraVersion = null, addressesVersion = null,
        poiSizeBytes = null, poiExtraSizeBytes = null, sizeBytes = 100L,
    )

    @Test
    fun `eliminare la regione ferma il download, toglie la zona e l'ultima aperta, e non da errore`() = runTest {
        repository.installedRegions.value = listOf(installed())
        zones.setZone("italia", RegionZone(8.0, 40.0, 10.0, 42.0))
        recent.setLastRegionId("italia")

        val error = removal(testScheduler = testScheduler).remove("italia")

        assertNull(error)
        assertEquals(listOf("italia"), scheduler.cancelled)
        assertNull(zones.zone("italia"))
        assertNull(recent.lastRegionId())
        assertTrue(repository.installedRegions.value.isEmpty())
    }

    @Test
    fun `l'ultima regione aperta resta finche' resta un pacchetto`() = runTest {
        repository.installedRegions.value = listOf(installed())
        recent.setLastRegionId("italia")
        val removal = removal(testScheduler = testScheduler)

        assertNull(removal.removePackage("italia", PackageKind.MAP))
        assertEquals("italia", recent.lastRegionId())

        assertNull(removal.removePackage("italia", PackageKind.ROUTING))
        assertNull(recent.lastRegionId())
    }

    @Test
    fun `se i file non si eliminano torna il messaggio d'errore`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val removal = removal(FailingRemovals(repository), testScheduler)

        assertEquals(R.string.regions_delete_error, removal.remove("italia"))
        assertEquals(R.string.regions_delete_error, removal.removePackage("italia", PackageKind.MAP))
    }
}
