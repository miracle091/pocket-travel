package com.pockettravel.app.regions

import androidx.work.WorkInfo
import com.pockettravel.app.MainDispatcherRule
import com.pockettravel.app.regionEntry
import com.pockettravel.app.regionManifest
import com.pockettravel.app.transitFeed
import com.pockettravel.app.transitManifestEntry
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.testing.FakeLastKnownPosition
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.sync.TransitIndex
import com.pockettravel.core.sync.attachTransitFeeds
import com.pockettravel.core.sync.testing.FakeManifestClient
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import com.pockettravel.core.sync.testing.FakeTransitClient
import com.pockettravel.feature.ai.testing.FakeAiAvailability
import com.pockettravel.feature.map.TransitPackageState
import com.pockettravel.feature.map.TransitUpdateResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RegionHubViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val files = InMemoryPreferencesFactory()
    private val repository = FakeRegionRepository()
    private val manifests = FakeManifestClient(regionManifest())
    private val scheduler = FakeRegionSyncScheduler()
    private val transitClient = FakeTransitClient()
    private val position = FakeLastKnownPosition()
    private val locator = FakeCountryLocator()
    private val ai = FakeAiAvailability()
    private val transitPreferences = TransitNetworkPreferences(files)
    private val recent = RecentRegionPreferences(files)
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ITALIAN)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    private fun viewModel() = RegionHubViewModel(
        repository, recent, manifests, scheduler, transitClient, transitPreferences, position, locator, ai, mainDispatcher.dispatcher,
    )

    // I StateFlow con WhileSubscribed hanno valori solo con un osservatore.
    private fun TestScope.observe(viewModel: RegionHubViewModel) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        advanceUntilIdle()
    }

    private fun installed(
        id: String = "italia",
        name: String = "Italia",
        code: String? = "it",
        map: String? = "m1",
        transit: String? = null,
    ) = InstalledRegion(
        id, name, code, mapVersion = map, routingVersion = null, poiVersion = "p1", poiExtraVersion = null, addressesVersion = null,
        poiSizeBytes = 10L, poiExtraSizeBytes = null, sizeBytes = 100L, transitVersion = transit,
    )

    private fun work(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state, emptySet())

    // Il catalogo con una rete dei mezzi pubblici per "italia"; transit.json la elenca.
    private fun withTransitOffered(version: String = "v1") {
        val feed = transitFeed("mdb-1", listOf("italia"), version)
        manifests.manifest = regionManifest(transit = transitManifestEntry())
        transitClient.index = TransitIndex("t1", listOf(feed))
    }

    @Test
    fun `l'IA e' disponibile come dice AiAvailability e si puo rileggere`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        assertFalse(viewModel.uiState.value.aiAvailable)

        ai.available.value = true
        viewModel.refreshAiAvailability()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.aiAvailable)
        assertEquals(1, ai.refreshCalls)
    }

    @Test
    fun `prima di caricare una regione non c'e' nulla e la mappa e' in attesa`() = runTest {
        val viewModel = viewModel()

        observe(viewModel)

        assertNull(viewModel.uiState.value.displayName)
        assertFalse(viewModel.uiState.value.regionMissing)
        assertEquals(RegionMapState.LOADING, viewModel.uiState.value.mapState)
        assertEquals(TransitPackageState.UNKNOWN, viewModel.uiState.value.transitState)
        assertNull(viewModel.uiState.value.transitUpdateResult)
    }

    @Test
    fun `caricare una regione installata ne da il nome e la ricorda come ultima aperta`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.load("italia")
        advanceUntilIdle()

        assertEquals("Italia", viewModel.uiState.value.displayName)
        assertFalse(viewModel.uiState.value.splitCountry)
        assertFalse(viewModel.uiState.value.regionMissing)
        assertEquals("italia", recent.lastRegionId())
    }

    @Test
    fun `il nome e' nella lingua dell'interfaccia`() = runTest {
        Locale.setDefault(Locale.ENGLISH)
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.load("italia")
        advanceUntilIdle()

        assertEquals("Italy", viewModel.uiState.value.displayName)
    }

    @Test
    fun `una regione di una nazione divisa e' segnalata come tale`() = runTest {
        repository.installedRegions.value = listOf(installed("francia-bretagna", "Francia - Bretagna", "fr"))
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.load("francia-bretagna")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.splitCountry)
        assertEquals("Francia - Bretagna", viewModel.uiState.value.displayName)
    }

    @Test
    fun `una regione non installata risulta mancante e senza nome`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.load("atlantide")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.regionMissing)
        assertNull(viewModel.uiState.value.displayName)
        assertEquals("anche una regione mancante e' l'ultima aperta", "atlantide", recent.lastRegionId())
    }

    @Test
    fun `ricaricare la stessa regione non rifa il lavoro, ma se mancava la ricontrolla`() = runTest {
        repository.installedRegions.value = listOf(installed())
        val viewModel = viewModel()
        observe(viewModel)
        viewModel.load("italia")
        advanceUntilIdle()
        val requests = manifests.recentCount

        viewModel.load("italia")
        advanceUntilIdle()
        assertEquals(requests, manifests.recentCount)

        val missing = viewModel()
        observe(missing)
        missing.load("francia")
        advanceUntilIdle()
        assertTrue(missing.uiState.value.regionMissing)
        repository.installedRegions.value = listOf(installed(), installed("francia", "Francia", "fr"))
        missing.load("francia")
        advanceUntilIdle()
        assertFalse(missing.uiState.value.regionMissing)
        assertEquals("Francia", missing.uiState.value.displayName)
    }

    @Test
    fun `cambiando regione si riparte da uno stato vuoto e vale l'ultima richiesta`() = runTest {
        repository.installedRegions.value = listOf(installed(), installed("francia", "Francia", "fr"))
        // La lettura di "francia" resta in sospeso: dopo il cambio non c'e' il nome di prima.
        val francia = CompletableDeferred<Unit>()
        val slow = object : RegionRepository by repository {
            override suspend fun installed(regionId: String): InstalledRegion? {
                if (regionId == "francia") francia.await()
                return repository.installed(regionId)
            }
        }
        val viewModel = RegionHubViewModel(
            slow, recent, manifests, scheduler, transitClient, transitPreferences, position, locator, ai, mainDispatcher.dispatcher,
        )
        observe(viewModel)
        viewModel.load("italia")
        advanceUntilIdle()
        assertEquals("Italia", viewModel.uiState.value.displayName)

        viewModel.load("francia")
        runCurrent()
        assertNull("subito dopo il cambio il nome della regione di prima non c'e' piu'", viewModel.uiState.value.displayName)
        viewModel.load("italia")
        advanceUntilIdle()

        assertEquals("Italia", viewModel.uiState.value.displayName)
        assertEquals("italia", recent.lastRegionId())
    }

    @Test
    fun `la mappa e' installata, mancante o in scaricamento`() = runTest {
        repository.installedRegions.value = listOf(installed(map = null))
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)
        assertEquals(RegionMapState.MISSING, viewModel.uiState.value.mapState)

        scheduler.downloadState("italia").value = work(WorkInfo.State.RUNNING)
        advanceUntilIdle()
        assertEquals(RegionMapState.DOWNLOADING, viewModel.uiState.value.mapState)

        scheduler.downloadState("italia").value = work(WorkInfo.State.ENQUEUED)
        advanceUntilIdle()
        assertEquals(RegionMapState.DOWNLOADING, viewModel.uiState.value.mapState)

        scheduler.downloadState("italia").value = work(WorkInfo.State.FAILED)
        advanceUntilIdle()
        assertEquals(RegionMapState.MISSING, viewModel.uiState.value.mapState)

        repository.installedRegions.value = listOf(installed(map = "m1"))
        advanceUntilIdle()
        assertEquals(RegionMapState.INSTALLED, viewModel.uiState.value.mapState)
    }

    @Test
    fun `scaricare la mappa accoda solo il pacchetto della mappa dalla voce del catalogo`() = runTest {
        manifests.manifest = regionManifest(listOf(regionEntry("italia"), regionEntry("francia", "Francia", "fr")))
        val viewModel = viewModel()
        viewModel.load("francia")
        advanceUntilIdle()

        viewModel.downloadMap()
        advanceUntilIdle()

        val (entry, kinds) = scheduler.enqueued.single()
        assertEquals("francia", entry.regionId)
        assertEquals(setOf(PackageKind.MAP), kinds)
    }

    @Test
    fun `scaricare la mappa senza regione caricata non fa nulla`() = runTest {
        val viewModel = viewModel()

        viewModel.downloadMap()
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `scaricare la mappa con il catalogo irraggiungibile o senza la regione non accoda e non va in errore`() = runTest {
        val viewModel = viewModel()
        viewModel.load("italia")
        advanceUntilIdle()

        manifests.failure = IOException("offline")
        viewModel.downloadMap()
        advanceUntilIdle()
        manifests.failure = null
        manifests.manifest = regionManifest(listOf(regionEntry("francia", "Francia", "fr")))
        viewModel.downloadMap()
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `gli orari gia' installati risultano installati senza leggere il catalogo`() = runTest {
        repository.installedRegions.value = listOf(installed(transit = "transit-1"))
        val viewModel = viewModel()

        viewModel.load("italia")
        observe(viewModel)

        assertEquals(TransitPackageState.INSTALLED, viewModel.uiState.value.transitState)
        assertEquals(0, manifests.recentCount)
    }

    @Test
    fun `se il catalogo offre reti per la regione gli orari sono scaricabili`() = runTest {
        repository.installedRegions.value = listOf(installed())
        withTransitOffered()
        val viewModel = viewModel()

        viewModel.load("italia")
        observe(viewModel)

        assertEquals(TransitPackageState.AVAILABLE, viewModel.uiState.value.transitState)
    }

    @Test
    fun `se il catalogo non ha reti per la regione gli orari non sono offerti`() = runTest {
        repository.installedRegions.value = listOf(installed())
        manifests.manifest = regionManifest(transit = transitManifestEntry())
        transitClient.index = TransitIndex("t1", listOf(transitFeed("mdb-9", listOf("francia"))))
        val viewModel = viewModel()

        viewModel.load("italia")
        observe(viewModel)

        assertEquals(TransitPackageState.NOT_OFFERED, viewModel.uiState.value.transitState)
    }

    @Test
    fun `senza rete o senza transit nel catalogo gli orari restano sconosciuti`() = runTest {
        repository.installedRegions.value = listOf(installed())
        manifests.failure = IOException("offline")
        val offline = viewModel()
        offline.load("italia")
        observe(offline)
        assertEquals(TransitPackageState.UNKNOWN, offline.uiState.value.transitState)

        manifests.failure = null
        manifests.manifest = regionManifest(transit = null)
        val withoutTransit = viewModel()
        withoutTransit.load("italia")
        observe(withoutTransit)
        assertEquals(TransitPackageState.UNKNOWN, withoutTransit.uiState.value.transitState)

        manifests.manifest = regionManifest(transit = transitManifestEntry())
        transitClient.failure = IOException("transit.json non raggiungibile")
        val noIndex = viewModel()
        noIndex.load("italia")
        observe(noIndex)
        assertEquals(TransitPackageState.UNKNOWN, noIndex.uiState.value.transitState)
    }

    @Test
    fun `scaricare gli orari accoda il pacchetto con le reti della regione e mostra lo scaricamento`() = runTest {
        repository.installedRegions.value = listOf(installed())
        withTransitOffered()
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        val (entry, kinds) = scheduler.enqueued.single()
        assertEquals("italia", entry.regionId)
        assertEquals(setOf(PackageKind.TRANSIT), kinds)
        assertEquals(listOf("mdb-1"), entry.transit?.feeds?.map { it.id })
        assertEquals(TransitPackageState.DOWNLOADING, viewModel.uiState.value.transitState)
        assertNull(viewModel.uiState.value.transitUpdateResult)
        assertEquals("la scelta di default diventa dell'utente", emptySet<String>(), transitPreferences.excluded.value["italia"])
    }

    @Test
    fun `finito il download gli orari risultano installati e l'esito resta vuoto`() = runTest {
        repository.installedRegions.value = listOf(installed())
        withTransitOffered()
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)
        viewModel.downloadTransit()
        advanceUntilIdle()

        scheduler.downloadState("italia").value = work(WorkInfo.State.SUCCEEDED)
        repository.installedRegions.value = listOf(installed(transit = "transit-1"))
        advanceUntilIdle()

        assertEquals(TransitPackageState.INSTALLED, viewModel.uiState.value.transitState)
        assertNull(viewModel.uiState.value.transitUpdateResult)
    }

    @Test
    fun `un download degli orari fallito da' l'esito Fallito`() = runTest {
        repository.installedRegions.value = listOf(installed())
        withTransitOffered()
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)
        viewModel.downloadTransit()
        advanceUntilIdle()

        scheduler.downloadState("italia").value = work(WorkInfo.State.FAILED)
        advanceUntilIdle()

        assertEquals(TransitUpdateResult.FAILED, viewModel.uiState.value.transitUpdateResult)
        assertEquals(TransitPackageState.AVAILABLE, viewModel.uiState.value.transitState)
    }

    @Test
    fun `aggiornare con la stessa versione del catalogo non scarica e lo dice`() = runTest {
        withTransitOffered()
        val sameVersion = regionManifest(
            listOf(regionEntry("italia")),
            transit = transitManifestEntry(),
        )
        manifests.manifest = sameVersion
        // La versione installata e' quella calcolata dalle reti del catalogo.
        val latest = attachTransitFeeds(sameVersion.regions, transitClient.index).single().versionOf(PackageKind.TRANSIT)
        repository.installedRegions.value = listOf(installed(transit = latest))
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(TransitUpdateResult.UP_TO_DATE, viewModel.uiState.value.transitUpdateResult)
        assertEquals(TransitPackageState.INSTALLED, viewModel.uiState.value.transitState)
    }

    @Test
    fun `aggiornare con una versione diversa scarica anche se gli orari sono installati`() = runTest {
        withTransitOffered(version = "v2")
        repository.installedRegions.value = listOf(installed(transit = "transit-vecchia"))
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertEquals(setOf(PackageKind.TRANSIT), scheduler.enqueued.single().second)
        assertEquals("durante l'aggiornamento il riquadro mostra lo scaricamento", TransitPackageState.DOWNLOADING, viewModel.uiState.value.transitState)
    }

    @Test
    fun `se il catalogo non e' leggibile aggiornare da' l'esito Fallito`() = runTest {
        repository.installedRegions.value = listOf(installed())
        manifests.failure = IOException("offline")
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertEquals(TransitUpdateResult.FAILED, viewModel.uiState.value.transitUpdateResult)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `se la regione non ha reti nel catalogo aggiornare non scarica e dice che e' aggiornato`() = runTest {
        repository.installedRegions.value = listOf(installed())
        manifests.manifest = regionManifest(transit = transitManifestEntry())
        transitClient.index = TransitIndex("t1", listOf(transitFeed("mdb-9", listOf("francia"))))
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertEquals(TransitUpdateResult.UP_TO_DATE, viewModel.uiState.value.transitUpdateResult)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `se il download viene rifiutato non resta lo scaricamento`() = runTest {
        repository.installedRegions.value = listOf(installed())
        withTransitOffered()
        scheduler.rejectDownloads = true
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(TransitPackageState.AVAILABLE, viewModel.uiState.value.transitState)
        assertNull(viewModel.uiState.value.transitUpdateResult)
    }

    @Test
    fun `scaricare gli orari senza regione caricata non fa nulla`() = runTest {
        val viewModel = viewModel()

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertEquals(0, manifests.fetchCount)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `con molte reti la posizione nella nazione della regione sceglie quelle vicine`() = runTest {
        val near = listOf(-0.5, 51.0, 0.5, 52.0)
        val far = listOf(-4.5, 56.0, -3.5, 57.0)
        val feeds = listOf(
            transitFeed("londra", listOf("regno-unito"), bbox = near),
            transitFeed("edimburgo", listOf("regno-unito"), bbox = far),
            transitFeed("glasgow", listOf("regno-unito"), bbox = far),
            transitFeed("aberdeen", listOf("regno-unito"), bbox = far),
        )
        manifests.manifest = regionManifest(listOf(regionEntry("regno-unito", "Regno Unito", "gb")), transit = transitManifestEntry())
        transitClient.index = TransitIndex("t1", feeds)
        repository.installedRegions.value = listOf(installed("regno-unito", "Regno Unito", "gb"))
        position.position = 51.5 to -0.1
        locator.country = "gb"
        val viewModel = viewModel()
        viewModel.load("regno-unito")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertEquals(listOf("londra"), scheduler.enqueued.single().first.transit?.feeds?.map { it.id })
        assertEquals(setOf("edimburgo", "glasgow", "aberdeen"), transitPreferences.excluded.value["regno-unito"])
    }

    @Test
    fun `con la posizione in un'altra nazione le reti restano tutte`() = runTest {
        val feeds = listOf("a", "b", "c", "d").map { transitFeed(it, listOf("regno-unito"), bbox = listOf(-4.5, 56.0, -3.5, 57.0)) }
        manifests.manifest = regionManifest(listOf(regionEntry("regno-unito", "Regno Unito", "gb")), transit = transitManifestEntry())
        transitClient.index = TransitIndex("t1", feeds)
        repository.installedRegions.value = listOf(installed("regno-unito", "Regno Unito", "gb"))
        position.position = 45.0 to 9.0
        locator.country = "it"
        val viewModel = viewModel()
        viewModel.load("regno-unito")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertEquals(4, scheduler.enqueued.single().first.transit?.feeds?.size)
    }

    @Test
    fun `cambiando regione durante l'aggiornamento l'esito non finisce sulla nuova`() = runTest {
        repository.installedRegions.value = listOf(installed(), installed("francia", "Francia", "fr"))
        manifests.failure = IOException("offline")
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)

        viewModel.downloadTransit()
        viewModel.load("francia")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.transitUpdateResult)
    }

    @Test
    fun `una rete scelta dall'utente non viene toccata dal download`() = runTest {
        repository.installedRegions.value = listOf(installed())
        withTransitOffered()
        transitPreferences.setExcluded("italia", setOf("altra"))
        val viewModel = viewModel()
        viewModel.load("italia")
        observe(viewModel)

        viewModel.downloadTransit()
        advanceUntilIdle()

        assertEquals(setOf("altra"), transitPreferences.excluded.value["italia"])
    }
}
