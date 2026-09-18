package com.pockettravel.app.navigation

import androidx.lifecycle.ViewModel
import com.pockettravel.app.onboarding.OnboardingPreferences
import com.pockettravel.app.regions.RecentRegionPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Calcolata una volta sola nel costruttore, mai piu' per la vita del NavHost (stesso principio
// del flag di onboarding in OnboardingViewModel — vedi commento su PocketTravelNavHost): app
// gia' in uso -> si riparte dalla mappa dell'ultima regione aperta, cosi' l'utente non rivede
// l'elenco regioni ad ogni avvio; nessuna regione mai aperta (o mai installata) -> l'elenco
// regioni stesso, da cui scaricarne una.
@HiltViewModel
class StartDestinationViewModel @Inject constructor(
    onboardingPreferences: OnboardingPreferences,
    recentRegionPreferences: RecentRegionPreferences,
) : ViewModel() {
    val startDestination: String = when {
        !onboardingPreferences.isCompleted() -> PocketTravelDestinations.ONBOARDING
        else -> recentRegionPreferences.lastRegionId()
            ?.let { PocketTravelDestinations.regionHub(it, tab = "map") }
            ?: PocketTravelDestinations.REGIONS
    }
}
