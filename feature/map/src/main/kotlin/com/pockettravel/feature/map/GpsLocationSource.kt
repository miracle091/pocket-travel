package com.pockettravel.feature.map

import kotlinx.coroutines.flow.Flow

/** Posizione dal solo GPS del dispositivo: niente rete, niente Google Play Services. */
interface GpsLocationSource {
    fun hasPermission(): Boolean

    /** Se il GPS e' acceso, emesso subito e a ogni cambio delle impostazioni di localizzazione. */
    fun gpsEnabled(): Flow<Boolean>

    /** Posizioni del GPS, circa una al secondo; da raccogliere solo con il permesso. */
    fun fixes(): Flow<GpsFix>
}
