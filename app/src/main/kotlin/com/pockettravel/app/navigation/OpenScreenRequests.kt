package com.pockettravel.app.navigation

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Schermata chiesta toccando una notifica di aggiornamento o di allerta (UpdateAvailableNotifier.EXTRA_OPEN_SCREEN): MainActivity la
 * scrive dall'intent, PocketTravelNavHost la apre e la azzera. Vale per l'activity gia' aperta e per quella appena creata.
 */
internal object OpenScreenRequests {
    val screen = MutableStateFlow<String?>(null)
}
