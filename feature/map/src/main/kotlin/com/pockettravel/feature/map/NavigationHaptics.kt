package com.pockettravel.feature.map

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Le vibrazioni della navigazione a piedi (vedi [NavigationHaptics]), dietro un'interfaccia per i test. */
interface NavigationVibration {
    fun turn(type: TurnType)

    fun arrived()
}

class SystemNavigationVibration @Inject constructor(@ApplicationContext private val context: Context) : NavigationVibration {
    override fun turn(type: TurnType) = NavigationHaptics.turn(context, type)

    override fun arrived() = NavigationHaptics.arrived(context)
}

/**
 * Feedback aptico della navigazione a piedi, da sentire col dispositivo in mano senza guardare lo schermo: un
 * impulso lungo per la sinistra, due brevi per la destra, uno breve per le altre indicazioni, tre all'arrivo. In bici
 * e in auto no: li' non si sente o distrae.
 */
internal object NavigationHaptics {
    fun turn(context: Context, type: TurnType) = vibrate(
        context,
        when (turnSide(type)) {
            TurnSide.LEFT -> longArrayOf(0, LONG_PULSE_MS)
            TurnSide.RIGHT -> longArrayOf(0, PULSE_MS, GAP_MS, PULSE_MS)
            null -> longArrayOf(0, PULSE_MS)
        },
    )

    fun arrived(context: Context) = vibrate(context, longArrayOf(0, PULSE_MS, GAP_MS, PULSE_MS, GAP_MS, PULSE_MS))

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
        // Attributi audio della navigazione: senza, Android tratta l'impulso breve come feedback al tocco e
        // lo silenzia se le vibrazioni al tocco sono spente.
        @Suppress("DEPRECATION")
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), NAVIGATION_AUDIO)
    }

    private val NAVIGATION_AUDIO = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
    private const val PULSE_MS = 60L
    private const val LONG_PULSE_MS = 300L
    private const val GAP_MS = 120L
    private const val AMPLITUDE = 90
}

internal enum class TurnSide { LEFT, RIGHT }

// Il lato della svolta per la vibrazione; null per dritto, inversione, rotonda (l'uscita non e' un lato) e arrivo.
internal fun turnSide(type: TurnType): TurnSide? = when (type) {
    TurnType.SLIGHT_LEFT, TurnType.LEFT, TurnType.SHARP_LEFT, TurnType.KEEP_LEFT, TurnType.EXIT_LEFT -> TurnSide.LEFT
    TurnType.SLIGHT_RIGHT, TurnType.RIGHT, TurnType.SHARP_RIGHT, TurnType.KEEP_RIGHT, TurnType.EXIT_RIGHT -> TurnSide.RIGHT
    TurnType.CONTINUE, TurnType.U_TURN, TurnType.ROUNDABOUT, TurnType.ROUNDABOUT_LEFT, TurnType.ARRIVE -> null
}
