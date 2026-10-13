package com.pockettravel.feature.map

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class AndroidConnectivityChecker(private val connectivityManager: ConnectivityManager) : ConnectivityChecker {
    override fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
