package com.pockettravel.app.regions

import androidx.work.WorkInfo
import com.pockettravel.app.manifestFile
import com.pockettravel.app.regionEntry
import com.pockettravel.app.regionManifest
import com.pockettravel.app.transitFeed
import com.pockettravel.app.transitManifestEntry
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.data.RoutingVariantChoice
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.core.sync.TransitDefaultReason
import com.pockettravel.core.sync.TransitIndex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegionListViewModelContentsTest : RegionListViewModelTestBase() {

    @Test
    fun `la mappa e' leggera di default solo se non e' installata`() = runTest {
        repository.installedRegions.value = listOf(installed("francia", "Francia", "fr"))
        val viewModel = viewModel()

        assertTrue(viewModel.observeMapLight("italia").first())
        assertFalse(viewModel.observeMapLight("francia").first())
    }

    @Test
    fun `la scelta della mappa leggera vince su tutto`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()

        viewModel.setMapLight("italia", true)
        advanceUntilIdle()

        assertTrue(viewModel.observeMapLight("italia").first())
        assertEquals(true, mapDetail.choice("italia"))
    }

    @Test
    fun `cambiare mappa con la mappa installata la riscarica, senza la mappa no`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setMapLight("italia", true)
        advanceUntilIdle()
        assertEquals(setOf(PackageKind.MAP), scheduler.enqueued.single().second)

        repository.installedRegions.value = listOf(installed(map = null))
        viewModel.setMapLight("italia", false)
        advanceUntilIdle()
        assertEquals(1, scheduler.enqueued.size)
        assertEquals(false, mapDetail.choice("italia"))
    }

    @Test
    fun `la zona scelta si salva ridotta alla regione e riscarica mappa e rete stradale installate`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setZone("italia", RegionZone(0.0, 40.0, 10.0, 42.0))
        advanceUntilIdle()

        assertEquals(RegionZone(6.0, 40.0, 10.0, 42.0), zones.zone("italia"))
        val (entry, kinds) = scheduler.enqueued.single()
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING), kinds)
        assertEquals(6.0, entry.map.source.minLon, 0.0)
        assertEquals(42.0, entry.map.source.maxLat, 0.0)
    }

    @Test
    fun `una zona fuori dalla regione viene ignorata`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setZone("italia", RegionZone(30.0, 60.0, 40.0, 70.0))
        advanceUntilIdle()

        assertNull(zones.zone("italia"))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `la zona di una regione sconosciuta viene ignorata`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setZone("atlantide", RegionZone(8.0, 40.0, 10.0, 42.0))
        advanceUntilIdle()

        assertNull(zones.zone("atlantide"))
    }

    @Test
    fun `con la regione non installata la zona si salva e il download parte solo se richiesto`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setZone("italia", RegionZone(8.0, 40.0, 10.0, 42.0))
        advanceUntilIdle()
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(RegionZone(8.0, 40.0, 10.0, 42.0), zones.zone("italia"))

        viewModel.setZone("italia", RegionZone(8.0, 40.0, 10.0, 42.0), download = true)
        advanceUntilIdle()
        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), scheduler.enqueued.single().second)
    }

    @Test
    fun `togliere la zona torna a tutta la regione e la riscarica per intero`() = runTest {
        repository.installedRegions.value = listOf(installed())
        zones.setZone("italia", RegionZone(8.0, 40.0, 10.0, 42.0))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setZone("italia", null)
        advanceUntilIdle()

        assertNull(zones.zone("italia"))
        val (entry, kinds) = scheduler.enqueued.single()
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING), kinds)
        assertEquals(6.0, entry.map.source.minLon, 0.0)
        assertEquals(19.0, entry.map.source.maxLon, 0.0)
    }

    @Test
    fun `i civici installati ma non offerti nella zona vengono tolti`() = runTest {
        repository.installedRegions.value = listOf(installed(addresses = "a1"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setZone("italia", RegionZone(8.0, 40.0, 10.0, 42.0))
        advanceUntilIdle()

        assertEquals(listOf("italia" to PackageKind.ADDRESSES), repository.removedPackages)
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING), scheduler.enqueued.single().second)
    }

    @Test
    fun `le regioni nella zona sono ordinate per presenza, con il nome nella lingua dell'interfaccia`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        locator.country = "it"

        val inside = viewModel.regionsInZone(RegionZone(8.0, 40.0, 10.0, 42.0))
        locator.country = null
        val sea = viewModel.regionsInZone(RegionZone(8.0, 40.0, 10.0, 42.0))
        locator.country = "fr"
        val otherCountry = viewModel.regionsInZone(RegionZone(8.0, 40.0, 10.0, 42.0))

        assertEquals(listOf("italia" to "Italia"), inside)
        assertEquals(emptyList<Pair<String, String>>(), sea)
        assertEquals(emptyList<Pair<String, String>>(), otherCountry)
    }

    @Test
    fun `la regione e' installata per intero solo se installata e senza zona`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.isInstalledWhole("italia"))
        assertFalse(viewModel.isInstalledWhole("francia"))
        zones.setZone("italia", RegionZone(8.0, 40.0, 10.0, 42.0))
        assertFalse(viewModel.isInstalledWhole("italia"))
    }

    @Test
    fun `la rete stradale per l'auto riscarica quella installata dalla variante solo auto`() = runTest {
        val car = RoutingPackageEntry("rc1", listOf(manifestFile("italia-car.rd5", 3_000L)))
        manifests.manifest = regionManifest(listOf(regionEntry().copy(routingCar = car)))
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setRoutingChoice("italia", RoutingVariantOption.CAR)
        advanceUntilIdle()

        val (entry, kinds) = scheduler.enqueued.single()
        assertEquals(setOf(PackageKind.ROUTING), kinds)
        assertEquals("rc1", entry.routing.version)
        assertEquals(RoutingVariantOption.CAR, viewModel.observeRoutingChoice("italia").first())
    }

    @Test
    fun `scegliere di nuovo la stessa rete stradale o senza averla installata non scarica`() = runTest {
        repository.installedRegions.value = listOf(installed(routing = null))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setRoutingChoice("italia", RoutingVariantOption.ALL)
        viewModel.setRoutingChoice("italia", RoutingVariantOption.CAR)
        viewModel.setRoutingChoice("italia", RoutingVariantOption.BIKE_FOOT)
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(RoutingVariantOption.BIKE_FOOT, viewModel.observeRoutingChoice("italia").first())
        assertEquals(RoutingVariantChoice.BIKE_FOOT, routingPreferences.choices.value.choiceFor("italia"))
    }

    @Test
    fun `le regioni in download sono quelle dello scheduler`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        assertEquals(emptySet<String>(), viewModel.uiState.value.downloadingRegions)

        scheduler.downloadingRegions.value = setOf("italia")
        advanceUntilIdle()

        assertEquals(setOf("italia"), viewModel.uiState.value.downloadingRegions)
    }

    @Test
    fun `l'avanzamento di una regione e' il lavoro dello scheduler`() = runTest {
        val viewModel = viewModel()
        val running = work(WorkInfo.State.RUNNING)

        scheduler.downloadState("italia").value = running

        assertEquals(running, viewModel.observeDownloadProgress("italia").first())
        assertNull(viewModel.observeDownloadProgress("francia").first())
    }

    // Regno Unito con quattro reti di mezzi pubblici, una a Londra e tre in Scozia.
    private fun ukWithTransit() {
        val london = listOf(-0.5, 51.0, 0.5, 52.0)
        val scotland = listOf(-4.5, 56.0, -3.5, 57.0)
        val feeds = listOf(
            transitFeed("londra", listOf("regno-unito"), bbox = london),
            transitFeed("edimburgo", listOf("regno-unito"), bbox = scotland),
            transitFeed("glasgow", listOf("regno-unito"), bbox = scotland),
            transitFeed("aberdeen", listOf("regno-unito"), bbox = scotland),
        )
        manifests.manifest = regionManifest(listOf(regionEntry("regno-unito", "Regno Unito", "gb")), transit = transitManifestEntry())
        transitClient.index = TransitIndex("t1", feeds)
    }

    private fun RegionListViewModel.transitNetworks() =
        items.single().packages.first { it.kind == PackageKind.TRANSIT }

    @Test
    fun `le reti dei mezzi pubblici di default sono quelle vicine all'ultima posizione`() = runTest {
        ukWithTransit()
        position.position = 51.5 to -0.1
        locator.country = "gb"
        val viewModel = viewModel()

        observe(viewModel)

        val transit = viewModel.transitNetworks()
        assertEquals(listOf("londra" to true, "edimburgo" to false, "glasgow" to false, "aberdeen" to false), transit.networks.map { it.id to it.selected })
        assertEquals(TransitDefaultReason.NEAR, transit.transitDefaultReason)
    }

    @Test
    fun `senza posizione nota le reti restano tutte`() = runTest {
        ukWithTransit()
        val viewModel = viewModel()

        observe(viewModel)

        assertEquals(listOf(true, true, true, true), viewModel.transitNetworks().networks.map { it.selected })
        assertEquals(TransitDefaultReason.ALL, viewModel.transitNetworks().transitDefaultReason)
    }

    @Test
    fun `aggiungere e togliere una rete cambia subito le reti scelte`() = runTest {
        ukWithTransit()
        position.position = 51.5 to -0.1
        locator.country = "gb"
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.setTransitNetwork("regno-unito", "glasgow", included = true)
        advanceUntilIdle()
        assertEquals(listOf(true, false, true, false), viewModel.transitNetworks().networks.map { it.selected })
        assertNull("scelta dell'utente: niente motivo di default", viewModel.transitNetworks().transitDefaultReason)

        viewModel.setTransitNetwork("regno-unito", "londra", included = false)
        advanceUntilIdle()
        assertEquals(listOf(false, false, true, false), viewModel.transitNetworks().networks.map { it.selected })
    }
}
