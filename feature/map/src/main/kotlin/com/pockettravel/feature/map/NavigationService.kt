package com.pockettravel.feature.map

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.IBinder
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pockettravel.core.ui.R as UiR
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Servizio in primo piano di tipo posizione, vivo mentre la guida e' attiva: con lo schermo spento o
 * l'app in secondo piano Android limita le posizioni alle app senza un servizio cosi', e la guida si
 * bloccherebbe. Non traccia nulla (GPS e percorso restano nel [NavigationViewModel]): tiene l'app in
 * primo piano e disegna la notifica da [NavigationSession.snapshot]; si ferma quando questo torna null.
 */
@AndroidEntryPoint
class NavigationService : Service() {
    @Inject lateinit var session: NavigationSession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null
    private val turnIcons = mutableMapOf<TurnType, Bitmap>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "Termina" dalla notifica: il ViewModel chiude la guida; la sessione si svuota anche se non c'e' piu'.
            session.requestStop()
            session.clear()
            stopSelf()
            return START_NOT_STICKY
        }
        // Entro 5 secondi da startForegroundService: prima di tutto il resto.
        val first = session.snapshot.value?.let(::render) ?: NotificationText(title = applicationContext.getString(R.string.navigation_waiting_fix))
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(first), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (error: Exception) {
            // Da 14 il tipo posizione richiede il permesso di posizione (SecurityException); da 12 non si parte da
            // sfondo. Senza servizio la guida continua con l'app aperta.
            Log.w(TAG, "Servizio di navigazione non avviato", error)
            stopSelf()
            return START_NOT_STICKY
        }
        if (observing == null) observing = observe(first)
        return START_NOT_STICKY
    }

    // Aggiorna la notifica solo se il testo cambia e al massimo una volta al secondo: collectLatest scarta
    // l'attesa di un aggiornamento superato, e a ogni nuovo valore il tempo da aspettare si ricalcola.
    private fun observe(shownFirst: NotificationText) = scope.launch {
        var shown: NotificationText? = shownFirst
        var lastPostMillis: Long? = SystemClock.elapsedRealtime()
        session.snapshot.map { it?.let(::render) }.distinctUntilChanged().collectLatest { text ->
            if (text == null) {
                stopSelf()
                return@collectLatest
            }
            if (text == shown) return@collectLatest
            val wait = throttleWaitMillis(lastPostMillis, SystemClock.elapsedRealtime())
            if (wait > 0) delay(wait)
            shown = text
            lastPostMillis = SystemClock.elapsedRealtime()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // Il contesto dell'applicazione ha la lingua scelta nell'app anche fino ad Android 12, quello del servizio no.
    private fun render(snapshot: NavigationSnapshot): NotificationText {
        val context = applicationContext
        val turn = snapshot.turn
            ?: return NotificationText(
                title = context.getString(if (snapshot.waitingForFix) R.string.navigation_waiting_fix else R.string.navigation_calculating),
                text = context.getString(R.string.navigation_title, snapshot.destinationName),
            )
        val distance = if (snapshot.distanceToNextMeters < NOW_METERS) context.getString(R.string.navigation_now) else distanceText(context, snapshot.distanceToNextMeters)
        // All'arrivo la distanza dalla meta non dice nulla: solo "Arrivo".
        val title = if (turn.type == TurnType.ARRIVE && snapshot.distanceToNextMeters < NOW_METERS) turnText(context, turn) else "$distance · ${turnText(context, turn)}"
        val minutes = remainingMinutes(snapshot.remainingSeconds)
        val duration = if (minutes < 60) context.getString(R.string.navigation_minutes, minutes) else context.getString(R.string.navigation_hours_minutes, minutes / 60, minutes % 60)
        val arrival = DateFormat.getTimeFormat(context).format(Date(arrivalMinute(System.currentTimeMillis(), snapshot.remainingSeconds) * 60_000))
        return NotificationText(
            title = title,
            text = snapshot.street ?: context.getString(R.string.navigation_title, snapshot.destinationName),
            subText = context.getString(R.string.navigation_distance_arrival, duration, arrival),
            turn = turn.type,
            chip = distance,
        )
    }

    private fun build(text: NotificationText): Notification {
        val context = applicationContext
        val manager = getSystemService(NotificationManager::class.java)
        // Importanza bassa: aggiornata di continuo, non deve suonare ne' comparire come avviso sopra le app.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.navigation_channel), NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // Con l'app gia' aperta la riporta in primo piano (onNewIntent di MainActivity) invece di aprirne un'altra.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(EXTRA_OPEN_NAVIGATOR, true)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = launch?.let { PendingIntent.getActivity(context, REQUEST_OPEN, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        // PendingIntent.getService e non getForegroundService: il servizio e' gia' in primo piano.
        val stop = PendingIntent.getService(
            context, REQUEST_STOP, Intent(context, NavigationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(UiR.drawable.ms_navigation)
            .setLargeIcon(text.turn?.let(::turnIcon))
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setSubText(text.subText)
            .setContentIntent(content)
            .addAction(0, context.getString(R.string.navigation_notification_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            // La svolta si legge anche a telefono bloccato.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 16: "Live Update", come la navigazione di Google Maps. La notifica resta in cima e sulla
            // schermata di blocco, e la distanza dalla svolta compare nel chip della barra di stato. Sulle versioni
            // precedenti questi due campi sono ignorati.
            .setRequestPromotedOngoing(true)
            .apply { text.chip?.let(::setShortCriticalText) }
            .build()
    }

    // La freccia della guida (stesse icone) in bianco su un cerchio scuro: leggibile con il tema chiaro e scuro.
    private fun turnIcon(type: TurnType): Bitmap = turnIcons.getOrPut(type) {
        val size = (LARGE_ICON_DP * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ICON_BACKGROUND })
        ContextCompat.getDrawable(this, turnIconRes(type))?.mutate()?.let { arrow ->
            arrow.setTint(Color.WHITE)
            val inset = size / 6
            arrow.setBounds(inset, inset, size - inset, size - inset)
            arrow.draw(canvas)
        }
        bitmap
    }

    // Testo gia' composto: confrontarlo evita di riaggiornare la notifica quando non cambia nulla.
    private data class NotificationText(val title: String, val text: String? = null, val subText: String? = null, val turn: TurnType? = null, val chip: String? = null)

    companion object {
        /** Extra del lancio dalla notifica: MainActivity porta l'hub sulla scheda Navigatore. */
        const val EXTRA_OPEN_NAVIGATOR = "com.pockettravel.feature.map.OPEN_NAVIGATOR"

        private const val ACTION_STOP = "com.pockettravel.feature.map.STOP_NAVIGATION"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 4202
        private const val REQUEST_OPEN = 4202
        private const val REQUEST_STOP = 4203
        private const val LARGE_ICON_DP = 48
        private const val ICON_BACKGROUND = 0xFF37474F.toInt()
        // Sotto i 10 m la svolta e' adesso, come nella guida a schermo.
        private const val NOW_METERS = 10.0
        private val TAG = NavigationService::class.java.simpleName

        /** Dal primo piano (l'avvio della guida): da sfondo Android 12+ non consente di far partire un servizio in primo piano. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            } catch (error: Exception) {
                Log.w(TAG, "Servizio di navigazione non avviato", error)
            }
        }

        private fun distanceText(context: Context, meters: Double): String = when (val label = distanceLabel(meters)) {
            is DistanceLabel.Meters -> context.getString(R.string.navigation_meters, label.value)
            is DistanceLabel.Kilometers -> {
                val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
                context.getString(R.string.navigation_kilometers, format.format(label.value))
            }
        }

        private fun turnText(context: Context, instruction: TurnInstruction): String = when (instruction.type) {
            TurnType.CONTINUE -> context.getString(R.string.turn_continue)
            TurnType.SLIGHT_LEFT -> context.getString(R.string.turn_slight_left)
            TurnType.LEFT -> context.getString(R.string.turn_left)
            TurnType.SHARP_LEFT -> context.getString(R.string.turn_sharp_left)
            TurnType.SLIGHT_RIGHT -> context.getString(R.string.turn_slight_right)
            TurnType.RIGHT -> context.getString(R.string.turn_right)
            TurnType.SHARP_RIGHT -> context.getString(R.string.turn_sharp_right)
            TurnType.KEEP_LEFT -> context.getString(R.string.turn_keep_left)
            TurnType.KEEP_RIGHT -> context.getString(R.string.turn_keep_right)
            TurnType.EXIT_LEFT -> context.getString(R.string.turn_exit_left)
            TurnType.EXIT_RIGHT -> context.getString(R.string.turn_exit_right)
            TurnType.U_TURN -> context.getString(R.string.turn_u_turn)
            TurnType.ROUNDABOUT, TurnType.ROUNDABOUT_LEFT -> context.getString(R.string.turn_roundabout, instruction.roundaboutExit)
            TurnType.ARRIVE -> context.getString(R.string.turn_arrive)
        }

        // Le stesse frecce di turnIcon() della guida a schermo (che e' un composable: qui servono gli id).
        private fun turnIconRes(type: TurnType): Int = when (type) {
            TurnType.CONTINUE -> UiR.drawable.ms_straight
            TurnType.SLIGHT_LEFT -> UiR.drawable.ms_turn_slight_left
            TurnType.LEFT -> UiR.drawable.ms_turn_left
            TurnType.SHARP_LEFT -> UiR.drawable.ms_turn_sharp_left
            TurnType.SLIGHT_RIGHT -> UiR.drawable.ms_turn_slight_right
            TurnType.RIGHT -> UiR.drawable.ms_turn_right
            TurnType.SHARP_RIGHT -> UiR.drawable.ms_turn_sharp_right
            TurnType.KEEP_LEFT -> UiR.drawable.ms_fork_left
            TurnType.KEEP_RIGHT -> UiR.drawable.ms_fork_right
            TurnType.EXIT_LEFT -> UiR.drawable.ms_ramp_left
            TurnType.EXIT_RIGHT -> UiR.drawable.ms_ramp_right
            TurnType.U_TURN -> UiR.drawable.ms_u_turn_left
            TurnType.ROUNDABOUT -> UiR.drawable.ms_roundabout_right
            TurnType.ROUNDABOUT_LEFT -> UiR.drawable.ms_roundabout_left
            TurnType.ARRIVE -> UiR.drawable.ms_flag
        }
    }
}
