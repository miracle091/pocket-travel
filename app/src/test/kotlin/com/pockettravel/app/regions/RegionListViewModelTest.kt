package com.pockettravel.app.regions

import com.pockettravel.app.R
import com.pockettravel.app.regionEntry
import com.pockettravel.app.regionManifest
import com.pockettravel.app.transitFeed
import com.pockettravel.app.transitManifestEntry
import com.pockettravel.core.data.InstalledGuides
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.sync.AddressGridManifestEntry
import com.pockettravel.core.sync.ReplacedRegion
import com.pockettravel.core.sync.TransitIndex
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class RegionListViewModelTest : RegionListViewModelTestBase() {

    @Test
    fun `prima che il catalogo arrivi l'elenco e' in caricamento e vuoto`() {
        val viewModel = viewModel()

        assertEquals(RegionListUiState(), viewModel.uiState.value)
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `il catalogo letto compare nell'elenco con stato, peso e rettangolo`() = runTest {
        val viewModel = viewModel()

        observe(viewModel)

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.loadError)
        val item = state.items.single()
        assertEquals("italia", item.regionId)
        assertEquals("Italia", item.displayName)
        assertEquals("it", item.countryCode)
        assertEquals(RegionStatus.NOT_INSTALLED, item.status)
        assertEquals("la mappa non pesa, rete stradale e orari si scelgono a parte", 1_000L, item.sizeBytes)
        assertTrue(item.includesMap)
        assertEquals(RegionBbox(6.0, 36.0, 19.0, 47.0), item.bbox)
        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), item.packages.map { it.kind }.toSet())
        assertEquals(listOf(PackageKind.ADDRESSES), item.unavailableKinds)
    }

    @Test
    fun `con i percorsi di navigazione attivi il peso comprende la rete stradale`() = runTest {
        usagePreferences.setWantsDirections(true)
        val viewModel = viewModel()

        observe(viewModel)

        assertEquals(6_000L, viewModel.items.single().sizeBytes)
    }

    @Test
    fun `la regione installata e aggiornata risulta installata, con la dimensione sul dispositivo`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()

        observe(viewModel)

        val item = viewModel.items.single()
        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(100L, item.sizeBytes)
    }

    @Test
    fun `se il catalogo ha un pacchetto piu' recente la regione e' da aggiornare e pesa solo il cambiato`() = runTest {
        repository.installedRegions.value = listOf(installed(poi = "p0"))
        val viewModel = viewModel()

        observe(viewModel)

        val item = viewModel.items.single()
        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.status)
        assertEquals(1_000L, item.sizeBytes)
        assertEquals(RegionStatus.UPDATE_AVAILABLE, item.packages.first { it.kind == PackageKind.POI }.status)
        assertEquals(RegionStatus.INSTALLED, item.packages.first { it.kind == PackageKind.MAP }.status)
    }

    @Test
    fun `l'elenco segue le installazioni che cambiano`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        assertEquals(RegionStatus.NOT_INSTALLED, viewModel.items.single().status)

        repository.installedRegions.value = listOf(installed())
        advanceUntilIdle()

        assertEquals(RegionStatus.INSTALLED, viewModel.items.single().status)
    }

    @Test
    fun `con il catalogo irraggiungibile resta l'errore e le nazioni installate restano apribili`() = runTest {
        manifests.failure = IOException("offline")
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()

        observe(viewModel)

        val state = viewModel.uiState.value
        assertEquals(R.string.regions_load_error, state.loadError)
        assertFalse(state.isLoading)
        val item = state.items.single()
        assertEquals("italia", item.regionId)
        assertEquals(RegionStatus.INSTALLED, item.status)
        assertEquals(0L, item.packages.sumOf { it.downloadBytes })
    }

    @Test
    fun `con il catalogo irraggiungibile le installate restano nello stato insieme ai download in corso`() = runTest {
        manifests.failure = IOException("offline")
        repository.installedRegions.value = listOf(installed(), installed(id = "francia", name = "Francia", code = "fr"))
        scheduler.downloadingRegions.value = setOf("francia")
        val viewModel = viewModel()

        observe(viewModel)

        val state = viewModel.uiState.value
        assertEquals(listOf("italia", "francia"), state.items.map { it.regionId })
        assertEquals(setOf("francia"), state.downloadingRegions)
        assertEquals(R.string.regions_load_error, state.loadError)
    }

    @Test
    fun `con il catalogo irraggiungibile e niente installato l'elenco e' vuoto con l'errore`() = runTest {
        manifests.failure = IOException("offline")
        val viewModel = viewModel()

        observe(viewModel)

        assertEquals(emptyList<RegionUiItem>(), viewModel.items)
        assertEquals(R.string.regions_load_error, viewModel.uiState.value.loadError)
    }

    @Test
    fun `riprovare dopo l'errore carica il catalogo e toglie l'errore`() = runTest {
        manifests.failure = IOException("offline")
        val viewModel = viewModel()
        observe(viewModel)

        manifests.failure = null
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.loadError)
        assertEquals(RegionStatus.NOT_INSTALLED, viewModel.items.single().status)
    }

    @Test
    fun `i nomi offline delle regioni installate seguono la lingua dell'interfaccia`() = runTest {
        manifests.failure = IOException("offline")
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        viewModel.setLocale(Locale.ENGLISH)

        observe(viewModel)

        assertEquals("Italy", viewModel.items.single().displayName)
    }

    @Test
    fun `cambiando lingua i nomi cambiano e il catalogo si rilegge, ma non con la stessa lingua`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        val reads = manifests.recentCount

        viewModel.setLocale(Locale.ITALY)
        advanceUntilIdle()
        assertEquals("stessa lingua: niente da rileggere", reads, manifests.recentCount)

        viewModel.setLocale(Locale.ENGLISH)
        advanceUntilIdle()
        assertEquals(reads + 1, manifests.recentCount)
        assertEquals("Italy", viewModel.items.single().displayName)
    }

    @Test
    fun `la ricerca ignora maiuscole e accenti e filtra anche le regioni sostituite`() = runTest {
        manifests.manifest = regionManifest(
            listOf(regionEntry("italia"), regionEntry("sao-tome", "Sao Tome", "st"), regionEntry("francia", "Francia", "fr")),
        ).copy(replacedRegions = listOf(ReplacedRegion("stati-uniti", "Stati Uniti"), ReplacedRegion("spagna-vecchia", "Spagna")))
        repository.installedRegions.value = listOf(installed("stati-uniti", "Stati Uniti", "us"), installed("spagna-vecchia", "Spagna", "es"))
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.onQueryChange("FRAN")
        advanceUntilIdle()
        assertEquals(listOf("francia"), viewModel.items.map { it.regionId })
        assertEquals("FRAN", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.replaced.isEmpty())

        viewModel.onQueryChange("stati")
        advanceUntilIdle()
        assertTrue(viewModel.items.isEmpty())
        assertEquals(listOf("stati-uniti"), viewModel.uiState.value.replaced.map { it.regionId })

        viewModel.onQueryChange("  ")
        advanceUntilIdle()
        assertEquals(3, viewModel.items.size)
        assertEquals(2, viewModel.uiState.value.replaced.size)
    }

    @Test
    fun `le regioni installate che il catalogo ha diviso sono elencate a parte con il loro gruppo`() = runTest {
        manifests.manifest = regionManifest(listOf(regionEntry("italia"))).copy(replacedRegions = listOf(ReplacedRegion("stati-uniti", "Stati Uniti")))
        repository.installedRegions.value = listOf(installed("stati-uniti", "Stati Uniti", "us", sizeBytes = 300L))
        val viewModel = viewModel()

        observe(viewModel)

        assertEquals(listOf(ReplacedRegionItem("stati-uniti", "Stati Uniti", "us", "Stati Uniti", 300L)), viewModel.uiState.value.replaced)
    }

    @Test
    fun `le guide mancanti partono da sole, su rete mobile se sono leggere`() = runTest {
        repository.guides.value = null
        viewModel()

        advanceUntilIdle()

        assertEquals(listOf(FakeRegionSyncScheduler.GuidesSyncRequest(onlyOnWifi = false, automatic = true)), scheduler.guidesSyncs)
    }

    @Test
    fun `le guide pesanti partono da sole solo con il Wi-Fi`() = runTest {
        manifests.manifest = regionManifest(guidesBytes = 6L * 1024 * 1024)
        viewModel()

        advanceUntilIdle()

        assertEquals(listOf(FakeRegionSyncScheduler.GuidesSyncRequest(onlyOnWifi = true, automatic = true)), scheduler.guidesSyncs)
    }

    @Test
    fun `con le guide gia' alla versione del catalogo non parte nulla`() = runTest {
        repository.guides.value = InstalledGuides("g1", 100L)
        viewModel()

        advanceUntilIdle()

        assertTrue(scheduler.guidesSyncs.isEmpty())
    }

    @Test
    fun `il codice paese delle regioni installate prima che si salvasse viene completato dal catalogo`() = runTest {
        repository.installedRegions.value = listOf(installed(code = null), installed("francia", "Francia", code = "fr"))
        manifests.manifest = regionManifest(listOf(regionEntry("italia"), regionEntry("francia", "Francia", "de")))
        viewModel()

        advanceUntilIdle()

        val codes = repository.installedRegions.value.associate { it.regionId to it.countryCode }
        assertEquals("it", codes["italia"])
        assertEquals("quello gia' salvato non si tocca", "fr", codes["francia"])
    }

    @Test
    fun `un errore dei civici o dei mezzi pubblici non blocca l'elenco`() = runTest {
        manifests.manifest = regionManifest(listOf(regionEntry()), transit = transitManifestEntry())
            .copy(addressGrid = AddressGridManifestEntry("a1", "https://github.com/pocket-travel/address-grid.json", 10L, "c".repeat(64)))
        addressGrid.failure = IOException("civici non raggiungibili")
        transitClient.failure = IOException("transit non raggiungibile")
        val viewModel = viewModel()

        observe(viewModel)

        assertNull(viewModel.uiState.value.loadError)
        assertEquals(1, viewModel.items.size)
        assertEquals(1, addressGrid.fetched.size)
        assertEquals(1, transitClient.fetched.size)
    }

    @Test
    fun `il controllo manuale scarica sempre il catalogo e fa partire gli altri controlli`() = runTest {
        updateChecks += CountingUpdateCheck()
        updateChecks += CountingUpdateCheck()
        val viewModel = viewModel()
        observe(viewModel)
        assertEquals(0, manifests.fetchCount)

        viewModel.checkForUpdatesNow()
        advanceUntilIdle()

        assertEquals(1, manifests.fetchCount)
        assertEquals(listOf(1, 1), updateChecks.map { it.runs })
    }

    @Test
    fun `scaricare una regione non installata accoda i pacchetti del download completo`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.download("italia")
        advanceUntilIdle()

        val (entry, kinds) = scheduler.enqueued.single()
        assertEquals("italia", entry.regionId)
        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), kinds)
    }

    @Test
    fun `con i percorsi di navigazione attivi scaricare comprende la rete stradale`() = runTest {
        usagePreferences.setWantsDirections(true)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.download("italia")
        advanceUntilIdle()

        assertEquals(setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.POI), scheduler.enqueued.single().second)
    }

    @Test
    fun `aggiornare una regione installata accoda solo i pacchetti cambiati`() = runTest {
        repository.installedRegions.value = listOf(installed(poi = "p0"))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.download("italia")
        advanceUntilIdle()

        assertEquals(setOf(PackageKind.POI), scheduler.enqueued.single().second)
    }

    @Test
    fun `aggiornare una regione gia' aggiornata non accoda nulla`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.download("italia")
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `scaricare una regione che il catalogo non ha non fa nulla`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.download("atlantide")
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `scaricare prima che il catalogo arrivi non fa nulla`() = runTest {
        manifests.failure = IOException("offline")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.download("italia")
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `senza spazio sufficiente non si accoda e arriva un messaggio una tantum`() = runTest {
        repository.availableBytes = 500L
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.download("italia")
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(R.string.regions_not_enough_space, viewModel.uiState.value.message)
        viewModel.onMessageShown()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `lo spazio giusto basta`() = runTest {
        repository.availableBytes = 1_000L
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.download("italia")
        advanceUntilIdle()

        assertEquals(1, scheduler.enqueued.size)
    }

    @Test
    fun `scaricare i pacchetti scelti dice se e' partito`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.downloadKinds("italia", setOf(PackageKind.MAP, PackageKind.POI)))
        assertEquals(setOf(PackageKind.MAP, PackageKind.POI), scheduler.enqueued.single().second)
        assertFalse("nessun pacchetto scelto", viewModel.downloadKinds("italia", emptySet()))
        assertFalse("regione sconosciuta", viewModel.downloadKinds("atlantide", setOf(PackageKind.MAP)))
        repository.availableBytes = 0L
        assertFalse("spazio insufficiente", viewModel.downloadKinds("italia", setOf(PackageKind.POI)))
        assertEquals(1, scheduler.enqueued.size)
    }

    @Test
    fun `scaricare un pacchetto accoda quel solo pacchetto`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.downloadPackage("italia", PackageKind.POI)
        advanceUntilIdle()

        assertEquals(setOf(PackageKind.POI), scheduler.enqueued.single().second)
    }

    @Test
    fun `scaricare le reti dei mezzi pubblici ricorda la scelta fatta`() = runTest {
        val feeds = listOf(transitFeed("a", listOf("italia")), transitFeed("b", listOf("italia")))
        manifests.manifest = regionManifest(listOf(regionEntry()), transit = transitManifestEntry())
        transitClient.index = TransitIndex("t1", feeds)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.downloadPackage("italia", PackageKind.TRANSIT)
        advanceUntilIdle()

        assertEquals(setOf(PackageKind.TRANSIT), scheduler.enqueued.single().second)
        assertEquals(emptySet<String>(), transitPreferences.excluded.value["italia"])
    }

    @Test
    fun `eliminare una regione ferma il download, toglie la zona e cancella i dati`() = runTest {
        repository.installedRegions.value = listOf(installed())
        zones.setZone("italia", RegionZone(8.0, 40.0, 10.0, 42.0))
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.delete("italia")
        advanceUntilIdle()

        assertEquals(listOf("italia"), scheduler.cancelled)
        assertNull(zones.zone("italia"))
        assertTrue(repository.installedRegions.value.isEmpty())
        assertEquals(RegionStatus.NOT_INSTALLED, viewModel.items.single().status)
    }

    @Test
    fun `se i file non si eliminano arriva un messaggio e la regione resta`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel(regions = FailingRemovals(repository))
        observe(viewModel)

        viewModel.delete("italia")
        advanceUntilIdle()

        assertEquals(R.string.regions_delete_error, viewModel.uiState.value.message)
        assertEquals(1, repository.installedRegions.value.size)
    }

    @Test
    fun `eliminare un pacchetto lascia gli altri, e se fallisce da' il messaggio`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.deletePackage("italia", PackageKind.ROUTING)
        advanceUntilIdle()
        assertEquals(listOf("italia" to PackageKind.ROUTING), repository.removedPackages)
        assertNull(viewModel.uiState.value.message)

        val failing = viewModel(regions = FailingRemovals(repository))
        observe(failing)
        failing.deletePackage("italia", PackageKind.MAP)
        advanceUntilIdle()
        assertEquals(R.string.regions_delete_error, failing.uiState.value.message)
    }
}
