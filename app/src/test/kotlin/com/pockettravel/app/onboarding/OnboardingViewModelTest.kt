package com.pockettravel.app.onboarding

import com.pockettravel.app.HintsReset
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.data.RoutingVariantChoice
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.feature.map.MapFilterPreferences
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {

    private val files = InMemoryPreferencesFactory()
    private var hintResets = 0

    // Senza una scelta la nazionalita' viene dal dispositivo (Resources di sistema, assente nei test JVM): la si salva prima.
    private fun viewModel(nationality: String? = "IT"): OnboardingViewModel {
        nationality?.let { files.open("profile").edit().putString("nationality", it).apply() }
        return OnboardingViewModel(
            onboardingPreferences = OnboardingPreferences(files),
            hintsReset = HintsReset { hintResets++ },
            usageModePreferences = UsageModePreferences(files, MapFilterPreferences(files), RoutingVariantPreferences(files)),
            nationalityPreferences = NationalityPreferences(files),
        )
    }

    @Test
    fun `all'inizio non c'e' nessuna scelta e l'onboarding non e' completato`() {
        val viewModel = viewModel()

        assertEquals(emptySet<UsageMode>(), viewModel.usageModes.value)
        assertFalse(viewModel.accessible.value)
        assertFalse(viewModel.wantsDirections.value)
        assertEquals("IT", viewModel.nationality.value)
        assertFalse(OnboardingPreferences(files).isCompleted())
        assertEquals(0, hintResets)
    }

    @Test
    fun `i modi scelti, la sedia a rotelle e le indicazioni restano per la prossima apertura`() {
        val viewModel = viewModel()

        viewModel.setUsageModes(setOf(UsageMode.A_PIEDI, UsageMode.CAMPER))
        viewModel.setAccessible(true)
        viewModel.setWantsDirections(true)

        assertEquals(setOf(UsageMode.A_PIEDI, UsageMode.CAMPER), viewModel.usageModes.value)
        assertTrue(viewModel.accessible.value)
        assertTrue(viewModel.wantsDirections.value)
        val reopened = viewModel()
        assertEquals(setOf(UsageMode.A_PIEDI, UsageMode.CAMPER), reopened.usageModes.value)
        assertTrue(reopened.accessible.value)
        assertTrue(reopened.wantsDirections.value)
    }

    @Test
    fun `i modi scelti decidono la rete stradale da scaricare`() {
        val viewModel = viewModel()

        viewModel.setUsageModes(setOf(UsageMode.AUTO, UsageMode.CAMPER))
        assertEquals(RoutingVariantChoice.CAR, RoutingVariantPreferences(files).choices.value.default)

        viewModel.setUsageModes(setOf(UsageMode.AUTO, UsageMode.A_PIEDI))
        assertEquals(RoutingVariantChoice.ALL, RoutingVariantPreferences(files).choices.value.default)
    }

    @Test
    fun `la nazionalita' scelta sostituisce quella del dispositivo e resta`() {
        val viewModel = viewModel(nationality = "IT")

        viewModel.setNationality("DE")

        assertEquals("DE", viewModel.nationality.value)
        assertEquals("DE", NationalityPreferences(files).nationality.value)
    }

    @Test
    fun `completare segna l'onboarding visto e fa tornare i suggerimenti delle schede`() {
        val viewModel = viewModel()

        viewModel.complete()

        assertTrue(OnboardingPreferences(files).isCompleted())
        assertEquals(1, hintResets)
    }

    @Test
    fun `rivedere il tutorial rimostra i suggerimenti a ogni completamento`() {
        val viewModel = viewModel()

        viewModel.complete()
        viewModel.complete()

        assertTrue(OnboardingPreferences(files).isCompleted())
        assertEquals(2, hintResets)
    }

    @Test
    fun `lasciare l'onboarding senza completarlo non tocca i suggerimenti`() {
        val viewModel = viewModel()

        viewModel.setUsageModes(setOf(UsageMode.A_PIEDI))
        viewModel.setNationality("FR")

        assertFalse(OnboardingPreferences(files).isCompleted())
        assertEquals(0, hintResets)
    }
}
