package com.pockettravel.feature.map

import kotlinx.coroutines.flow.Flow

/** Per la mappa del mondo online: letta solo se c'e' rete davvero utilizzabile, non solo un
 * Wi-Fi senza Internet dietro un captive portal. */
interface ConnectivityChecker {
    fun isOnline(): Boolean

    /** Rete a consumo (dati mobili, hotspot, Wi-Fi impostato "a consumo"): i feed grandi del tempo reale solo con un tocco. */
    fun isMetered(): Boolean = false
}

/** Segnala ogni cambio dello stato di rete utilizzabile (persa, ottenuta, o le sue capacita' cambiate,
 * es. dopo la convalida di un captive portal) per far ricalcolare la sorgente della mappa
 * (MapRouteViewModel) mentre la scheda Mappa resta aperta. Il valore e' [ConnectivityChecker.isOnline]:
 * le callback di Android arrivano anche quando cambia altro (intensita' del segnale, banda) e restano
 * senza effetto qui. */
interface ConnectivityObserver {
    fun changes(): Flow<Boolean>
}
