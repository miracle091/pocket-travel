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
import com.pockettravel.core.data.PreferencesFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Una sola notifica non intrusiva per tutti gli aggiornamenti disponibili (nazioni, app, modello IA) — mai un download
 * automatico. Ogni controllo sostituisce la sua parte dell'elenco salvato ([PendingUpdatesStore]) e la notifica si
 * rifà con lo stesso ID. Toccata apre sempre la schermata Aggiornamenti dell'app ([OPEN_UPDATES]).
 */
class UpdateAvailableNotifier internal constructor(
    private val context: Context,
    private val store: PendingUpdatesStore,
) {
    @Inject constructor(@ApplicationContext context: Context, preferences: PreferencesFactory) :
        this(context, PendingUpdatesStore(preferences))

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_updates_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Fino alla 0.15.0 un canale per nazioni, app e modello IA: toglierli toglie anche le loro vecchie notifiche.
        OLD_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
    }

    /** Le nazioni da aggiornare trovate dal controllo del catalogo, tutte insieme: quelle che non ci sono più escono dalla notifica. */
    internal fun setRegionUpdates(regions: List<RegionUpdateItem>) = update { it.copy(regions = regions.sortedBy(RegionUpdateItem::name)) }

    /** [entry]: la nuova versione dell'app, o null se quella installata è l'ultima. */
    fun setAppUpdate(entry: AppVersionEntry?) = update { it.copy(appVersion = entry?.versionName) }

    /** [entry]: il nuovo file del modello IA scaricato, o null se non c'è niente da aggiornare. */
    fun setAiModelUpdate(entry: AiModelManifestEntry?) = update { pending ->
        pending.copy(aiModelVersion = entry?.modelVersion)
    }

    /** Dopo un aggiornamento installato dall'app: Android chiude l'app per sostituirla e non la riapre, la notifica la riapre. */
    fun notifyAppUpdated(versionName: String) {
        update { it.copy(appVersion = null) }
        post(
            APP_UPDATED_NOTIFICATION_ID,
            context.getString(R.string.sync_app_updated_title, versionName),
            text = null,
            style = null,
            alert = true,
            openApp(screen = null),
        )
    }

    // Una chiamata alla volta: i tre controlli sono worker diversi e possono finire insieme.
    @Synchronized
    private fun update(change: (PendingUpdates) -> PendingUpdates) {
        val pending = change(store.load())
        store.save(pending)
        when (val action = updatesNotificationAction(pending, store.alertedKeys, isShowing(UPDATES_NOTIFICATION_ID))) {
            UpdatesNotificationAction.Cancel -> {
                NotificationManagerCompat.from(context).cancel(UPDATES_NOTIFICATION_ID)
                store.alertedKeys = emptySet()
            }
            UpdatesNotificationAction.Skip -> Unit
            is UpdatesNotificationAction.Post -> {
                postUpdates(pending, action.alert)
                store.alertedKeys = store.alertedKeys + pending.keys
            }
        }
    }

    private fun postUpdates(pending: PendingUpdates, alert: Boolean) {
        val lines = pending.regions.map(::regionLine) +
            listOfNotNull(
                pending.appVersion?.let { context.getString(R.string.sync_update_app_line, it) },
                pending.aiModelVersion?.let { context.getString(R.string.sync_update_ai_model_line, it) },
            )
        val title = context.resources.getQuantityString(R.plurals.sync_updates_available_title, pending.count, pending.count)
        val text = if (pending.count == 1) {
            lines.single()
        } else {
            val names = pending.regions.map { it.name } +
                listOfNotNull(
                    pending.appVersion?.let { context.getString(R.string.sync_update_app_name) },
                    pending.aiModelVersion?.let { context.getString(R.string.sync_update_ai_model_name) },
                )
            names.joinToString(", ")
        }
        val style = NotificationCompat.InboxStyle().also { inbox -> lines.forEach(inbox::addLine) }
        post(UPDATES_NOTIFICATION_ID, title, text, style.takeIf { pending.count > 1 }, alert, openApp(OPEN_UPDATES))
    }

    private fun regionLine(item: RegionUpdateItem): String =
        if (item.redownload) context.getString(R.string.sync_update_poi_redownload_text, item.name) else item.name

    private fun isShowing(notificationId: Int): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == notificationId }

    // L'activity di avvio dell'app (intent esplicito) con la schermata da aprire, se c'è: la legge MainActivity.
    private fun openApp(screen: String?): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        if (screen != null) intent.putExtra(EXTRA_OPEN_SCREEN, screen)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, screen.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission")
    private fun post(
        notificationId: Int,
        title: String,
        text: String?,
        style: NotificationCompat.Style?,
        alert: Boolean,
        contentIntent: PendingIntent?,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            // Senza novità (un aggiornamento in meno, lo stesso elenco) la notifica cambia senza suonare.
            .setOnlyAlertOnce(!alert)
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

        private const val CHANNEL_ID = "updates"
        private val OLD_CHANNEL_IDS = listOf("region-updates", "app-updates", "ai-model-updates")

        // ID fissi: una sola notifica per tutti gli aggiornamenti, e una per "app aggiornata".
        private const val UPDATES_NOTIFICATION_ID = 1
        private const val APP_UPDATED_NOTIFICATION_ID = 2
    }
}
