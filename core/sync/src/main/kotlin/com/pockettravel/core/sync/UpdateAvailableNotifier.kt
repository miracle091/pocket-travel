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
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aggiornamenti regioni", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(APP_CHANNEL_ID, "Aggiornamenti app", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(AI_MODEL_CHANNEL_ID, "Aggiornamenti modello IA", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun notifyUpdateAvailable(entry: RegionManifestEntry) {
        val sizeMb = entry.sizeBytes / (1024 * 1024)
        notify(CHANNEL_ID, entry.regionId.hashCode(), "Aggiornamento disponibile", "${entry.displayName}, $sizeMb MB")
    }

    fun notifyAppUpdateAvailable(entry: AppVersionEntry) {
        notify(APP_CHANNEL_ID, APP_NOTIFICATION_ID, "Aggiornamento app disponibile", "Versione ${entry.versionName}")
    }

    fun notifyAiModelUpdateAvailable(entry: AiModelManifestEntry) {
        val sizeMb = entry.sizeBytes / (1024 * 1024)
        notify(AI_MODEL_CHANNEL_ID, AI_MODEL_NOTIFICATION_ID, "Aggiornamento modello IA disponibile", "${entry.modelVersion}, $sizeMb MB")
    }

    @SuppressLint("MissingPermission")
    private fun notify(channelId: String, notificationId: Int, title: String, text: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, channelId)
            // Icona di sistema come segnaposto: nessun asset reale ancora nel progetto,
            // stessa scelta della Fase 2 per il pin sulla mappa — fuori scope Fase 3.
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS non concesso: promemoria non critico, si salta in silenzio.
        }
    }

    private companion object {
        const val CHANNEL_ID = "region-updates"
        const val APP_CHANNEL_ID = "app-updates"
        const val AI_MODEL_CHANNEL_ID = "ai-model-updates"

        // ID fissi: a differenza delle regioni (tante, serve un ID per regionId), c'e' un solo
        // aggiornamento app e un solo modello IA possibili alla volta.
        const val APP_NOTIFICATION_ID = 1
        const val AI_MODEL_NOTIFICATION_ID = 2
    }
}
