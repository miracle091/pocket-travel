package com.pockettravel.app.settings

import com.pockettravel.app.MainDispatcherRule
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.RoutingVariantChoice
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.sync.CatalogCheck
import com.pockettravel.core.sync.CustomCatalog
import com.pockettravel.core.sync.testing.FakeCatalogPreferences
import com.pockettravel.feature.map.MapFilterPreferences
import com.pockettravel.feature.map.NavigationPreferences
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    // Senza una scelta la nazionalita' viene dal telefono (Resources di sistema, assente nei test JVM): la si salva prima.
    private fun viewModel(nationality: String = "IT", saved: CustomCatalog? = null): SettingsViewModel {
        files.open("profile").edit().putString("nationality", nationality).apply()
        catalog.saved = saved
        return SettingsViewModel(
            themePreferences = ThemePreferences(files),
            usageModePreferences = UsageModePreferences(files, MapFilterPreferences(files), RoutingVariantPreferences(files)),
            nationalityPreferences = NationalityPreferences(files),
            navigationPreferences = NavigationPreferences(files),
            catalogPreferences = catalog,
            ioDispatcher = mainDispatcher.dispatcher,
        )
    }

    @Test
    fun `i valori iniziali sono quelli di fabbrica`() {
        val viewModel = viewModel()

        assertTrue(viewModel.useDynamicColor.value)
        assertFalse(viewModel.forceDark.value)
        assertEquals(emptySet<UsageMode>(), viewModel.usageModes.value)
        assertFalse(viewModel.accessible.value)
        assertFalse(viewModel.wantsDirections.value)
        assertTrue(viewModel.stopGpsOnArrival.value)
        assertTrue(viewModel.walkingHaptics.value)
        assertEquals("IT", viewModel.nationality.value)
        assertNull(viewModel.customCatalog)
        assertEquals(CatalogState.Idle, viewModel.catalogState.value)
    }

    @Test
    fun `i colori e il tema si cambiano e restano per la prossima apertura`() {
        val viewModel = viewModel()

        viewModel.setUseDynamicColor(false)
        viewModel.setForceDark(true)

        assertFalse(viewModel.useDynamicColor.value)
        assertTrue(viewModel.forceDark.value)
        val reopened = viewModel()
        assertFalse(reopened.useDynamicColor.value)
        assertTrue(reopened.forceDark.value)
    }

    @Test
    fun `la nazionalita' scelta sostituisce quella iniziale e resta`() {
        val viewModel = viewModel(nationality = "IT")

        viewModel.setNationality("FR")

        assertEquals("FR", viewModel.nationality.value)
        assertEquals("FR", NationalityPreferences(files).nationality.value)
    }

    @Test
    fun `i modi d'uso, la sedia a rotelle e le indicazioni si salvano a parte`() {
        val viewModel = viewModel()

        viewModel.setUsageModes(setOf(UsageMode.A_PIEDI, UsageMode.AUTO))
        viewModel.setAccessible(true)
        viewModel.setWantsDirections(true)

        assertEquals(setOf(UsageMode.A_PIEDI, UsageMode.AUTO), viewModel.usageModes.value)
        assertTrue(viewModel.accessible.value)
        assertTrue(viewModel.wantsDirections.value)
        viewModel.setAccessible(false)
        assertFalse(viewModel.accessible.value)
        assertTrue("gli altri valori non cambiano", viewModel.wantsDirections.value)
    }

    @Test
    fun `scegliere solo modi motorizzati imposta la rete stradale per l'auto`() {
        val viewModel = viewModel()

        viewModel.setUsageModes(setOf(UsageMode.AUTO))

        assertEquals(RoutingVariantChoice.CAR, RoutingVariantPreferences(files).choices.value.default)
    }

    @Test
    fun `svuotare i modi riporta la rete stradale a tutti i mezzi`() {
        val viewModel = viewModel()
        viewModel.setUsageModes(setOf(UsageMode.AUTO))

        viewModel.setUsageModes(emptySet())

        assertEquals(emptySet<UsageMode>(), viewModel.usageModes.value)
        assertEquals(RoutingVariantChoice.ALL, RoutingVariantPreferences(files).choices.value.default)
    }

    @Test
    fun `le preferenze del navigatore si cambiano`() {
        val viewModel = viewModel()

        viewModel.setStopGpsOnArrival(false)
        viewModel.setWalkingHaptics(false)

        assertFalse(viewModel.stopGpsOnArrival.value)
        assertFalse(viewModel.walkingHaptics.value)
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
        val check = CompletableDeferred<CatalogCheck>()
        catalog.result = { check.await() }

        viewModel.useCatalog("https://example.org/manifest.json", "chiave")
        runCurrent()

        assertEquals(CatalogState.Checking, viewModel.catalogState.value)
        check.complete(CatalogCheck.OK)
        advanceUntilIdle()
        assertEquals(CatalogState.Restart, viewModel.catalogState.value)
        assertEquals(listOf("https://example.org/manifest.json" to "chiave"), catalog.checked)
    }

    @Test
    fun `un catalogo non valido resta nelle Impostazioni con il motivo`() = runTest {
        val viewModel = viewModel()
        catalog.result = { CatalogCheck.BAD_SIGNATURE }

        viewModel.useCatalog("https://example.org/manifest.json", "chiave")
        advanceUntilIdle()

        assertEquals(CatalogState.Failed(CatalogCheck.BAD_SIGNATURE), viewModel.catalogState.value)
    }

    @Test
    fun `con una prova in corso un secondo tocco non ne avvia un'altra`() = runTest {
        val viewModel = viewModel()
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
        catalog.result = { CatalogCheck.UNREACHABLE }
        viewModel.useCatalog("https://example.org/manifest.json", "chiave")
        advanceUntilIdle()

        viewModel.clearCatalogError()
        assertEquals(CatalogState.Idle, viewModel.catalogState.value)

        catalog.result = { CatalogCheck.OK }
        viewModel.useCatalog("https://example.org/manifest.json", "chiave")
        advanceUntilIdle()
        viewModel.clearCatalogError()
        assertEquals(CatalogState.Restart, viewModel.catalogState.value)
    }

    @Test
    fun `dopo un errore si puo' riprovare`() = runTest {
        val viewModel = viewModel()
        catalog.result = { CatalogCheck.INVALID_KEY }
        viewModel.useCatalog("https://example.org/manifest.json", "sbagliata")
        advanceUntilIdle()

        catalog.result = { CatalogCheck.OK }
        viewModel.useCatalog("https://example.org/manifest.json", "giusta")
        advanceUntilIdle()

        assertEquals(CatalogState.Restart, viewModel.catalogState.value)
        assertEquals(2, catalog.checked.size)
    }

    @Test
    fun `tornare al catalogo ufficiale lo ripristina e chiede il riavvio`() = runTest {
        val viewModel = viewModel(saved = CustomCatalog("https://example.org/manifest.json", "chiave"))

        viewModel.useOfficialCatalog()
        advanceUntilIdle()

        assertEquals(1, catalog.resets)
        assertEquals(CatalogState.Restart, viewModel.catalogState.value)
    }
}
