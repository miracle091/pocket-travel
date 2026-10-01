package com.pockettravel.feature.map

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Preferenze del Navigatore, dalle Impostazioni. */
@Singleton
class NavigationPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("navigation", Context.MODE_PRIVATE)

    private val _stopGpsOnArrival = MutableStateFlow(prefs.getBoolean(KEY_STOP_GPS_ON_ARRIVAL, true))

    /**
     * All'arrivo la guida si chiude e il GPS si spegne da solo, anche con l'app in background (risparmia
     * batteria). Spento, la guida resta aperta col GPS acceso finche' non si tocca "Termina": utile se la
     * meta e' un punto di passaggio o se l'arrivo scatta qualche metro prima.
     */
    val stopGpsOnArrival: StateFlow<Boolean> = _stopGpsOnArrival.asStateFlow()

    fun setStopGpsOnArrival(stop: Boolean) {
        prefs.edit { putBoolean(KEY_STOP_GPS_ON_ARRIVAL, stop) }
        _stopGpsOnArrival.value = stop
    }

    private val _walkingHaptics = MutableStateFlow(prefs.getBoolean(KEY_WALKING_HAPTICS, true))

    /** Vibrazione leggera alle svolte e all'arrivo nella guida a piedi (NavigationHaptics). */
    val walkingHaptics: StateFlow<Boolean> = _walkingHaptics.asStateFlow()

    fun setWalkingHaptics(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WALKING_HAPTICS, enabled) }
        _walkingHaptics.value = enabled
    }

    /**
     * La guida in corso, salvata su disco finche' non si chiude: se il sistema chiude l'app (anche a schermo
     * spento), alla riapertura il Navigatore propone di riprenderla, sia dalle app recenti sia dall'icona.
     */
    fun saveActiveGuidance(guidance: ActiveGuidance) {
        prefs.edit {
            putString(KEY_GUIDANCE_PLACE, RecentDestinations.encode(listOf(guidance.place)))
            putString(KEY_GUIDANCE_REGION, guidance.regionId)
            putString(KEY_GUIDANCE_MODE, guidance.mode.name)
            putLong(KEY_GUIDANCE_ARRIVE_BY, guidance.arriveByEpochMinute ?: -1L)
            putLong(KEY_GUIDANCE_STARTED, guidance.startedAtMillis)
        }
    }

    /** La guida salvata, se c'e' e non e' troppo vecchia (dopo [ACTIVE_GUIDANCE_MAX_AGE_MILLIS] non ha piu' senso riprenderla). */
    fun activeGuidance(now: Long = System.currentTimeMillis()): ActiveGuidance? {
        val place = RecentDestinations.decode(prefs.getString(KEY_GUIDANCE_PLACE, null)).firstOrNull() ?: return null
        val started = prefs.getLong(KEY_GUIDANCE_STARTED, 0L)
        if (now - started > ACTIVE_GUIDANCE_MAX_AGE_MILLIS) {
            clearActiveGuidance()
            return null
        }
        return ActiveGuidance(
            regionId = prefs.getString(KEY_GUIDANCE_REGION, null) ?: place.regionId,
            place = place,
            mode = prefs.getString(KEY_GUIDANCE_MODE, null)?.let { name -> TravelMode.entries.firstOrNull { it.name == name } } ?: TravelMode.WALK,
            arriveByEpochMinute = prefs.getLong(KEY_GUIDANCE_ARRIVE_BY, -1L).takeIf { it >= 0 },
            startedAtMillis = started,
        )
    }

    fun clearActiveGuidance() = prefs.edit {
        remove(KEY_GUIDANCE_PLACE)
        remove(KEY_GUIDANCE_REGION)
        remove(KEY_GUIDANCE_MODE)
        remove(KEY_GUIDANCE_ARRIVE_BY)
        remove(KEY_GUIDANCE_STARTED)
    }

    private companion object {
        const val KEY_GUIDANCE_PLACE = "guidance_place"
        const val KEY_GUIDANCE_REGION = "guidance_region"
        const val KEY_GUIDANCE_MODE = "guidance_mode"
        const val KEY_GUIDANCE_ARRIVE_BY = "guidance_arrive_by"
        const val KEY_GUIDANCE_STARTED = "guidance_started"
        const val ACTIVE_GUIDANCE_MAX_AGE_MILLIS = 12 * 60 * 60 * 1000L
        const val KEY_STOP_GPS_ON_ARRIVAL = "stop_gps_on_arrival"
        const val KEY_WALKING_HAPTICS = "walking_haptics"
    }
}

/** Una guida avviata e non ancora chiusa (vedi [NavigationPreferences.saveActiveGuidance]). */
data class ActiveGuidance(
    val regionId: String,
    val place: NavigationPlace,
    val mode: TravelMode,
    // Ora di arrivo scelta, in minuti dall'epoca come ora locale (null se si e' partiti senza).
    val arriveByEpochMinute: Long?,
    val startedAtMillis: Long,
)
