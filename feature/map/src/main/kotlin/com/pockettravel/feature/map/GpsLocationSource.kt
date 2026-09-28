package com.pockettravel.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/** Posizione dal solo GPS del telefono: niente rete, niente Google Play Services. */
interface GpsLocationSource {
    fun hasPermission(): Boolean

    /** Se il GPS e' acceso, emesso subito e a ogni cambio delle impostazioni di localizzazione. */
    fun gpsEnabled(): Flow<Boolean>

    /** Posizioni del GPS, circa una al secondo; da raccogliere solo con il permesso. */
    fun fixes(): Flow<GpsFix>
}

class AndroidGpsLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : GpsLocationSource {
    private val locationManager = context.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun gpsEnabled(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            }
        }
        trySend(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        // NOT_EXPORTED: i broadcast di sistema come PROVIDERS_CHANGED arrivano comunque.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    // Il chiamante raccoglie questo flusso solo dopo hasPermission(): il lint non puo' saperlo.
    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<GpsFix> = callbackFlow {
        val listener = LocationListener { location ->
            trySend(GpsFix(location.latitude, location.longitude, location.accuracy, System.currentTimeMillis()))
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MILLIS, 0f, listener, Looper.getMainLooper())
        awaitClose { locationManager.removeUpdates(listener) }
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 1_000L
    }
}
