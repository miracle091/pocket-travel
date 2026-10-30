package com.pockettravel.feature.ai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.pockettravel.core.ui.R as UiR

/**
 * Servizio in primo piano vivo mentre l'assistente risponde ([AnswerKeepAlive.active]), con un wake lock parziale:
 * con lo schermo spento la risposta arriva completa e nello stesso tempo. Non genera nulla (la risposta resta nel
 * ViewModel): tiene sveglia la CPU e mostra la notifica con "Ferma".
 */
@AndroidEntryPoint
class AnswerService : Service() {
    @Inject lateinit var keepAlive: AnswerKeepAlive

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Importanza bassa: la notifica c'e' per tutta la risposta, senza suono ne' avviso sopra le app.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.ai_answer_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // "Ferma": la risposta si ferma nel ViewModel. Anche questo e' un avvio del servizio, con il suo id: senza
        // contarlo, stopSelfResult con l'id di prima non chiuderebbe il servizio.
        val stop = intent?.action == ACTION_STOP
        if (stop) keepAlive.requestStop()
        if (stop || startInForeground()) {
            lastStartId = startId
            if (!stop && wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { acquire(WAKE_LOCK_MAX_MILLIS) }
            }
            scope.launch {
                keepAlive.active.first { !it }
                // Solo se nessuna risposta nuova ha avviato di nuovo il servizio nel frattempo.
                stopSelfResult(lastStartId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    // Entro 5 secondi da startForegroundService: prima di tutto il resto. false se Android non lo consente: la risposta
    // continua, solo senza servizio.
    private fun startInForeground(): Boolean {
        // Il tipo del servizio esiste da Android 10: ServiceCompat lo ignora sulle versioni precedenti.
        @SuppressLint("InlinedApi")
        val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(), serviceType)
            true
        } catch (error: IllegalStateException) {
            // Da Android 12 non si parte da sfondo (ForegroundServiceStartNotAllowedException).
            Log.w(TAG, "Servizio della risposta non avviato", error)
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "Servizio della risposta non avviato", error)
            false
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    // Il contesto dell'applicazione ha la lingua scelta nell'app anche fino ad Android 12, quello del servizio no.
    private fun build(): Notification {
        val context = applicationContext
        // Con l'app gia' aperta la riporta in primo piano invece di aprirne un'altra.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = launch?.let { PendingIntent.getActivity(context, REQUEST_OPEN, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        // PendingIntent.getService e non getForegroundService: il servizio e' gia' in primo piano.
        val stop = PendingIntent.getService(
            context, REQUEST_STOP, Intent(context, AnswerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(UiR.drawable.ms_auto_awesome)
            .setContentTitle(context.getString(R.string.ai_answer_notification_title))
            .setContentIntent(content)
            .addAction(0, context.getString(R.string.ai_answer_notification_stop), stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.pockettravel.feature.ai.STOP_ANSWER"
        private const val CHANNEL_ID = "assistant_answer"
        private const val NOTIFICATION_ID = 4302
        private const val REQUEST_OPEN = 4302
        private const val REQUEST_STOP = 4303
        private const val WAKE_LOCK_TAG = "PocketTravel:answer"

        // Oltre il caricamento del 4B e i token massimi di una risposta a ~1 token/s su un telefono lento: un limite
        // che non scatta mai in una risposta vera, ma libera la CPU se il servizio non si chiudesse.
        private const val WAKE_LOCK_MAX_MILLIS = 15 * 60 * 1000L
        private val TAG = AnswerService::class.java.simpleName

        /** Dal primo piano (il tocco su Invia): da sfondo Android 12+ non consente di far partire un servizio in primo piano. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AnswerService::class.java))
            } catch (error: IllegalStateException) {
                // Da Android 12 non si parte da sfondo (ForegroundServiceStartNotAllowedException).
                Log.w(TAG, "Servizio della risposta non avviato", error)
            }
        }
    }
}
