package com.pockettravel.core.sync

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import com.pockettravel.core.data.alerts.Alert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifiche delle allerte con l'app chiusa (Altro > Impostazioni): interruttore, spento di fabbrica, e memoria delle
 * allerte già notificate, con l'ora della notifica, per non ripeterle.
 */
@Singleton
class AlertNotificationPreferences @Inject constructor(preferences: PreferencesFactory) {

    private val prefs = preferences.open("alert_notifications")
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        _enabled.value = enabled
    }

    fun isNotified(alert: Alert): Boolean = prefs.contains(notifiedKey(alert))

    fun markNotified(alerts: Collection<Alert>, nowMillis: Long) {
        prefs.edit { alerts.forEach { putLong(notifiedKey(it), nowMillis) } }
    }

    /** Toglie le allerte notificate prima di [limitMillis]: la memoria non cresce all'infinito. */
    fun forgetNotifiedBefore(limitMillis: Long) {
        val expired = prefs.all.filter { (key, value) -> key.startsWith(NOTIFIED_PREFIX) && value is Long && value < limitMillis }
        if (expired.isEmpty()) return
        prefs.edit { expired.keys.forEach { remove(it) } }
    }

    // L'id è stabile solo dentro la fonte.
    private fun notifiedKey(alert: Alert) = "$NOTIFIED_PREFIX${alert.source.name}:${alert.id}"

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val NOTIFIED_PREFIX = "notified:"
    }
}
