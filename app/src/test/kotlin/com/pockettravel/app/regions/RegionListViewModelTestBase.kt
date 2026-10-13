package com.pockettravel.app.regions

import androidx.work.WorkInfo
import com.pockettravel.app.MainDispatcherRule
import com.pockettravel.app.regionEntry
import com.pockettravel.app.regionManifest
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.MapDetailPreferences
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.UpdateCheck
import com.pockettravel.core.data.testing.FakeLastKnownPosition
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.sync.testing.FakeAddressGridClient
import com.pockettravel.core.sync.testing.FakeManifestClient
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import com.pockettravel.core.sync.testing.FakeTransitClient
import com.pockettravel.feature.map.MapFilterPreferences
import com.pockettravel.feature.map.UsageModePreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.util.Locale
import java.util.UUID

/** Fakes e funzioni comuni ai test di [RegionListViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class RegionListViewModelTestBase {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    protected val files = InMemoryPreferencesFactory()
    protected val manifests = FakeManifestClient(regionManifest(listOf(regionEntry())))
    protected val addressGrid = FakeAddressGridClient()
    protected val transitClient = FakeTransitClient()
    protected val repository = FakeRegionRepository()
    protected val scheduler = FakeRegionSyncScheduler()
    protected val updateChecks = mutableListOf<CountingUpdateCheck>()
    protected val routingPreferences = RoutingVariantPreferences(files)
    protected val usagePreferences = UsageModePreferences(files, MapFilterPreferences(files), routingPreferences)
    protected val transitPreferences = TransitNetworkPreferences(files)
    protected val mapDetail = MapDetailPreferences(files)
    protected val zones = RegionZonePreferences(files)
    protected val position = FakeLastKnownPosition()
    protected val locator = FakeCountryLocator()
    protected lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ITALIAN)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    protected fun viewModel(regions: RegionRepository = repository) = RegionListViewModel(
        manifestClient = manifests,
        addressGridClient = addressGrid,
        transitClient = transitClient,
        regionRepository = regions,
        regionSyncScheduler = scheduler,
        updateChecks = updateChecks.toSet(),
        usageModePreferences = usagePreferences,
        transitNetworkPreferences = transitPreferences,
        mapDetailPreferences = mapDetail,
        regionZonePreferences = zones,
        routingVariantPreferences = routingPreferences,
        lastKnownPosition = position,
        countryLocator = locator,
        ioDispatcher = mainDispatcher.dispatcher,
        defaultDispatcher = mainDispatcher.dispatcher,
    )

    // L'elenco e' uno StateFlow con WhileSubscribed: serve un osservatore per avere i valori.
    protected fun TestScope.observe(viewModel: RegionListViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()
    }

    protected fun installed(
        id: String = "italia",
        name: String = "Italia",
        code: String? = "it",
        map: String? = "m1",
        routing: String? = "r1",
        poi: String? = "p1",
        addresses: String? = null,
        sizeBytes: Long = 100L,
    ) = InstalledRegion(
        id, name, code, mapVersion = map, routingVersion = routing, poiVersion = poi, poiExtraVersion = null, addressesVersion = addresses,
        poiSizeBytes = poi?.let { 10L }, poiExtraSizeBytes = null, sizeBytes = sizeBytes,
    )

    protected fun work(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state, emptySet())

    protected val RegionListViewModel.items get() = uiState.value.items
}

/** [UpdateCheck] che conta le volte in cui parte. */
class CountingUpdateCheck : UpdateCheck {
    var runs = 0
        private set

    override fun checkNow() {
        runs++
    }
}

/** Repository le cui eliminazioni falliscono come quando i file non si possono cancellare (check nel repository vero). */
class FailingRemovals(private val inner: FakeRegionRepository) : RegionRepository by inner {
    override suspend fun remove(regionId: String) = throw IllegalStateException("file non eliminabile")

    override suspend fun removePackage(regionId: String, kind: PackageKind) = throw IllegalStateException("file non eliminabile")
}
