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
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pockettravel.core.data.PackageKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Notifica non intrusiva "aggiornamento disponibile" — mai un download automatico. Toccata apre sempre la schermata
 * Aggiornamenti dell'app ([OPEN_UPDATES]), dove si aggiornano nazioni, guide e modello IA e si apre la pagina
 * dell'ultima release per l'app.
 */
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

    /**
     * [redownloadKinds]: i pacchetti che la migrazione del database ha tolto per mancanza di spazio (InstalledRegion.poiToRedownload).
     * Se fra gli aggiornamenti [kinds] ce ne sono, il testo dice che vanno riscaricati e il peso e' il loro.
     */
    fun notifyUpdateAvailable(entry: RegionManifestEntry, kinds: Set<PackageKind>, redownloadKinds: Set<PackageKind> = emptySet()) {
        val missing = redownloadKinds intersect kinds
        if (missing.isNotEmpty()) {
            notify(
                CHANNEL_ID,
                entry.regionId.hashCode(),
                context.getString(R.string.sync_update_poi_redownload_title),
                context.getString(R.string.sync_update_poi_redownload_text, entry.displayName, size(entry.downloadBytes(missing))),
                openApp(OPEN_UPDATES),
            )
            return
        }
        notify(
            CHANNEL_ID,
            entry.regionId.hashCode(),
            context.getString(R.string.sync_update_region_title),
            context.getString(R.string.sync_update_region_text, entry.displayName, size(entry.downloadBytes(kinds))),
            openApp(OPEN_UPDATES),
        )
    }

    fun notifyAppUpdateAvailable(entry: AppVersionEntry) {
        notify(
            APP_CHANNEL_ID,
            APP_NOTIFICATION_ID,
            context.getString(R.string.sync_update_app_title),
            context.getString(R.string.sync_update_app_text, entry.versionName),
            openApp(OPEN_UPDATES),
        )
    }

    fun notifyAiModelUpdateAvailable(entry: AiModelManifestEntry) {
        notify(
            AI_MODEL_CHANNEL_ID,
            AI_MODEL_NOTIFICATION_ID,
            context.getString(R.string.sync_update_ai_model_title),
            context.getString(R.string.sync_update_ai_model_text, entry.modelVersion, size(entry.sizeBytes)),
            openApp(OPEN_UPDATES),
        )
    }

    // Peso con l'unita' giusta (kB, MB, GB), come in Spazio di archiviazione: in MB interi una nazione piccola dava "0 MB".
    private fun size(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    // L'activity di avvio dell'app (intent esplicito) con la schermata da aprire: la legge MainActivity.
    private fun openApp(screen: String): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        intent.putExtra(EXTRA_OPEN_SCREEN, screen).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, screen.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission")
    private fun notify(channelId: String, notificationId: Int, title: String, text: String, contentIntent: PendingIntent?) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS non concesso: promemoria non critico, si salta in silenzio.
        }
    }

    companion object {
        /** Extra dell'intent di avvio: la schermata da aprire ([OPEN_UPDATES]). */
        const val EXTRA_OPEN_SCREEN = "com.pockettravel.OPEN_SCREEN"
        const val OPEN_UPDATES = "updates"

        private const val CHANNEL_ID = "region-updates"
        private const val APP_CHANNEL_ID = "app-updates"
        private const val AI_MODEL_CHANNEL_ID = "ai-model-updates"

        // ID fissi: a differenza delle regioni (tante, serve un ID per regionId), c'e' un solo
        // aggiornamento app e un solo modello IA possibili alla volta.
        private const val APP_NOTIFICATION_ID = 1
        private const val AI_MODEL_NOTIFICATION_ID = 2
    }
}
