package com.pockettravel.feature.map

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager

class AndroidConnectivityChecker(
    private val connectivityManager: ConnectivityManager,
    private val powerManager: PowerManager? = null,
) : ConnectivityChecker {
    override fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun isMetered(): Boolean = connectivityManager.isActiveNetworkMetered

    override fun isDataSaverRestricting(): Boolean =
        connectivityManager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    override fun isPowerSaveMode(): Boolean = powerManager?.isPowerSaveMode == true
}
