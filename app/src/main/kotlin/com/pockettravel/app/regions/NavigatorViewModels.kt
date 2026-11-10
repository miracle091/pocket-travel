package com.pockettravel.app.regions

import androidx.compose.runtime.compositionLocalOf
import com.pockettravel.feature.map.NavigationPlannerViewModel
import com.pockettravel.feature.map.NavigationViewModel

/**
 * Pianificatore e navigazione, uno solo per tutta l'app (legati all'activity, non alla schermata): la scheda Navigatore della pagina della regione e
 * il Navigatore della barra mostrano la stessa destinazione e la stessa navigazione in corso, e una navigazione avviata da una
 * parte non compare dall'altra come "interrotta" (con un secondo GPS acceso).
 */
data class NavigatorViewModels(val planner: NavigationPlannerViewModel, val navigation: NavigationViewModel)

/** Li fornisce PocketTravelNavHost, che li prende dall'activity; le schermate dentro il NavHost li leggono da qui. */
val LocalNavigatorViewModels = compositionLocalOf<NavigatorViewModels> { error("NavigatorViewModels non forniti: servono dentro PocketTravelNavHost") }
