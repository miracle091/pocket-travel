package com.pockettravel.feature.map

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pockettravel.core.ui.R as UiR
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.ceil

/**
 * L'ora di arrivo scelta nel selettore, fissata al momento della scelta: oggi, o domani se quell'ora di
 * oggi e' passata da piu' di un'ora. Un'ora appena passata e' un appuntamento gia' mancato (si mostra il
 * ritardo), non uno di domani. Da li' in poi resta quella, cosi' un ritardo si vede invece di slittare.
 */
internal fun arrivalFor(time: LocalTime, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
    val arrival = now.toLocalDate().atTime(time)
    return if (arrival.isBefore(now.minusHours(1))) arrival.plusDays(1) else arrival
}

/**
 * Partenza per arrivare entro [arrival] con un percorso di [durationSeconds], arrotondata al minuto prima. I
 * conti passano dal fuso [zone]: la notte del cambio dell'ora legale un'ora d'orologio non e' un'ora vera.
 */
internal fun departureFor(arrival: LocalDateTime, durationSeconds: Double, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime {
    val minutes = ceil(durationSeconds / 60).toLong().coerceAtLeast(1)
    return arrival.atZone(zone).minusMinutes(minutes).toLocalDateTime().withSecond(0).withNano(0)
}

/** Minuti veri da [from] a [to], ore locali nel fuso [zone] (anche a cavallo del cambio dell'ora legale). */
internal fun minutesBetween(from: LocalDateTime, to: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
    Duration.between(from.atZone(zone), to.atZone(zone)).toMinutes()

/**
 * Minuti di ritardo rispetto ad [arrival] partendo adesso con [durationSeconds] di strada: positivi in
 * ritardo (arrotondati per eccesso, anche pochi secondi sono un minuto), zero o negativi in orario.
 */
internal fun minutesLate(
    arrival: LocalDateTime,
    durationSeconds: Double,
    now: LocalDateTime = LocalDateTime.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    val seconds = Duration.between(arrival.atZone(zone), now.atZone(zone).plusSeconds(ceil(durationSeconds).toLong())).seconds
    return if (seconds > 0) ceil(seconds / 60.0).toLong() else -(-seconds / 60)
}

/** Quando suona l'avviso per la partenza [departure]: l'ora locale in millisecondi dall'epoca. */
internal fun reminderTriggerMillis(departure: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
    departure.atZone(zone).toInstant().toEpochMilli()

/**
 * Avvisi "e' ora di partire" del Navigatore: uno solo alla volta, l'ultimo sostituisce il precedente.
 * Con AlarmManager e non WorkManager: in Doze il ritardo di WorkManager e' di minuti, un avviso di partenza
 * deve suonare all'ora giusta. Il permesso SCHEDULE_EXACT_ALARM non e' dichiarato (su Play e' riservato
 * a sveglie e calendari): senza, canScheduleExactAlarms() e' falso e si usa setAndAllowWhileIdle, che in
 * Doze puo' tardare di qualche minuto ma non salta. Il riavvio del telefono cancella gli allarmi: l'avviso
 * resta salvato nelle preferenze e DepartureReminderBootReceiver lo riprogramma all'accensione.
 */
internal object DepartureReminder {
    private const val REQUEST_CODE = 4201

    private const val PREFS = "navigation_departure_reminder"
    private const val KEY_TRIGGER_AT = "trigger_at"
    private const val KEY_DESTINATION = "destination"

    fun schedule(context: Context, departure: LocalDateTime, destinationName: String) {
        val triggerAt = reminderTriggerMillis(departure)
        prefs(context).edit().putLong(KEY_TRIGGER_AT, triggerAt).putString(KEY_DESTINATION, destinationName).apply()
        setAlarm(context, triggerAt, destinationName)
    }

    /** All'accensione: riprogramma l'avviso salvato, o lo mostra subito se l'ora e' passata da poco a telefono spento. */
    fun restore(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        val triggerAt = prefs.getLong(KEY_TRIGGER_AT, 0L).takeIf { it > 0 } ?: return
        val destination = prefs.getString(KEY_DESTINATION, null).orEmpty()
        when (restoreAction(triggerAt, now)) {
            RestoreAction.SCHEDULE -> setAlarm(context, triggerAt, destination)
            RestoreAction.NOTIFY_NOW -> {
                clearSaved(context)
                DepartureReminderReceiver.notify(context, destination)
            }
            RestoreAction.DROP -> clearSaved(context)
        }
    }

    internal fun clearSaved(context: Context) = prefs(context).edit().clear().apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @SuppressLint("ScheduleExactAlarm")
    private fun setAlarm(context: Context, triggerAt: Long, destinationName: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, DepartureReminderReceiver::class.java).putExtra(DepartureReminderReceiver.KEY_DESTINATION, destinationName)
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context) {
        clearSaved(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, DepartureReminderReceiver::class.java)
        alarmManager.cancel(PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE))
    }
}

internal enum class RestoreAction { SCHEDULE, NOTIFY_NOW, DROP }

/**
 * Cosa fare all'accensione di un avviso salvato per [triggerAt]: ancora da venire, si riprogramma; passato da
 * meno di [MISSED_GRACE_MILLIS] (telefono spento all'ora giusta) si mostra subito; piu' vecchio non serve piu'.
 */
internal fun restoreAction(triggerAt: Long, now: Long): RestoreAction = when {
    triggerAt > now -> RestoreAction.SCHEDULE
    now - triggerAt <= MISSED_GRACE_MILLIS -> RestoreAction.NOTIFY_NOW
    else -> RestoreAction.DROP
}

private const val MISSED_GRACE_MILLIS = 30 * 60_000L

/** Riprogramma l'avviso di partenza dopo il riavvio del telefono (gli allarmi non sopravvivono allo spegnimento). */
class DepartureReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) DepartureReminder.restore(context)
    }
}

/** La notifica all'ora di partenza; toccandola si apre l'app. */
class DepartureReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DepartureReminder.clearSaved(context)
        notify(context, intent.getStringExtra(KEY_DESTINATION).orEmpty())
    }

    companion object {
        const val KEY_DESTINATION = "destination"
        private const val CHANNEL_ID = "navigation_departure"
        private const val NOTIFICATION_ID = 4201

        fun notify(context: Context, destination: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.planner_departure_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val content = launch?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(UiR.drawable.ms_navigation)
            .setContentTitle(context.getString(R.string.planner_departure_title))
            .setContentText(context.getString(R.string.planner_departure_text, destination))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // Permesso verificato sopra con areNotificationsEnabled (su Android 13+ e' il permesso di notifica).
        @Suppress("MissingPermission")
        manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
