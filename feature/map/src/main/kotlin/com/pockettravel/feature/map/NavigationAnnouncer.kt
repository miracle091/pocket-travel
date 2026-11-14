package com.pockettravel.feature.map

import com.pockettravel.core.speech.NavigationVoice
import com.pockettravel.core.speech.ReadAloudResult
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/**
 * Guida vocale e vibrazioni a piedi della navigazione in corso: a ogni posizione [VoiceCues] decide se c'e' qualcosa da
 * dire, la voce lo dice (con "Guida vocale" accesa) e a piedi la svolta e l'arrivo vibrano (con "Vibrazione a piedi").
 * Segue le posizioni del GPS, non lo schermo: funziona anche a schermo spento.
 */
class NavigationAnnouncer @Inject constructor(
    private val voice: NavigationVoice,
    private val vibration: NavigationVibration,
    private val texts: VoiceTexts,
    private val preferences: NavigationPreferences,
) {
    private var cues: VoiceCues? = null
    private var profile = RouteProfile.CAR

    fun begin(profile: RouteProfile) {
        this.profile = profile
        cues = VoiceCues(profile)
    }

    fun end() {
        cues = null
    }

    /** Accende la voce nella lingua dell'app: [ReadAloudResult.FINISHED] se e' pronta. */
    suspend fun startVoice(): ReadAloudResult = voice.start(texts.locale.language)

    fun stopVoice() = voice.stop()

    fun onProgress(route: Route, progress: NavigationProgress, street: String?, timeMillis: Long) {
        val cue = cues?.next(route, progress, street, timeMillis) ?: return
        if (preferences.voiceGuidance.value) voice.say(voicePhrase(cue, texts))
        if (profile != RouteProfile.WALK || !preferences.walkingHaptics.value) return
        when (cue) {
            is VoiceCue.Now -> vibration.turn(cue.instruction.type)
            VoiceCue.Arrived -> vibration.arrived()
            is VoiceCue.Follow, is VoiceCue.Prepare -> Unit
        }
    }

    /** "Raggiungi la fermata": la corsa delle [missed] non si prende piu', la prossima parte alle [next]. */
    fun onMissedDeparture(missed: LocalDateTime, next: LocalDateTime) {
        if (!preferences.voiceGuidance.value) return
        val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(texts.locale)
        voice.say(texts.string(R.string.voice_missed_departure, missed.format(clock), next.format(clock)))
    }
}
