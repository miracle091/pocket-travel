package com.pockettravel.feature.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import java.util.Date

/**
 * Sotto le partenze: quando e' arrivato il tempo reale, se l'ente non ha risposto, e "Aggiorna i ritardi" (con il peso
 * se su rete a consumo i feed grandi aspettano un tocco).
 */
@Composable
internal fun RealtimeStatus(realtime: TransitRealtimeUi, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val updatedAt = realtime.updatedAt
    when {
        updatedAt != null -> {
            val time = remember(updatedAt, context) { DateFormat.getTimeFormat(context).format(Date.from(updatedAt)) }
            Text(
                stringResource(R.string.transit_realtime_updated, time),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        realtime.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            PocketTravelLoadingIndicator()
            Text(stringResource(R.string.transit_realtime_loading), style = MaterialTheme.typography.bodyMedium)
        }
    }
    // Riquadro aperto da un quarto d'ora senza tocchi: i ritardi non si riscaricano da soli finche' non si tocca il pulsante.
    if (realtime.paused && !realtime.loading) {
        Text(
            stringResource(R.string.transit_realtime_paused),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Senza rete non c'e' niente da aggiornare: al ritorno della rete il ciclo del tabellone riparte da solo.
    if (realtime.problem == RealtimeProblem.Offline) {
        Text(stringResource(R.string.transit_realtime_offline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (realtime.failed && !realtime.loading) RealtimeFailure(realtime.problem)
    val size = realtime.tapSizeBytes
    TextButton(onClick = onRefresh, enabled = !realtime.loading) {
        Text(
            if (size != null && size > 0) {
                stringResource(R.string.transit_realtime_refresh_size, Formatter.formatShortFileSize(context, size))
            } else {
                stringResource(R.string.transit_realtime_refresh)
            },
        )
    }
}

/** Perche' i ritardi non sono aggiornati e, per il risparmio dati o energetico, il pulsante alle impostazioni. */
@Composable
private fun RealtimeFailure(problem: RealtimeProblem?) {
    val context = LocalContext.current
    val (message, settings) = when (problem) {
        RealtimeProblem.DataSaver ->
            R.string.transit_realtime_data_saver to (R.string.transit_realtime_data_saver_settings to dataSaverSettings(context.packageName))
        RealtimeProblem.BatterySaver ->
            R.string.transit_realtime_battery_saver to
                (R.string.transit_realtime_battery_saver_settings to Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        RealtimeProblem.Offline, RealtimeProblem.FeedUnreachable, null -> R.string.transit_realtime_failed to null
    }
    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    settings?.let { (label, intent) -> TextButton(onClick = { openSettings(context, intent) }) { Text(stringResource(label)) } }
}

private fun dataSaverSettings(packageName: String) =
    Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, "package:$packageName".toUri())

// Su alcuni telefoni la schermata non esiste: ripiega sulle impostazioni generali, e se manca anche quella non fa nulla.
private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
