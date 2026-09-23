package com.pockettravel.app.navigation

import androidx.lifecycle.ViewModel
import com.pockettravel.app.onboarding.OnboardingPreferences
import com.pockettravel.app.regions.RecentRegionPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// Calcolata una volta sola nel costruttore, mai piu' per la vita del NavHost (stesso principio
// del flag di onboarding in OnboardingViewModel — vedi commento su PocketTravelNavHost). La
// destinazione di partenza e' sempre l'elenco regioni (o l'onboarding): se l'app e' gia' in uso,
// PocketTravelNavHost apre sopra la mappa dell'ultima regione (initialRegionId), cosi' l'utente
// riparte da li' ma "Indietro" torna all'elenco invece di chiudere l'app.
@HiltViewModel
class StartDestinationViewModel @Inject constructor(
    onboardingPreferences: OnboardingPreferences,
    recentRegionPreferences: RecentRegionPreferences,
) : ViewModel() {
    private val onboardingCompleted = onboardingPreferences.isCompleted()

    val startDestination: String =
        if (onboardingCompleted) PocketTravelDestinations.REGIONS else PocketTravelDestinations.ONBOARDING

    val initialRegionId: String? = if (onboardingCompleted) recentRegionPreferences.lastRegionId() else null
}
