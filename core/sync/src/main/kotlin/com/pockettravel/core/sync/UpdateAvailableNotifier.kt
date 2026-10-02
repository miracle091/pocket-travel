package com.pockettravel.core.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pockettravel.core.data.PackageKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Notifica non intrusiva "aggiornamento disponibile" — mai un download automatico. */
class UpdateAvailableNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_updates_regions_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(APP_CHANNEL_ID, context.getString(R.string.sync_updates_app_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(AI_MODEL_CHANNEL_ID, context.getString(R.string.sync_updates_ai_model_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun notifyUpdateAvailable(entry: RegionManifestEntry, kinds: Set<PackageKind>) {
        val sizeMb = entry.downloadBytes(kinds) / (1024 * 1024)
        notify(
            CHANNEL_ID,
            entry.regionId.hashCode(),
            context.getString(R.string.sync_update_region_title),
            context.getString(R.string.sync_update_region_text, entry.displayName, sizeMb),
        )
    }

    fun notifyAppUpdateAvailable(entry: AppVersionEntry) {
        notify(
            APP_CHANNEL_ID,
            APP_NOTIFICATION_ID,
            context.getString(R.string.sync_update_app_title),
            context.getString(R.string.sync_update_app_text, entry.versionName),
        )
    }

    fun notifyAiModelUpdateAvailable(entry: AiModelManifestEntry) {
        val sizeMb = entry.sizeBytes / (1024 * 1024)
        notify(
            AI_MODEL_CHANNEL_ID,
            AI_MODEL_NOTIFICATION_ID,
            context.getString(R.string.sync_update_ai_model_title),
            context.getString(R.string.sync_update_ai_model_text, entry.modelVersion, sizeMb),
        )
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
            // Icona di sistema come segnaposto: nessun asset dedicato nel progetto.
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
