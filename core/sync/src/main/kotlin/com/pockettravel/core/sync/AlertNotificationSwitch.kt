package com.pockettravel.core.sync

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Interruttore "Notifiche delle allerte": salva la scelta e accende o spegne il controllo periodico. */
class AlertNotificationSwitch @Inject constructor(
    private val preferences: AlertNotificationPreferences,
    private val scheduler: AlertNotificationsScheduler,
) {
    val enabled: StateFlow<Boolean> = preferences.enabled

    /** Il permesso delle notifiche lo chiede la schermata prima di accendere. */
    fun set(enabled: Boolean) {
        preferences.setEnabled(enabled)
        scheduler.setScheduled(enabled)
    }
}
