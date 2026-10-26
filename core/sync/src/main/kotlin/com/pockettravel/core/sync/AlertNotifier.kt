package com.pockettravel.core.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pockettravel.core.ui.alertText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Mostra le notifiche delle allerte; toccata, la notifica apre l'app. */
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlertNotificationSink {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_alerts_channel), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    @SuppressLint("MissingPermission")
    override fun show(notification: RegionAlertNotification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Titolo nella lingua dell'app, come nel riquadro "Allerte in corso".
        val resources = context.resources
        val title = alertText(notification.firstAlert, resources, resources.configuration.locales[0]).title
        val text = if (notification.otherAlerts > 0) {
            context.getString(R.string.sync_alerts_text_more, title, notification.otherAlerts)
        } else {
            title
        }
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert_notification)
            .setContentTitle(notification.regionName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openRegion(notification.regionId))
            .build()
        try {
            NotificationManagerCompat.from(context).notify("$CHANNEL_ID:${notification.regionId}".hashCode(), built)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS non concesso: si salta in silenzio.
        }
    }

    // L'activity di avvio con la guida della regione da aprire, dove c'e' il riquadro "Allerte in corso". Un codice per
    // regione: con lo stesso codice Android riuserebbe l'intent di un'altra regione.
    private fun openRegion(regionId: String): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(UpdateAvailableNotifier.EXTRA_OPEN_SCREEN, OPEN_REGION_PREFIX + regionId)
        val requestCode = "$CHANNEL_ID:$regionId".hashCode()
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** Valore di EXTRA_OPEN_SCREEN per la guida di una regione: prefisso seguito dall'id della regione. */
        const val OPEN_REGION_PREFIX = "region:"

        private const val CHANNEL_ID = "alerts"
    }
}
