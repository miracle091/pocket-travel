package com.pockettravel.feature.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import com.pockettravel.core.ui.R as UiR

// "Programma un orario di arrivo": invece di partire subito si sceglie l'ora di arrivo, il Navigatore dice quando partire
// (durata del percorso con il modo scelto) e, se si vuole, avvisa con una notifica a quell'ora. Il
// riquadro cambia colore col tempo: neutro, poi "parti tra poco" a 5 minuti dalla partenza, rosso con i
// minuti di ritardo quando partendo adesso non si arriva piu' in tempo. TalkBack annuncia solo il cambio
// di stato (icona con una descrizione fissa per stato), non i minuti: il testo completo si legge al focus.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArriveBy(
    route: Route,
    arrival: LocalDateTime?,
    reminder: LocalDateTime?,
    onArrivalChange: (LocalDateTime?) -> Unit,
    onSetReminder: (LocalDateTime) -> Unit,
) {
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    // Notifiche spente (permesso negato o disattivate): l'avviso non si programma e lo si dice.
    var notificationsOff by rememberSaveable { mutableStateOf(false) }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val format = { time: LocalDateTime -> timeFormat.format(java.util.Date.from(time.atZone(ZoneId.systemDefault()).toInstant())) }
    // Partenza per cui si e' chiesto il permesso: serve alla risposta, che arriva dopo la scelta.
    var pendingDeparture by remember { mutableStateOf<LocalDateTime?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val departure = pendingDeparture
        pendingDeparture = null
        if (granted && departure != null) onSetReminder(departure) else notificationsOff = true
    }

    if (arrival == null) {
        // Senza il margine sinistro del TextButton l'icona si allinea al tempo e alla distanza sopra.
        TextButton(onClick = { picking = true }, contentPadding = PaddingValues(end = Spacing.l, top = Spacing.s, bottom = Spacing.s)) {
            Icon(ImageVector.vectorResource(UiR.drawable.ms_schedule), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.s))
            Text(stringResource(R.string.planner_arrive_by))
        }
    } else {
        // L'orologio avanza anche a schermo fermo: il riquadro passa da solo a "tra poco" e poi al ritardo.
        val now by produceState(LocalDateTime.now()) {
            while (true) {
                delay(15_000)
                value = LocalDateTime.now()
            }
        }
        val departure = departureFor(arrival, route.durationSeconds)
        val late = minutesLate(arrival, route.durationSeconds, now)
        val minutesToDeparture = minutesBetween(now, departure)
        val (container, content) = when {
            late > 0 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            minutesToDeparture < SOON_MINUTES -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }
        Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.m)) {
                // liveRegion sull'icona: TalkBack annuncia il passaggio a "tra poco" e al ritardo, non ogni minuto.
                val announcement = when {
                    late > 0 -> stringResource(R.string.navigation_running_late)
                    minutesToDeparture < 1 -> stringResource(R.string.planner_leave_now_on_time)
                    minutesToDeparture < SOON_MINUTES -> stringResource(R.string.planner_leave_soon)
                    else -> stringResource(R.string.planner_leave_at, format(departure))
                }
                Icon(
                    ImageVector.vectorResource(if (late > 0) UiR.drawable.ms_error else UiR.drawable.ms_schedule),
                    contentDescription = announcement,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(modifier = Modifier.width(Spacing.m))
                Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(
                        when {
                            late > 0 -> minutesPlural(R.plurals.navigation_late, late)
                            minutesToDeparture < 1 -> stringResource(R.string.planner_leave_now_on_time)
                            minutesToDeparture < SOON_MINUTES -> minutesPlural(R.plurals.planner_leave_in, minutesToDeparture)
                            else -> stringResource(R.string.planner_leave_at, format(departure))
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.planner_to_arrive_at, format(arrival)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = {
                    notificationsOff = false
                    onArrivalChange(null)
                }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.planner_arrive_by_clear))
                }
            }
        }
        if (departure.isAfter(now)) {
            if (reminder == null) {
                FilledTonalButton(onClick = {
                    notificationsOff = false
                    when {
                        NotificationManagerCompat.from(context).areNotificationsEnabled() -> onSetReminder(departure)
                        // Da Android 13 le notifiche vanno chieste; negate o spente dalle impostazioni, niente avviso.
                        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                            pendingDeparture = departure
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        else -> notificationsOff = true
                    }
                }) {
                    Icon(ImageVector.vectorResource(UiR.drawable.ms_notifications), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(stringResource(R.string.planner_remind_me))
                }
            } else {
                Text(
                    stringResource(R.string.planner_reminder_set, format(reminder)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
            }
            if (notificationsOff && reminder == null) {
                Text(
                    stringResource(R.string.planner_notifications_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    if (picking) {
        val now = LocalTime.now().plusMinutes((route.durationSeconds / 60).toLong() + 15)
        val pickerState = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.planner_arrive_by_title)) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    notificationsOff = false
                    onArrivalChange(arrivalFor(LocalTime.of(pickerState.hour, pickerState.minute)))
                    picking = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

// Sotto questi minuti dalla partenza il riquadro avvisa che e' quasi ora.
private const val SOON_MINUTES = 5
