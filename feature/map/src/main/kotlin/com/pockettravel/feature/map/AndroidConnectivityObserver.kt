package com.pockettravel.feature.map

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AndroidConnectivityObserver(private val connectivityManager: ConnectivityManager) : ConnectivityObserver {
    private val checker = AndroidConnectivityChecker(connectivityManager)

    override fun changes(): Flow<Boolean> = callbackFlow<Unit> {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager.registerNetworkCallback(request, callback)
        trySend(Unit)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.map { checker.isOnline() }.distinctUntilChanged()
}
