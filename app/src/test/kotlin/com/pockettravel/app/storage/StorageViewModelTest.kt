package com.pockettravel.app.storage

import com.pockettravel.app.MainDispatcherRule
import com.pockettravel.app.R
import com.pockettravel.app.regions.RecentRegionStore
import com.pockettravel.core.data.InstalledGuides
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import com.pockettravel.feature.ai.AiSettingsStore
import com.pockettravel.feature.ai.OnDeviceLlmEngine
import com.pockettravel.feature.ai.RamTier
import com.pockettravel.feature.ai.selectedModelDefinition
import com.pockettravel.feature.ai.testing.FakeLlmModelManager
import com.pockettravel.feature.ai.testing.FakeOnDeviceLlmEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = FakeRegionRepository()
    private val scheduler = FakeRegionSyncScheduler()
    private val models = FakeLlmModelManager()
    private val engine = FakeOnDeviceLlmEngine()
    private val modelsDir = createTempDirectory("storage-view-model-test").toFile()
    private val settings = AiSettingsStore(InMemoryPreferencesFactory(), modelsDir) { RamTier.MINIMO }
    private val recent = RecentRegionStore(InMemoryPreferencesFactory())
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ITALIAN)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        modelsDir.deleteRecursively()
    }

    private fun viewModel(
        regions: RegionRepository = repository,
        modelEngine: OnDeviceLlmEngine = engine,
    ) = StorageViewModel(regions, scheduler, models, modelEngine, settings, recent, mainDispatcher.dispatcher)

    // uiState e' un WhileSubscribed: serve un osservatore per avere i valori.
    private fun TestScope.observe(viewModel: StorageViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
    }

    private fun region(id: String = "italia", name: String = "Italia", code: String? = "it", map: String? = "m1", poi: String? = "p1") =
        InstalledRegion(
            id, name, code, mapVersion = map, routingVersion = null, poiVersion = poi, poiExtraVersion = null, addressesVersion = null,
            poiSizeBytes = poi?.let { 40L }, poiExtraSizeBytes = null, sizeBytes = 100L,
        )

    @Test
    fun `senza dati lo spazio e' vuoto e quello libero e' letto dal repository`() = runTest {
        repository.availableBytes = 5_000L
        val viewModel = viewModel()
        assertEquals("prima di osservarlo vale lo stato vuoto", StorageUiState(), viewModel.uiState.value)

        observe(viewModel)

        val state = viewModel.uiState.value
        assertNull(state.guides)
        assertEquals(emptyList<StorageRegionItem>(), state.installedRegions)
        assertEquals(0L, state.regionsSizeBytes)
        assertFalse(state.isModelDownloaded)
        assertEquals(0L, state.modelSizeBytes)
        assertEquals(5_000L, state.availableBytes)
        assertNull(state.message)
    }

    @Test
    fun `le regioni installate hanno i soli pacchetti installati con le loro dimensioni`() = runTest {
        repository.installedRegions.value = listOf(region())
        repository.packageSizes["italia" to PackageKind.MAP] = 60L
        val viewModel = viewModel()

        observe(viewModel)

        val item = viewModel.uiState.value.installedRegions.single()
        assertEquals("italia", item.regionId)
        assertEquals("Italia", item.displayName)
        assertEquals("it", item.countryCode)
        assertEquals(100L, item.sizeBytes)
        assertEquals(
            listOf(StoragePackageItem(PackageKind.MAP, 60L), StoragePackageItem(PackageKind.POI, 40L)),
            item.packages,
        )
    }

    @Test
    fun `il nome della regione e' nella lingua dell'interfaccia`() = runTest {
        Locale.setDefault(Locale.ENGLISH)
        repository.installedRegions.value = listOf(region(name = "Italia"))
        val viewModel = viewModel()

        observe(viewModel)

        assertEquals("Italy", viewModel.uiState.value.installedRegions.single().displayName)
    }

    @Test
    fun `lo spazio delle regioni comprende le guide`() = runTest {
        repository.installedRegions.value = listOf(region("italia"), region("francia", "Francia", "fr"))
        repository.guides.value = InstalledGuides("v1", 25L)
        val viewModel = viewModel()

        observe(viewModel)

        assertEquals(225L, viewModel.uiState.value.regionsSizeBytes)
        assertEquals(InstalledGuides("v1", 25L), viewModel.uiState.value.guides)
    }

    @Test
    fun `l'elenco segue le regioni che cambiano`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)

        repository.installedRegions.value = listOf(region())
        advanceUntilIdle()

        assertEquals(listOf("italia"), viewModel.uiState.value.installedRegions.map { it.regionId })
    }

    @Test
    fun `il modello scaricato compare con la sua dimensione`() = runTest {
        val definition = settings.selectedModelDefinition()
        models.downloadedModelIds.value = setOf(definition.id)
        models.sizes[definition.id] = 777L
        val viewModel = viewModel()

        observe(viewModel)

        assertTrue(viewModel.uiState.value.isModelDownloaded)
        assertEquals(777L, viewModel.uiState.value.modelSizeBytes)
    }

    @Test
    fun `eliminare una regione annulla il suo download, la toglie e rilegge lo spazio libero`() = runTest {
        repository.installedRegions.value = listOf(region("italia"), region("francia", "Francia", "fr"))
        repository.availableBytes = 1_000L
        recent.setLastRegionId("italia")
        val viewModel = viewModel()
        observe(viewModel)

        repository.availableBytes = 2_000L
        viewModel.deleteRegion("italia")
        advanceUntilIdle()

        assertEquals(listOf("italia"), scheduler.cancelled)
        assertNull(recent.lastRegionId())
        assertEquals(listOf("francia"), viewModel.uiState.value.installedRegions.map { it.regionId })
        assertEquals(2_000L, viewModel.uiState.value.availableBytes)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `eliminare un pacchetto lascia la regione con gli altri`() = runTest {
        repository.installedRegions.value = listOf(region())
        recent.setLastRegionId("italia")
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.deletePackage("italia", PackageKind.MAP)
        advanceUntilIdle()

        assertEquals(listOf("italia" to PackageKind.MAP), repository.removedPackages)
        assertEquals("italia", recent.lastRegionId())
        assertEquals(listOf(PackageKind.POI), viewModel.uiState.value.installedRegions.single().packages.map { it.kind })
    }

    @Test
    fun `eliminare l'ultimo pacchetto toglie la regione dall'elenco`() = runTest {
        repository.installedRegions.value = listOf(region(poi = null))
        recent.setLastRegionId("italia")
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.deletePackage("italia", PackageKind.MAP)
        advanceUntilIdle()

        assertEquals(emptyList<StorageRegionItem>(), viewModel.uiState.value.installedRegions)
        assertNull(recent.lastRegionId())
    }

    @Test
    fun `se i file non si eliminano arriva un messaggio una tantum e la regione resta`() = runTest {
        repository.installedRegions.value = listOf(region())
        val viewModel = viewModel(regions = FailingRemovals(repository))
        observe(viewModel)

        viewModel.deleteRegion("italia")
        advanceUntilIdle()

        assertEquals(R.string.regions_delete_error, viewModel.uiState.value.message)
        assertEquals(listOf("italia"), viewModel.uiState.value.installedRegions.map { it.regionId })
        viewModel.onMessageShown()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `lo stesso messaggio compare anche eliminando un pacchetto`() = runTest {
        repository.installedRegions.value = listOf(region())
        val viewModel = viewModel(regions = FailingRemovals(repository))
        observe(viewModel)

        viewModel.deletePackage("italia", PackageKind.POI)
        advanceUntilIdle()

        assertEquals(R.string.regions_delete_error, viewModel.uiState.value.message)
    }

    @Test
    fun `scaricare le guide chiede la sincronizzazione anche su rete mobile`() {
        viewModel().downloadGuides()

        assertEquals(listOf(FakeRegionSyncScheduler.GuidesSyncRequest(onlyOnWifi = false, automatic = false)), scheduler.guidesSyncs)
    }

    @Test
    fun `eliminare il modello libera il motore e rilegge lo stato`() = runTest {
        val definition = settings.selectedModelDefinition()
        models.downloadedModelIds.value = setOf(definition.id)
        val viewModel = viewModel(modelEngine = DeletingEngine(engine, models))
        observe(viewModel)
        assertTrue(viewModel.uiState.value.isModelDownloaded)

        viewModel.deleteModel()
        advanceUntilIdle()

        assertEquals(1, engine.releaseAndDeleteCalls)
        assertFalse(viewModel.uiState.value.isModelDownloaded)
        assertEquals(0L, viewModel.uiState.value.modelSizeBytes)
    }

    @Test
    fun `eliminare il modello senza modello scaricato non cambia lo stato`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.deleteModel()
        advanceUntilIdle()

        assertEquals(1, engine.releaseAndDeleteCalls)
        assertFalse(viewModel.uiState.value.isModelDownloaded)
    }
}

/** Repository le cui eliminazioni falliscono come quando i file non si possono cancellare (check nel repository vero). */
private class FailingRemovals(private val inner: FakeRegionRepository) : RegionRepository by inner {
    override suspend fun remove(regionId: String) = throw IllegalStateException("file non eliminabile")

    override suspend fun removePackage(regionId: String, kind: PackageKind) = throw IllegalStateException("file non eliminabile")
}

/** Motore che, eliminando il modello, toglie anche il file dal gestore dei modelli. */
private class DeletingEngine(
    private val inner: FakeOnDeviceLlmEngine,
    private val models: FakeLlmModelManager,
) : OnDeviceLlmEngine by inner {
    override suspend fun releaseAndDelete(): Boolean {
        models.downloadedModelIds.value = emptySet()
        return inner.releaseAndDelete()
    }
}
