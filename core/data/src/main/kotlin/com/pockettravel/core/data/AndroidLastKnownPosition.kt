package com.pockettravel.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** [LastKnownPosition] dal LocationManager di Android. */
class AndroidLastKnownPosition @Inject constructor(@ApplicationContext private val context: Context) : LastKnownPosition {
    override fun get(maxAgeMillis: Long): Pair<Double, Double>? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.takeIf { System.currentTimeMillis() - it.time <= maxAgeMillis }
                ?.let { it.latitude to it.longitude }
        } catch (_: SecurityException) {
            null
        }
    }
}
