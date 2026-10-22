package com.pockettravel.feature.map

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
    if (realtime.failed && !realtime.loading) {
        Text(stringResource(R.string.transit_realtime_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
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
