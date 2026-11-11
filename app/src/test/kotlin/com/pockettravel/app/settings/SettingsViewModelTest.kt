package com.pockettravel.app.settings

import com.pockettravel.app.MainDispatcherRule
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.RoutingVariantChoice
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.sync.AlertNotificationPreferences
import com.pockettravel.core.sync.AlertNotificationSwitch
import com.pockettravel.core.sync.AlertNotificationsScheduler
import com.pockettravel.core.sync.CatalogCheck
import com.pockettravel.core.sync.CustomCatalog
import com.pockettravel.core.sync.testing.FakeCatalogPreferences
import com.pockettravel.feature.map.MapFilterPreferences
import com.pockettravel.feature.map.NavigationPreferences
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val files = InMemoryPreferencesFactory()
    private val catalog = FakeCatalogPreferences()
    private val scheduled = mutableListOf<Boolean>()
    private val scheduler = AlertNotificationsScheduler { scheduled += it }

    // Senza una scelta la nazionalita' viene dal dispositivo (Resources di sistema, assente nei test JVM): la si salva prima.
    private fun viewModel(nationality: String = "IT", saved: CustomCatalog? = null): SettingsViewModel {
        files.open("profile").edit().putString("nationality", nationality).apply()
        catalog.saved = saved
        return SettingsViewModel(
            themePreferences = ThemePreferences(files),
            usageModePreferences = UsageModePreferences(files, MapFilterPreferences(files), RoutingVariantPreferences(files)),
            nationalityPreferences = NationalityPreferences(files),
            navigationPreferences = NavigationPreferences(files),
            catalogPreferences = catalog,
            alertNotifications = AlertNotificationSwitch(AlertNotificationPreferences(files), scheduler),
            ioDispatcher = mainDispatcher.dispatcher,
        )
    }

    // uiState e' WhileSubscribed: serve un osservatore, e runCurrent per far girare combine.
    private fun TestScope.observe(viewModel: SettingsViewModel) {
        backgroundScope.launch(mainDispatcher.dispatcher) { viewModel.uiState.collect {} }
    }

    @Test
    fun `le notifiche delle allerte sono spente di fabbrica e l'interruttore accende e spegne il controllo`() {
        val viewModel = viewModel()
        assertFalse(viewModel.alertNotifications.enabled.value)

        viewModel.alertNotifications.set(true)
        viewModel.alertNotifications.set(false)

        assertEquals(listOf(true, false), scheduled)
        assertFalse(viewModel.alertNotifications.enabled.value)
    }

    @Test
    fun `i valori iniziali sono quelli di fabbrica`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        runCurrent()

        assertTrue(viewModel.uiState.value.useDynamicColor)
        assertFalse(viewModel.uiState.value.forceDark)
        assertEquals(emptySet<UsageMode>(), viewModel.uiState.value.usageModes)
        assertFalse(viewModel.uiState.value.accessible)
        assertFalse(viewModel.uiState.value.wantsDirections)
        assertTrue(viewModel.uiState.value.stopGpsOnArrival)
        assertTrue(viewModel.uiState.value.walkingHaptics)
        assertEquals("IT", viewModel.uiState.value.nationality)
        assertNull(viewModel.customCatalog)
        assertEquals(CatalogState.Idle, viewModel.uiState.value.catalogState)
    }

    @Test
    fun `i colori e il tema si cambiano e restano per la prossima apertura`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.setUseDynamicColor(false)
        viewModel.setForceDark(true)
        runCurrent()

        assertFalse(viewModel.uiState.value.useDynamicColor)
        assertTrue(viewModel.uiState.value.forceDark)
        val reopened = viewModel()
        observe(reopened)
        runCurrent()
        assertFalse(reopened.uiState.value.useDynamicColor)
        assertTrue(reopened.uiState.value.forceDark)
    }

    @Test
    fun `la nazionalita' scelta sostituisce quella iniziale e resta`() = runTest {
        val viewModel = viewModel(nationality = "IT")
        observe(viewModel)

        viewModel.setNationality("FR")
        runCurrent()

        assertEquals("FR", viewModel.uiState.value.nationality)
        assertEquals("FR", NationalityPreferences(files).nationality.value)
    }

    @Test
    fun `i modi d'uso, la sedia a rotelle e i percorsi di navigazione si salvano a parte`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.setUsageModes(setOf(UsageMode.A_PIEDI, UsageMode.AUTO))
        viewModel.setAccessible(true)
        viewModel.setWantsDirections(true)
        runCurrent()

        assertEquals(setOf(UsageMode.A_PIEDI, UsageMode.AUTO), viewModel.uiState.value.usageModes)
        assertTrue(viewModel.uiState.value.accessible)
        assertTrue(viewModel.uiState.value.wantsDirections)
        viewModel.setAccessible(false)
        runCurrent()
        assertFalse(viewModel.uiState.value.accessible)
        assertTrue("gli altri valori non cambiano", viewModel.uiState.value.wantsDirections)
    }

    @Test
    fun `scegliere solo modi motorizzati imposta la rete stradale per l'auto`() {
        val viewModel = viewModel()

        viewModel.setUsageModes(setOf(UsageMode.AUTO))

        assertEquals(RoutingVariantChoice.CAR, RoutingVariantPreferences(files).choices.value.default)
    }

    @Test
    fun `svuotare i modi riporta la rete stradale a tutti i modi`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        viewModel.setUsageModes(setOf(UsageMode.AUTO))

        viewModel.setUsageModes(emptySet())
        runCurrent()

        assertEquals(emptySet<UsageMode>(), viewModel.uiState.value.usageModes)
        assertEquals(RoutingVariantChoice.ALL, RoutingVariantPreferences(files).choices.value.default)
    }

    @Test
    fun `le preferenze del navigatore si cambiano`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)

        viewModel.setStopGpsOnArrival(false)
        viewModel.setWalkingHaptics(false)
        runCurrent()

        assertFalse(viewModel.uiState.value.stopGpsOnArrival)
        assertFalse(viewModel.uiState.value.walkingHaptics)
        assertFalse(NavigationPreferences(files).stopGpsOnArrival.value)
    }

    @Test
    fun `il catalogo gia' scelto e' quello esposto`() {
        val chosen = CustomCatalog("https://example.org/manifest.json", "chiave")

        assertEquals(chosen, viewModel(saved = chosen).customCatalog)
    }

    @Test
    fun `un catalogo valido passa da Checking a Restart e viene provato con i dati inseriti`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        val check = CompletableDeferred<CatalogCheck>()
        catalog.result = { check.await() }

        viewModel.useCatalog("https://example.org/manifest.json", "chiave")
        runCurrent()

        assertEquals(CatalogState.Checking, viewModel.uiState.value.catalogState)
        check.complete(CatalogCheck.OK)
        advanceUntilIdle()
        assertEquals(CatalogState.Restart, viewModel.uiState.value.catalogState)
        assertEquals(listOf("https://example.org/manifest.json" to "chiave"), catalog.checked)
    }

    @Test
    fun `un catalogo non valido resta nelle Impostazioni con il motivo`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        catalog.result = { CatalogCheck.BAD_SIGNATURE }

        viewModel.useCatalog("https://example.org/manifest.json", "chiave")
        advanceUntilIdle()

        assertEquals(CatalogState.Failed(CatalogCheck.BAD_SIGNATURE), viewModel.uiState.value.catalogState)
    }

    @Test
    fun `con una prova in corso un secondo tocco non ne avvia un'altra`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        val check = CompletableDeferred<CatalogCheck>()
        catalog.result = { check.await() }

        viewModel.useCatalog("https://example.org/a.json", "k")
        runCurrent()
        viewModel.useCatalog("https://example.org/b.json", "k")
        check.complete(CatalogCheck.OK)
        advanceUntilIdle()

        assertEquals(listOf("https://example.org/a.json" to "k"), catalog.checked)
    }

    @Test
    fun `chiudere l'errore del catalogo torna a Idle ma non tocca gli altri stati`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        catalog.result = { CatalogCheck.UNREACHABLE }
        viewModel.useCatalog("https://example.org/manifest.json", "chiave")
        advanceUntilIdle()

        viewModel.clearCatalogError()
        runCurrent()
        assertEquals(CatalogState.Idle, viewModel.uiState.value.catalogState)

        catalog.result = { CatalogCheck.OK }
        viewModel.useCatalog("https://example.org/manifest.json", "chiave")
        advanceUntilIdle()
        viewModel.clearCatalogError()
        runCurrent()
        assertEquals(CatalogState.Restart, viewModel.uiState.value.catalogState)
    }

    @Test
    fun `dopo un errore si puo' riprovare`() = runTest {
        val viewModel = viewModel()
        observe(viewModel)
        catalog.result = { CatalogCheck.INVALID_KEY }
        viewModel.useCatalog("https://example.org/manifest.json", "sbagliata")
        advanceUntilIdle()

        catalog.result = { CatalogCheck.OK }
        viewModel.useCatalog("https://example.org/manifest.json", "giusta")
        advanceUntilIdle()

        assertEquals(CatalogState.Restart, viewModel.uiState.value.catalogState)
        assertEquals(2, catalog.checked.size)
    }

    @Test
    fun `tornare al catalogo ufficiale lo ripristina e chiede il riavvio`() = runTest {
        val viewModel = viewModel(saved = CustomCatalog("https://example.org/manifest.json", "chiave"))
        observe(viewModel)

        viewModel.useOfficialCatalog()
        advanceUntilIdle()

        assertEquals(1, catalog.resets)
        assertEquals(CatalogState.Restart, viewModel.uiState.value.catalogState)
    }
}
