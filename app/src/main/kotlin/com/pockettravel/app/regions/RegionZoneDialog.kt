package com.pockettravel.app.regions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.pockettravel.app.R
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.map.MapBounds
import com.pockettravel.feature.map.ZonePickerMap
import com.pockettravel.feature.map.ZonePickerMapViewModel
import kotlin.math.cos
import kotlin.math.roundToInt
import com.pockettravel.core.ui.R as UiR

// Sopra questa superficie del riquadro (Italia ~1,3 milioni di km², Lettonia ~0,1) si propone di scaricare solo una zona.
private const val LARGE_REGION_KM2 = 200_000.0

private const val KM_PER_DEGREE = 111.32

/** Larghezza e altezza in km di un riquadro, a meta' della sua latitudine. */
internal fun boundsSizeKm(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): Pair<Int, Int> {
    val midLat = Math.toRadians((minLat + maxLat) / 2)
    return ((maxLon - minLon) * KM_PER_DEGREE * cos(midLat)).roundToInt() to ((maxLat - minLat) * KM_PER_DEGREE).roundToInt()
}

private fun MapBounds.clampedTo(bbox: RegionBbox): MapBounds? {
    val clamped = MapBounds(maxOf(minLon, bbox.minLon), maxOf(minLat, bbox.minLat), minOf(maxLon, bbox.maxLon), minOf(maxLat, bbox.maxLat))
    return clamped.takeIf { it.minLon < it.maxLon && it.minLat < it.maxLat }
}

/** Regione grande: si puo' scaricarne solo una zona (mappa, percorsi e civici). */
internal fun RegionBbox.isLarge(): Boolean = boundsSizeKm(minLon, minLat, maxLon, maxLat).let { (w, h) -> w.toDouble() * h > LARGE_REGION_KM2 }

/** "Tutto il paese" o "Solo una zona, circa 60 × 45 km" per il foglio Contenuti. */
@Composable
internal fun zoneLabel(zone: RegionZone?): String = if (zone == null) {
    stringResource(R.string.zone_whole_region)
} else {
    val (w, h) = boundsSizeKm(zone.minLon, zone.minLat, zone.maxLon, zone.maxLat)
    stringResource(R.string.zone_area, w, h)
}

/** Prima di scaricare una regione grande: tutta o solo una zona. Toccare fuori chiude senza scaricare. */
@Composable
internal fun ZoneChoiceDialog(displayName: String, onWholeRegion: () -> Unit, onPickZone: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Zone, contentDescription = null) },
        title = { Text(stringResource(R.string.zone_choice_title, displayName)) },
        text = { Text(stringResource(R.string.zone_choice_message)) },
        confirmButton = { TextButton(onClick = onPickZone) { Text(stringResource(R.string.zone_choice_pick)) } },
        dismissButton = { TextButton(onClick = onWholeRegion) { Text(stringResource(R.string.zone_whole_region)) } },
    )
}

/**
 * Scelta della zona a schermo intero: la zona e' tutta la mappa visibile, limitata alla regione (per tornare a tutta la
 * regione basta inquadrarla intera). [download]: la regione non e' ancora installata e il pulsante la scarica.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZonePickerDialog(
    regionId: String,
    regionBbox: RegionBbox,
    zone: RegionZone?,
    download: Boolean,
    onConfirm: (RegionZone) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember { mutableStateOf<MapBounds?>(null) }
    val initial = remember(zone, regionBbox) {
        zone?.let { MapBounds(it.minLon, it.minLat, it.maxLon, it.maxLat) }
            ?: MapBounds(regionBbox.minLon, regionBbox.minLat, regionBbox.maxLon, regionBbox.maxLat)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Senza barra in alto: solo la spiegazione; si esce col tasto Indietro.
        Scaffold { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Text(
                    stringResource(R.string.zone_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                )
                ZonePickerMap(
                    tileSource = hiltViewModel<ZonePickerMapViewModel>().tileSource,
                    regionId = regionId,
                    initialBounds = initial,
                    // Solo la parte dentro la regione: fuori non c'e' niente da scaricare.
                    onZoneChange = { current = it.clampedTo(regionBbox) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(Spacing.l)) {
                    current?.let { bounds ->
                        val (w, h) = boundsSizeKm(bounds.minLon, bounds.minLat, bounds.maxLon, bounds.maxLat)
                        Text(stringResource(R.string.zone_size, w, h), style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
                    ) {
                        Button(
                            onClick = { current?.let { onConfirm(RegionZone(it.minLon, it.minLat, it.maxLon, it.maxLat)) } },
                            enabled = current != null,
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(if (download) AppIcons.Download else AppIcons.Zone, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(if (download) R.string.zone_download else R.string.zone_use))
                        }
                    }
                }
            }
        }
    }
}
