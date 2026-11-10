package com.pockettravel.app.navigation

import android.app.Application
import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.pockettravel.app.R
import com.pockettravel.app.onboarding.OnboardingStore
import com.pockettravel.app.regions.RecentRegionStore
import com.pockettravel.app.regions.RegionTab
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.feature.map.NavigationPlace
import com.pockettravel.feature.map.NavigationSession
import com.pockettravel.feature.map.NavigationViewModel
import com.pockettravel.feature.map.RouteProfile
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject
import kotlin.reflect.KClass
import com.pockettravel.core.ui.R as UiR

/**
 * Il NavHost vero dell'app (schermate vere, grafo Hilt di test senza rete) su JVM con Robolectric: rete di sicurezza per
 * chi riorganizza PocketTravelNavHost. Si controlla il back stack con le rotte, mai i testi.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36])
class PocketTravelNavHostTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var onboardingStore: OnboardingStore

    @Inject lateinit var recentRegionStore: RecentRegionStore

    @Inject lateinit var regionRepository: FakeRegionRepository

    @Inject lateinit var navigationSession: NavigationSession

    @Inject lateinit var usageModePreferences: UsageModePreferences

    @Inject lateinit var nationalityPreferences: NationalityPreferences

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<HiltTestActivity>
    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        hilt.inject()
        shadowOf(application.packageManager).addActivityIfNotPresent(ComponentName(application, HiltTestActivity::class.java))
    }

    @After
    fun tearDown() {
        scenario.close()
        assertTrue("Richieste di rete tentate: ${NetworkGuard.attempted}", NetworkGuard.attempted.isEmpty())
    }

    @Test
    fun `primo avvio parte dall'onboarding e finirlo lo sostituisce con una sola lista Regioni`() {
        // Le scelte del primo passo, senza le quali "Avanti" resta spento.
        usageModePreferences.setModes(setOf(UsageMode.A_PIEDI))
        nationalityPreferences.setNationality("it")
        launch()
        assertEquals(listOf(OnboardingRoute::class), stack())

        click(R.string.onboarding_next)
        click(R.string.onboarding_next)
        click(R.string.onboarding_start)

        assertEquals(listOf(RegionsRoute::class), stack())
        assertTrue(onboardingStore.isCompleted())
    }

    @Test
    fun `app gia' in uso con una regione recente apre la sua mappa sopra Regioni, una volta sola`() {
        onboardingStore.markCompleted()
        recentRegionStore.setLastRegionId(REGION_ID)
        installRegion()
        launch()
        assertEquals(listOf(RegionsRoute::class, RegionHubRoute::class), stack())
        assertEquals(RegionHubRoute(REGION_ID, tab = RegionTab.MAP.key), topEntryRoute<RegionHubRoute>())

        // Activity ricreata (rotazione, tema): il back stack e' ripristinato e la regione non si apre di nuovo.
        scenario.recreate()
        scenario.onActivity { setContent(it, openNavigatorOnStart = false) }
        compose.waitForIdle()
        assertEquals(listOf(RegionsRoute::class, RegionHubRoute::class), stack())
    }

    @Test
    fun `onboarding completato senza regione recente parte solo da Regioni`() {
        onboardingStore.markCompleted()
        launch()
        assertEquals(listOf(RegionsRoute::class), stack())
    }

    @Test
    fun `le destinazioni principali della barra hanno una sola copia e Regioni resta in fondo`() {
        onboardingStore.markCompleted()
        launch()

        click(R.string.nav_documents)
        assertEquals(listOf(RegionsRoute::class, VaultRoute::class), stack())
        click(R.string.nav_more)
        assertEquals(listOf(RegionsRoute::class, MoreRoute::class), stack())
        click(R.string.nav_navigator)
        assertEquals(listOf(RegionsRoute::class, NavigatorRoute::class), stack())
        click(R.string.nav_documents)
        click(R.string.nav_more)
        assertEquals(listOf(RegionsRoute::class, MoreRoute::class), stack())

        // Un secondo tocco sulla voce gia' aperta non impila un'altra copia.
        click(R.string.nav_more)
        assertEquals(listOf(RegionsRoute::class, MoreRoute::class), stack())
        click(R.string.nav_regions)
        assertEquals(listOf(RegionsRoute::class), stack())
        click(R.string.nav_regions)
        assertEquals(listOf(RegionsRoute::class), stack())
    }

    @Test
    fun `tornando a una destinazione principale se ne ripristina la voce salvata`() {
        onboardingStore.markCompleted()
        launch()

        click(R.string.nav_more)
        val moreEntryId = compose.runOnIdle { navController.currentBackStack.value.last().id }
        click(R.string.nav_documents)
        click(R.string.nav_more)
        assertEquals(moreEntryId, compose.runOnIdle { navController.currentBackStack.value.last().id })
    }

    @Test
    fun `indietro da Documenti e da Altro torna a Regioni`() {
        onboardingStore.markCompleted()
        launch()

        click(R.string.nav_documents)
        pressBack()
        assertEquals(listOf(RegionsRoute::class), stack())

        click(R.string.nav_more)
        pressBack()
        assertEquals(listOf(RegionsRoute::class), stack())
    }

    @Test
    fun `indietro dall'hub di una regione torna a Regioni`() {
        onboardingStore.markCompleted()
        installRegion()
        launch()
        navigateOnMain { navigate(RegionHubRoute(REGION_ID, tab = RegionTab.MAP.key)) }
        assertEquals(listOf(RegionsRoute::class, RegionHubRoute::class), stack())

        click(UiR.string.back, contentDescription = true)
        assertEquals(listOf(RegionsRoute::class), stack())
    }

    @Test
    fun `indietro dall'hub senza nulla sotto va a Regioni`() {
        onboardingStore.markCompleted()
        installRegion()
        launch()
        // Hub come unica voce: la lista Regioni sotto viene tolta.
        navigateOnMain {
            navigate(RegionHubRoute(REGION_ID, tab = RegionTab.MAP.key)) { popUpTo(RegionsRoute) { inclusive = true } }
        }
        assertEquals(listOf(RegionHubRoute::class), stack())

        click(UiR.string.back, contentDescription = true)
        assertEquals(listOf(RegionsRoute::class), stack())
    }

    @Test
    fun `la notifica del Navigatore all'avvio apre il Navigatore solo con una navigazione in corso`() {
        onboardingStore.markCompleted()
        launch(openNavigatorOnStart = true) { startNavigation(it) }
        assertEquals(listOf(RegionsRoute::class, NavigatorRoute::class), stack())
    }

    @Test
    fun `la notifica del Navigatore all'avvio non apre il Navigatore senza navigazione in corso`() {
        onboardingStore.markCompleted()
        launch(openNavigatorOnStart = true)
        assertEquals(listOf(RegionsRoute::class), stack())
    }

    @Test
    fun `la notifica del Navigatore all'avvio non apre il Navigatore durante l'onboarding`() {
        launch(openNavigatorOnStart = true) { startNavigation(it) }
        assertEquals(listOf(OnboardingRoute::class), stack())
    }

    @Test
    fun `la notifica del Navigatore all'avvio sostituisce la regione iniziale con il Navigatore`() {
        onboardingStore.markCompleted()
        recentRegionStore.setLastRegionId(REGION_ID)
        installRegion()
        launch(openNavigatorOnStart = true) { startNavigation(it) }
        assertEquals(listOf(RegionsRoute::class, NavigatorRoute::class), stack())
    }

    @Test
    fun `la notifica toccata con l'app aperta apre il Navigatore solo se si sta navigando`() {
        onboardingStore.markCompleted()
        launch()
        navigationSession.requestOpen()
        assertEquals(listOf(RegionsRoute::class), stack())

        scenario.onActivity { startNavigation(it) }
        navigationSession.requestOpen()
        assertEquals(listOf(RegionsRoute::class, NavigatorRoute::class), stack())
    }

    @Test
    fun `l'anteprima di una regione si apre da Regioni con i suoi argomenti`() {
        onboardingStore.markCompleted()
        launch()
        navigateOnMain { navigate(RegionPreviewRoute(REGION_ID, "Italia")) }
        assertEquals(listOf(RegionsRoute::class, RegionPreviewRoute::class), stack())
        assertEquals(RegionPreviewRoute(REGION_ID, "Italia"), topEntryRoute<RegionPreviewRoute>())
        compose.onNodeWithText("Italia").assertExists()
    }

    // ---- Supporto ----

    private fun launch(openNavigatorOnStart: Boolean = false, beforeContent: (HiltTestActivity) -> Unit = {}) {
        scenario = ActivityScenario.launch(HiltTestActivity::class.java)
        scenario.onActivity { activity ->
            beforeContent(activity)
            setContent(activity, openNavigatorOnStart)
        }
        compose.waitForIdle()
    }

    private fun setContent(activity: HiltTestActivity, openNavigatorOnStart: Boolean) {
        activity.setContent {
            val controller = testNavController()
            navController = controller
            PocketTravelTheme(darkTheme = false, dynamicColor = false) {
                PocketTravelNavHost(openNavigatorOnStart = openNavigatorOnStart, navController = controller)
            }
        }
    }

    // Come rememberNavController: lo stato si salva e si ripristina con l'activity ricreata.
    @Composable
    private fun testNavController(): TestNavHostController {
        val context = LocalContext.current
        fun create() = TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
        return rememberSaveable(saver = Saver(save = { it.saveState() }, restore = { create().apply { restoreState(it) } })) { create() }
    }

    // Una navigazione in corso, come dopo "Avvia": il ViewModel e' quello dell'activity, lo stesso che il NavHost legge.
    private fun startNavigation(activity: HiltTestActivity) {
        ViewModelProvider(activity)[NavigationViewModel::class.java]
            .start(REGION_ID, NavigationPlace("Roma", 41.9, 12.5, REGION_ID), RouteProfile.CAR)
    }

    private fun installRegion() = runBlocking {
        regionRepository.markPackagesInstalled(
            regionId = REGION_ID,
            displayName = "Italia",
            countryCode = "it",
            versions = mapOf(PackageKind.POI to "p1"),
            poiSizeBytes = 1L,
            poiExtraSizeBytes = null,
            previewVersion = null,
            citiesSizeBytes = null,
        )
    }

    // Le voci del back stack come classi di rotta, senza il grafo: l'ordine e' dal fondo alla cima.
    private fun stack(): List<KClass<*>> {
        compose.waitForIdle()
        return compose.runOnIdle {
            navController.currentBackStack.value
                .map { it.destination }
                .filterNot { it is NavGraph }
                .map { it.routeClass() }
        }
    }

    private inline fun <reified T : Any> topEntryRoute(): T = compose.runOnIdle { navController.currentBackStack.value.last().toRoute<T>() }

    private fun NavDestination.routeClass(): KClass<*> =
        ROUTES.firstOrNull { hasRoute(it) } ?: error("Rotta sconosciuta: $route")

    private fun navigateOnMain(block: TestNavHostController.() -> Unit) {
        compose.runOnUiThread { navController.block() }
        compose.waitForIdle()
    }

    // Tocco per risorsa, non per testo: vale in qualunque lingua; la barra non ha tag di test.
    private fun click(@StringRes resId: Int, contentDescription: Boolean = false) {
        val text = application.getString(resId)
        compose.onNodeWithTextOrDescription(text, contentDescription).performClick()
        compose.waitForIdle()
    }

    private fun ComposeTestRule.onNodeWithTextOrDescription(text: String, contentDescription: Boolean): SemanticsNodeInteraction =
        if (contentDescription) onNodeWithContentDescription(text) else onNodeWithText(text)

    // Il tasto Indietro di sistema, che raggiunge il gestore registrato da NavHost.
    private fun pressBack() {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private companion object {
        const val REGION_ID = "italia"

        val ROUTES: List<KClass<*>> = listOf(
            OnboardingRoute::class, RegionsRoute::class, NavigatorRoute::class, VaultRoute::class, MoreRoute::class,
            RegionHubRoute::class, RegionPreviewRoute::class, UpdatesRoute::class, SettingsRoute::class,
        )
    }
}
