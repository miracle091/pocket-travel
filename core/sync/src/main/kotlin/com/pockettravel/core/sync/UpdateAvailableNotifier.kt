package com.pockettravel.core.sync

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Notifica non intrusiva "aggiornamento disponibile" — mai un download automatico. */
class UpdateAvailableNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        val channel = NotificationChannel(CHANNEL_ID, "Aggiornamenti regioni", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun notifyUpdateAvailable(entry: RegionManifestEntry) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val sizeMb = entry.sizeBytes / (1024 * 1024)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // Icona di sistema come segnaposto: nessun asset reale ancora nel progetto,
            // stessa scelta della Fase 2 per il pin sulla mappa — fuori scope Fase 3.
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Aggiornamento disponibile")
            .setContentText("${entry.displayName}, $sizeMb MB")
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(entry.regionId.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS non concesso: promemoria non critico, si salta in silenzio.
        }
    }

    private companion object {
        const val CHANNEL_ID = "region-updates"
    }
}
