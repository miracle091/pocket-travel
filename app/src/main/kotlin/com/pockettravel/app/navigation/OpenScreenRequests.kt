package com.pockettravel.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Schermata chiesta toccando una notifica di aggiornamento o di allerta (UpdateAvailableNotifier.EXTRA_OPEN_SCREEN): MainActivity la
 * scrive dall'intent, PocketTravelNavHost la apre e la azzera. Vale per l'activity gia' aperta e per quella appena creata.
 */
internal object OpenScreenRequests {
    val screen = MutableStateFlow<String?>(null)

    /** Scheda chiesta per la pagina della regione gia' aperta (RegionTab.key): RegionHubScreen la seleziona e la azzera. */
    val regionTab = MutableStateFlow<String?>(null)

    /** Valore di `EXTRA_OPEN_SCREEN` che apre le Impostazioni ("Chiedi all'assistente" senza motore disponibile). */
    const val OPEN_SETTINGS = "settings"
}
