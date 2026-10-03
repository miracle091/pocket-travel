package com.pockettravel.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * L'ultima posizione nota al telefono (GPS o rete), senza accendere nulla: per proporre le reti dei mezzi
 * pubblici vicine e le ambasciate piu' vicine nella guida. Null senza permesso di posizione o se il telefono non ne conosce una
 * (o, con [maxAgeMillis], nessuna abbastanza recente: i POI vicini dell'assistente non devono venire da un'altra citta').
 */
class LastKnownPosition @Inject constructor(@ApplicationContext private val context: Context) {
    fun get(maxAgeMillis: Long = Long.MAX_VALUE): Pair<Double, Double>? {
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
