package com.pockettravel.app.regions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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

/**
 * "Tutta la nazione" (o "Tutta la regione" per una parte di una nazione divisa, [splitCountry]) oppure
 * "Solo una zona, circa 60 × 45 km", per il foglio Contenuti.
 */
@Composable
internal fun zoneLabel(zone: RegionZone?, splitCountry: Boolean = false): String = if (zone == null) {
    stringResource(if (splitCountry) R.string.zone_whole_region else R.string.zone_whole_country)
} else {
    val (w, h) = boundsSizeKm(zone.minLon, zone.minLat, zone.maxLon, zone.maxLat)
    stringResource(R.string.zone_area, w, h)
}

/**
 * Prima di scaricare una regione grande: tutta o solo una zona. Toccare fuori chiude senza scaricare.
 * [splitCountry]: la regione e' una parte di una nazione divisa (groupName), e i testi dicono "regione" invece di "nazione".
 */
@Composable
internal fun ZoneChoiceDialog(
    displayName: String,
    onWholeRegion: () -> Unit,
    onPickZone: () -> Unit,
    onDismiss: () -> Unit,
    splitCountry: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Zone, contentDescription = null) },
        title = { Text(stringResource(if (splitCountry) R.string.zone_choice_title else R.string.zone_choice_title_country, displayName)) },
        text = { Text(stringResource(if (splitCountry) R.string.zone_choice_message else R.string.zone_choice_message_country)) },
        confirmButton = { TextButton(onClick = onPickZone) { Text(stringResource(R.string.zone_choice_pick)) } },
        dismissButton = {
            TextButton(onClick = onWholeRegion) {
                Text(stringResource(if (splitCountry) R.string.zone_whole_region else R.string.zone_whole_country))
            }
        },
    )
}

/**
 * Scelta della zona a schermo intero: la zona e' tutta la mappa visibile, limitata alla regione (per tornare a tutta la
 * regione basta inquadrarla intera). [download]: la regione non e' ancora installata e il pulsante la scarica.
 */
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
        // Senza titolo: in alto solo Indietro e la spiegazione.
        Scaffold { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Row(
                    // Alta come le barre dell'app (barra del titolo e barra di navigazione, 64 dp), come la barra in basso.
                    modifier = Modifier.fillMaxWidth().heightIn(min = TopAppBarDefaults.TopAppBarExpandedHeight).padding(start = Spacing.xs, end = Spacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(AppIcons.Back, contentDescription = stringResource(UiR.string.back)) }
                    Text(
                        stringResource(R.string.zone_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                ZonePickerMap(
                    tileSource = hiltViewModel<ZonePickerMapViewModel>().tileSource,
                    regionId = regionId,
                    initialBounds = initial,
                    onZoneChange = { current = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                // Misura della zona e pulsante sulla stessa riga.
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = TopAppBarDefaults.TopAppBarExpandedHeight).padding(horizontal = Spacing.l),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        // La misura e' della parte dentro la regione; la zona confermata e' tutta l'area (puo' prendere altri paesi).
                        current?.clampedTo(regionBbox)?.let { bounds ->
                            val (w, h) = boundsSizeKm(bounds.minLon, bounds.minLat, bounds.maxLon, bounds.maxLat)
                            Text(stringResource(R.string.zone_size, w, h), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
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

/**
 * Scelta della zona con la domanda per le zone che prendono anche altri paesi del catalogo: scaricare anche quelli, o
 * solo il paese piu' presente sullo schermo. [download]: la regione non e' ancora installata.
 */
@Composable
internal fun ZoneSelection(item: RegionUiItem, download: Boolean, actions: RegionRowActions, onDone: () -> Unit) {
    val bbox = item.bbox ?: return
    val scope = rememberCoroutineScope()
    var crossBorder by remember { mutableStateOf<Pair<RegionZone, List<Pair<String, String>>>?>(null) }
    // Si scarica quella aperta come richiesto, le altre sempre (sono nuove o vanno rifatte con la zona). Con "Scarica tutto"
    // ([keepWhole]) i paesi vicini gia' installati per intero restano com'erano; "Solo <paese>" e' una scelta esplicita.
    fun apply(zone: RegionZone, regionIds: List<String>, keepWhole: Boolean = false) {
        scope.launch {
            val whole = if (keepWhole) regionIds.filter { it != item.regionId && actions.isInstalledWhole(it) }.toSet() else emptySet()
            zoneTargets(item.regionId, regionIds, whole).forEach { id -> actions.onZoneChange(id, zone, id != item.regionId || download) }
            onDone()
        }
    }
    val pending = crossBorder
    if (pending == null) {
        ZonePickerDialog(
            regionId = item.regionId,
            regionBbox = bbox,
            zone = item.zone,
            download = download,
            onConfirm = { zone ->
                scope.launch {
                    val regions = actions.regionsInZone(zone)
                    if (regions.none { it.first != item.regionId }) apply(zone, listOf(item.regionId)) else crossBorder = zone to regions
                }
            },
            onDismiss = onDone,
        )
    } else {
        val (zone, regions) = pending
        AlertDialog(
            onDismissRequest = { crossBorder = null },
            icon = { Icon(AppIcons.Zone, contentDescription = null) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.zone_cross_message))
                    // I paesi toccati oltre a quello principale, uno per riga.
                    regions.filter { it.first != item.regionId }.forEach { (_, name) -> Text("•  $name", style = MaterialTheme.typography.bodyLarge) }
                }
            },
            // "Solo <paese>" nell'angolo a sinistra, "Scarica tutto" a destra: un solo slot a tutta larghezza.
            confirmButton = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { apply(zone, listOf(regions.first().first)) }) { Text(stringResource(R.string.zone_cross_top_only, regions.first().second)) }
                    TextButton(onClick = { apply(zone, regions.map { it.first }, keepWhole = true) }) { Text(stringResource(R.string.zone_cross_all)) }
                }
            },
        )
    }
}

/**
 * Le regioni a cui applicare la zona scelta da [openedId]: quella aperta sempre, le altre tranne quelle gia' installate
 * per intero ([installedWhole]), che la zona ridurrebbe (mappa e percorsi rifatti solo li').
 */
internal fun zoneTargets(openedId: String, regionIds: List<String>, installedWhole: Set<String>): List<String> =
    regionIds.filter { it == openedId || it !in installedWhole }

/** Una regione del catalogo per [zoneShares]: paese e riquadro. */
internal data class ZoneCandidate(val regionId: String, val countryCode: String, val bbox: RegionBbox)

/**
 * Quanta parte della zona cade in ogni regione del catalogo: una griglia di [samples] x [samples] punti, ognuno assegnato
 * alla regione del suo paese ([countryAt]) che lo contiene (la piu' piccola, se piu' d'una); mare e paesi fuori dal
 * catalogo non contano. Ordinate dalla piu' presente.
 */
internal fun zoneShares(
    zone: RegionZone,
    candidates: List<ZoneCandidate>,
    countryAt: (latitude: Double, longitude: Double) -> String?,
    samples: Int = 20,
): List<Pair<String, Int>> {
    val counts = HashMap<String, Int>()
    for (i in 0 until samples) {
        val lat = zone.minLat + (i + 0.5) * (zone.maxLat - zone.minLat) / samples
        for (j in 0 until samples) {
            val lon = zone.minLon + (j + 0.5) * (zone.maxLon - zone.minLon) / samples
            val country = countryAt(lat, lon) ?: continue
            val region = candidates
                .filter { it.countryCode.equals(country, ignoreCase = true) && lat in it.bbox.minLat..it.bbox.maxLat && lon in it.bbox.minLon..it.bbox.maxLon }
                .minByOrNull { (it.bbox.maxLat - it.bbox.minLat) * (it.bbox.maxLon - it.bbox.minLon) }
                ?: continue
            counts[region.regionId] = (counts[region.regionId] ?: 0) + 1
        }
    }
    return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
}
