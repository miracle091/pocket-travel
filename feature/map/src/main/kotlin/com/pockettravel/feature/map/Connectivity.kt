package com.pockettravel.feature.map

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Per la mappa del mondo online: letta solo se c'e' rete davvero utilizzabile, non solo un
 * Wi-Fi senza Internet dietro un captive portal. */
interface ConnectivityChecker {
    fun isOnline(): Boolean
}

class AndroidConnectivityChecker(private val connectivityManager: ConnectivityManager) : ConnectivityChecker {
    override fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** Segnala ogni cambio di rete (persa, ottenuta, o le sue capacita' cambiate, es. dopo la
 * convalida di un captive portal) per far ricalcolare la sorgente della mappa (MapRouteViewModel)
 * mentre la scheda Mappa resta aperta. Un solo valore ("qualcosa e' cambiato"): chi osserva rilegge
 * lo stato vero con [ConnectivityChecker.isOnline]. */
class ConnectivityObserver(private val connectivityManager: ConnectivityManager) {
    fun changes(): Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(request, callback)
        trySend(Unit)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
