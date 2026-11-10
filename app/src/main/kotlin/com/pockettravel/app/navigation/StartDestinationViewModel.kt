package com.pockettravel.app.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.pockettravel.app.onboarding.OnboardingStore
import com.pockettravel.app.regions.RecentRegionStore
import com.pockettravel.app.regions.RegionTab
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Una navigazione che il NavHost esegue all'avvio, dopo aver mostrato la destinazione di partenza. */
sealed interface StartCommand {
    /** Sopra la destinazione di partenza, come un tocco sulla regione. */
    data class Open(val route: Any) : StartCommand

    /** Una destinazione della barra principale, come un tocco sulla barra. */
    data class OpenTopLevel(val route: Any) : StartCommand
}

// Calcolata una volta sola nel costruttore, mai piu' per la vita del NavHost (stesso principio
// del flag di onboarding in OnboardingViewModel — vedi commento su PocketTravelNavHost). La
// destinazione di partenza e' sempre l'elenco regioni (o l'onboarding): se l'app e' gia' in uso,
// PocketTravelNavHost apre sopra la mappa dell'ultima regione (initialRegionId), cosi' l'utente
// riparte da li' ma "Indietro" torna all'elenco invece di chiudere l'app.
@HiltViewModel
class StartDestinationViewModel @Inject constructor(
    onboardingStore: OnboardingStore,
    recentRegionStore: RecentRegionStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val onboardingCompleted = onboardingStore.isCompleted()

    val startDestination: Any =
        if (onboardingCompleted) RegionsRoute else OnboardingRoute

    val initialRegionId: String? = if (onboardingCompleted) recentRegionStore.lastRegionId() else null

    /**
     * I comandi dell'avvio, nell'ordine in cui eseguirli. App gia' in uso: la mappa dell'ultima regione sopra l'elenco, una
     * volta sola (non dopo una ricreazione dell'activity: il back stack e' gia' ripristinato). Activity aperta dalla notifica
     * della navigazione [openNavigatorOnStart]: dopo la regione, che altrimenti resterebbe in cima, il Navigatore, solo se
     * una navigazione e' in corso ([isNavigating]) e mai durante l'onboarding.
     */
    fun startCommands(openNavigatorOnStart: Boolean, isNavigating: Boolean): List<StartCommand> = buildList {
        if (savedState.get<Boolean>(KEY_INITIAL_REGION_OPENED) != true && initialRegionId != null) {
            savedState[KEY_INITIAL_REGION_OPENED] = true
            add(StartCommand.Open(RegionHubRoute(initialRegionId, tab = RegionTab.MAP.key)))
        }
        if (openNavigatorOnStart && startDestination != OnboardingRoute && isNavigating) {
            add(StartCommand.OpenTopLevel(NavigatorRoute))
        }
    }

    private companion object {
        const val KEY_INITIAL_REGION_OPENED = "initialRegionOpened"
    }
}
