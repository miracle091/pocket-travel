package com.pockettravel.feature.map

import androidx.lifecycle.SavedStateHandle
import com.pockettravel.core.data.AddressResult
import com.pockettravel.core.data.PoiRepository
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.testing.FakeAddressSearchRepository
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
abstract class NavigationPlannerTestBase {
    // Come Main.immediate: gli stati derivati (routing, regioni installate) sono aggiornati subito, non al prossimo giro.
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    protected val files = InMemoryPreferencesFactory()
    protected val dao = FakePoiDao()
    protected val addresses = FakeAddressSearchRepository()
    protected val regions = FakeRegionRepository(listOf(installedRegion("r1", "Italia"), installedRegion("r2", "Francia")))
    protected val engines = ScriptedRouteEngineFactory()
    protected val gps = FakeGpsLocationSource()
    protected val reminders = FakeDepartureReminderScheduler()
    protected val recents = RecentDestinations(files)
    protected val filters = MapFilterPreferences(files)
    protected val variants = RoutingVariantPreferences(files)
    protected val usageModes = UsageModePreferences(files, filters, variants)
    protected val tileSource = FakeOfflineTileSource().apply {
        bounds["r1"] = MapBounds(6.0, 36.0, 19.0, 47.0)
        bounds["r2"] = MapBounds(-5.0, 42.0, 8.0, 51.0)
    }
    protected var handle = SavedStateHandle()
    protected var host: ViewModelHost<NavigationPlannerViewModel>? = null
    protected var previousLocale: Locale = Locale.getDefault()

    protected val colosseo = NavigationPlace("Colosseo", 41.89, 12.49, "r1")
    protected val duomo = NavigationPlace("Duomo", 45.46, 9.19, "r1")
    protected val louvre = NavigationPlace("Louvre", 48.86, 2.33, "r2")
    protected val route = Route(listOf(RoutePoint(45.46, 9.19), RoutePoint(41.89, 12.49)), 480_000.0, 6_000.0)

    @Before
    fun italianLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ITALIAN)
    }

    @After
    fun restore() {
        Locale.setDefault(previousLocale)
        host?.clear()
    }

    protected fun TestScope.viewModel(): NavigationPlannerViewModel {
        val created = ViewModelHost(NavigationPlannerViewModel::class.java) {
            NavigationPlannerViewModel(
                handle, reminders, main.dispatcher, PoiRepository(dao), addresses, regions, engines, gps, recents, usageModes, variants, tileSource,
            )
        }
        host = created
        keepCollecting(created.viewModel.uiState)
        return created.viewModel
    }

    // Destinazione [to] con partenza da un posto scelto: l'anteprima non ha bisogno del GPS.
    protected fun NavigationPlannerViewModel.plan(to: NavigationPlace, from: NavigationPlace = duomo) {
        setDestination(to)
        startSearch(PlannerField.FROM)
        choose(from)
    }

    protected fun fix(latitude: Double = duomo.latitude, longitude: Double = duomo.longitude) = GpsFix(latitude, longitude, 5f, 0L)

    protected fun minutes(time: LocalDateTime) = time.toEpochSecond(ZoneOffset.UTC) / 60

    protected val departure = LocalDateTime.of(2026, 10, 12, 8, 30)
}
