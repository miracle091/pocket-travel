package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.alerts.Alert
import com.pockettravel.core.data.alerts.AlertKind
import com.pockettravel.core.data.alerts.AlertLevel
import com.pockettravel.core.data.alerts.AlertsRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Notifica di una regione: la prima allerta e il numero delle altre. */
data class RegionAlertNotification(
    val regionId: String,
    val regionName: String,
    val firstAlert: Alert,
    val otherAlerts: Int,
)

/** Dove arrivano le notifiche decise da [AlertNotificationRunner]. */
fun interface AlertNotificationSink {
    fun show(notification: RegionAlertNotification)
}

/** Vero per le allerte che meritano una notifica: arancioni e rosse (HIGH, EXTREME) e gli tsunami. */
fun Alert.isNotifiable(): Boolean = when (level) {
    AlertLevel.HIGH, AlertLevel.EXTREME -> true
    AlertLevel.LOW, AlertLevel.MODERATE -> kind == AlertKind.TSUNAMI
}

/**
 * Un giro del controllo periodico: per ogni regione installata cerca le allerte da notificare non ancora notificate e
 * manda al più una notifica per regione. Un'allerta si notifica una sola volta: se tocca più regioni (fonti mondiali),
 * la notifica va alla prima.
 */
class AlertNotificationRunner @Inject constructor(
    private val regionRepository: RegionRepository,
    private val alertsRepository: AlertsRepository,
    private val preferences: AlertNotificationPreferences,
    private val sink: AlertNotificationSink,
    private val clock: Clock,
) {
    suspend fun run() {
        if (!preferences.enabled.value) return
        val now = clock.millis()
        preferences.forgetNotifiedBefore(now - MEMORY_MILLIS)
        regionRepository.observeInstalled().first().forEach { region ->
            val fresh = alertsRepository.alerts(region.regionId)?.alerts.orEmpty()
                .filter { it.isNotifiable() && !preferences.isNotified(it) }
            val first = fresh.firstOrNull() ?: return@forEach
            sink.show(RegionAlertNotification(region.regionId, region.displayName, first, fresh.size - 1))
            preferences.markNotified(fresh, now)
        }
    }

    private companion object {
        val MEMORY_MILLIS = TimeUnit.DAYS.toMillis(7)
    }
}
