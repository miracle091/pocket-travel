package com.pockettravel.core.sync

import android.app.Notification
import android.content.Context
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import java.util.UUID

private const val PERCENT_MAX = 100

/** Notifica in primo piano del download dell'APK, con barra di avanzamento e "Annulla". */
internal class AppUpdateDownloadNotification(private val context: Context) {
    private val channelId = createDownloadChannel(context)

    fun build(workId: UUID, bytesDownloaded: Long, totalBytes: Long): Notification {
        val percent = downloadPercent(bytesDownloaded, totalBytes)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(context.getString(R.string.sync_app_update_title))
            .setProgress(PERCENT_MAX, percent ?: 0, percent == null)
            .addAction(0, context.getString(R.string.sync_download_cancel), WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (totalBytes > 0) {
            builder.setContentText(
                "${Formatter.formatShortFileSize(context, bytesDownloaded)} / ${Formatter.formatShortFileSize(context, totalBytes)}",
            )
        }
        return builder.build()
    }
}
