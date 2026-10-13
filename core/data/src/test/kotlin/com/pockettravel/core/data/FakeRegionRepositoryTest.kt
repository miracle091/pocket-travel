package com.pockettravel.core.data

import com.pockettravel.core.data.testing.FakeRegionRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Il fake si comporta come RoomRegionRepository nei casi che i test dei ViewModel usano. */
class FakeRegionRepositoryTest {

    @Test
    fun `i pacchetti installati si sommano e quello tolto per ultimo toglie la regione`() = runTest {
        val repository = FakeRegionRepository()

        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1"))
        repository.markPackagesInstalled("italia", "Italia", null, mapOf(PackageKind.ROUTING to "2"))
        assertEquals("1", repository.installed("italia")?.mapVersion)
        assertEquals("2", repository.installed("italia")?.routingVersion)
        assertEquals("it", repository.installed("italia")?.countryCode)

        repository.removePackage("italia", PackageKind.MAP)
        assertNull(repository.installed("italia")?.mapVersion)
        repository.removePackage("italia", PackageKind.ROUTING)
        assertNull(repository.installed("italia"))
        assertEquals(emptyList<InstalledRegion>(), repository.installedRegions.value)
    }

    @Test
    fun `forgetMissingPackages toglie solo i pacchetti installati senza file`() = runTest {
        val repository = FakeRegionRepository()
        repository.markPackagesInstalled("italia", "Italia", "it", mapOf(PackageKind.MAP to "1", PackageKind.ROUTING to "2"))
        repository.missingPackages["italia"] = setOf(PackageKind.MAP, PackageKind.TRANSIT)

        assertEquals(setOf(PackageKind.MAP), repository.forgetMissingPackages("italia"))
        assertNull(repository.installed("italia")?.mapVersion)
        assertEquals("2", repository.installed("italia")?.routingVersion)
    }
}
