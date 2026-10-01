package com.pockettravel.core.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.pockettravel.core.data.PackageKind
import java.util.UUID

/** Cosa mostra la notifica del download: i file (byte) o, dopo, l'estrazione della mappa (byte di tile). */
internal sealed interface DownloadPhase {
    data class Files(val bytesDownloaded: Long, val totalBytes: Long) : DownloadPhase
    data class ExtractingMap(val bytesDone: Long, val bytesTotal: Long) : DownloadPhase
}

/** Percentuale 0..100 della barra; null (barra indeterminata) se il totale non e' ancora noto. */
internal fun downloadPercent(done: Long, total: Long): Int? =
    if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null

/** I pacchetti in un ordine fisso (quello di [PackageKind]), non quello in cui l'insieme li restituisce. */
internal fun orderedKinds(kinds: Set<PackageKind>): List<PackageKind> = PackageKind.entries.filter { it in kinds }

/**
 * Lascia passare un aggiornamento al massimo ogni [intervalMillis]: i progressi arrivano a ogni blocco
 * scaricato, aggiornare la notifica a ogni blocco la renderebbe lenta e il sistema la limiterebbe.
 * Chiamabile da piu' thread (il download e l'estrazione della mappa girano su thread diversi).
 */
internal class UpdateThrottle(private val intervalMillis: Long = 1_000L) {
    private var lastMillis: Long? = null

    @Synchronized
    fun tryAcquire(nowMillis: Long): Boolean {
        val last = lastMillis
        if (last != null && nowMillis - last < intervalMillis) return false
        lastMillis = nowMillis
        return true
    }
}

/** Notifica in primo piano del download di una regione, con barra di avanzamento e "Annulla". */
internal class RegionDownloadNotification(private val context: Context) {
    fun notificationId(regionId: String): Int = NOTIFICATION_ID_BASE + (regionId.hashCode() and 0xFFFF)

    fun build(workId: UUID, regionName: String, kinds: Set<PackageKind>, phase: DownloadPhase): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_download_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val packages = orderedKinds(kinds).joinToString(", ") { context.getString(label(it)) }
        val percent: Int?
        val text: String
        when (phase) {
            is DownloadPhase.Files -> {
                percent = downloadPercent(phase.bytesDownloaded, phase.totalBytes)
                text = if (phase.totalBytes > 0) {
                    context.getString(
                        R.string.sync_download_progress,
                        packages,
                        Formatter.formatShortFileSize(context, phase.bytesDownloaded),
                        Formatter.formatShortFileSize(context, phase.totalBytes),
                    )
                } else {
                    packages
                }
            }
            is DownloadPhase.ExtractingMap -> {
                percent = downloadPercent(phase.bytesDone, phase.bytesTotal)
                text = context.getString(R.string.sync_download_extracting_map, percent ?: 0)
            }
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(regionName)
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(0, context.getString(R.string.sync_download_cancel), WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun label(kind: PackageKind): Int = when (kind) {
        PackageKind.MAP -> R.string.sync_package_map
        PackageKind.ROUTING -> R.string.sync_package_routing
        PackageKind.POI -> R.string.sync_package_poi
        PackageKind.POI_EXTRA -> R.string.sync_package_poi_extra
        PackageKind.ADDRESSES -> R.string.sync_package_addresses
        PackageKind.CITIES -> R.string.sync_package_cities
        PackageKind.TRANSIT -> R.string.sync_package_transit
    }

    private companion object {
        const val CHANNEL_ID = "region-download"
        // Un ID per regione (piu' regioni possono scaricare insieme), lontano da quelli delle altre notifiche.
        const val NOTIFICATION_ID_BASE = 0x10000
    }
}
