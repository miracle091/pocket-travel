package com.pockettravel.feature.map

import kotlin.math.ceil
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Velocita' tipiche (m/s) per il mezzo, finche' non c'e' una misura.
private const val WALK_TYPICAL_SPEED_MPS = 1.4
private const val BIKE_TYPICAL_SPEED_MPS = 4.5
private const val CAR_TYPICAL_SPEED_MPS = 12.0

// Stima della velocita': durata minima di una misura, velocita' massima plausibile e pesi della media mobile.
private const val MIN_MEASURE_SECONDS = 0.5
private const val MAX_PLAUSIBLE_SPEED_MPS = 70.0
private const val SPEED_KEEP_WEIGHT = 0.7
private const val SPEED_NEW_WEIGHT = 0.3

// Passo (m) degli annunci di TalkBack oltre i 50 m (prima della scala per la velocita').
private const val ANNOUNCEMENT_FAR_STEP_METERS = 100

/**
 * Velocita' effettiva lungo il percorso (m/s), dalla distanza rimasta che cala nel tempo, smussata perche' il
 * GPS salta. Finche' non c'e' una misura (primi secondi, fermi al semaforo) vale quella tipica del mezzo.
 */
@Composable
internal fun rememberSpeedMps(remainingMeters: Double, routeProfile: RouteProfile): Double {
    val holder = remember { SpeedEstimate() }
    SideEffect { holder.update(remainingMeters, System.nanoTime()) }
    return holder.speed ?: when (routeProfile) {
        RouteProfile.WALK -> WALK_TYPICAL_SPEED_MPS
        RouteProfile.BIKE -> BIKE_TYPICAL_SPEED_MPS
        RouteProfile.CAR -> CAR_TYPICAL_SPEED_MPS
    }
}

internal class SpeedEstimate {
    var speed: Double? = null
        private set
    private var lastMeters = Double.NaN
    private var lastNanos = 0L

    fun update(remainingMeters: Double, nanos: Long) {
        // Chiamata a ogni ricomposizione: senza una nuova posizione (stessa distanza rimasta) non e' una misura.
        if (remainingMeters == lastMeters) return
        if (!lastMeters.isNaN() && nanos > lastNanos) {
            val seconds = (nanos - lastNanos) / 1e9
            // Solo misure di almeno mezzo secondo e plausibili (ricalcoli e salti indietro si scartano).
            val measured = (lastMeters - remainingMeters) / seconds
            if (seconds >= MIN_MEASURE_SECONDS && measured in 0.0..MAX_PLAUSIBLE_SPEED_MPS) {
                speed = speed?.let { it * SPEED_KEEP_WEIGHT + measured * SPEED_NEW_WEIGHT } ?: measured
            }
        }
        lastMeters = remainingMeters
        lastNanos = nanos
    }
}

/**
 * La soglia (in metri) che TalkBack annuncia a [distanceMeters] dalla svolta: ogni 100 m, poi 50, 40, 30, 20,
 * 10 m; null sotto l'ultima (la svolta e' "Ora"). A [speedMps] piu' alte le soglie si moltiplicano (x2 in bici,
 * x5 in citta' in auto, x10 in strada veloce), cosi' l'annuncio arriva con un anticipo simile in secondi. Il
 * valore cambia solo al passaggio di una soglia: e' quello il momento in cui TalkBack lo rilegge.
 */
internal fun distanceAnnouncement(distanceMeters: Double, speedMps: Double): Int? {
    val scale = when {
        speedMps <= 2.5 -> 1
        speedMps <= 7.0 -> 2
        speedMps <= 20.0 -> 5
        else -> 10
    }
    val near = 10 * scale
    if (distanceMeters < near) return null
    val step = if (distanceMeters <= 50 * scale) near else ANNOUNCEMENT_FAR_STEP_METERS * scale
    return (ceil(distanceMeters / step) * step).toInt()
}
