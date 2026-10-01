package com.pockettravel.feature.map

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Feedback aptico della guida a piedi: un impulso breve e leggero alla svolta, due all'arrivo, da
 * sentire col telefono in mano senza guardare lo schermo. In bici e in auto no: li' non si sente o
 * distrae.
 */
internal object NavigationHaptics {
    fun turn(context: Context) = vibrate(context, longArrayOf(0, PULSE_MS))

    fun arrived(context: Context) = vibrate(context, longArrayOf(0, PULSE_MS, GAP_MS, PULSE_MS))

    private fun vibrate(context: Context, timings: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        // Intensita' bassa dove il motore la regola, altrimenti conta solo la brevita' dell'impulso.
        val amplitudes = IntArray(timings.size) { if (it % 2 == 1) AMPLITUDE else 0 }
        // Attributi da guida di navigazione: senza, Android tratta l'impulso breve come feedback al tocco e
        // lo silenzia se le vibrazioni al tocco sono spente.
        @Suppress("DEPRECATION")
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), NAVIGATION_AUDIO)
    }

    private val NAVIGATION_AUDIO = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
    private const val PULSE_MS = 60L
    private const val GAP_MS = 120L
    private const val AMPLITUDE = 90
}
