package com.pockettravel.app.navigation

import androidx.lifecycle.SavedStateHandle
import com.pockettravel.app.onboarding.OnboardingStore
import com.pockettravel.app.regions.RecentRegionStore
import com.pockettravel.app.regions.RegionTab
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationViewModelTest {
    private val files = InMemoryPreferencesFactory()
    private val savedState = SavedStateHandle()

    private fun viewModel() = StartDestinationViewModel(OnboardingStore(files), RecentRegionStore(files), savedState)

    private fun appInUse(lastRegionId: String? = null) {
        OnboardingStore(files).markCompleted()
        lastRegionId?.let { RecentRegionStore(files).setLastRegionId(it) }
    }

    @Test
    fun `primo avvio parte dall'onboarding senza comandi, anche dalla notifica`() {
        val viewModel = viewModel()

        assertEquals(OnboardingRoute, viewModel.startDestination)
        assertEquals(emptyList<StartCommand>(), viewModel.startCommands(openNavigatorOnStart = true, isNavigating = true))
    }

    @Test
    fun `app in uso senza regione recente parte da Regioni senza comandi`() {
        appInUse()

        val viewModel = viewModel()

        assertEquals(RegionsRoute, viewModel.startDestination)
        assertEquals(emptyList<StartCommand>(), viewModel.startCommands(openNavigatorOnStart = false, isNavigating = false))
    }

    @Test
    fun `la regione recente si apre sulla mappa una volta sola`() {
        appInUse(lastRegionId = "toscana")
        val region = StartCommand.Open(RegionHubRoute("toscana", tab = RegionTab.MAP.key))

        assertEquals(listOf(region), viewModel().startCommands(openNavigatorOnStart = false, isNavigating = false))
        // Activity ricreata, anche dopo la morte del processo (stesso stato salvato): il back stack ha gia' la regione.
        assertEquals(emptyList<StartCommand>(), viewModel().startCommands(openNavigatorOnStart = false, isNavigating = false))
    }

    @Test
    fun `dalla notifica il Navigatore si apre dopo la regione recente, solo con una navigazione in corso`() {
        appInUse(lastRegionId = "toscana")
        val viewModel = viewModel()

        assertEquals(
            listOf(StartCommand.Open(RegionHubRoute("toscana", tab = RegionTab.MAP.key)), StartCommand.OpenTopLevel(NavigatorRoute)),
            viewModel.startCommands(openNavigatorOnStart = true, isNavigating = true),
        )
        assertEquals(emptyList<StartCommand>(), viewModel.startCommands(openNavigatorOnStart = true, isNavigating = false))
    }
}
