package com.pockettravel.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pockettravel.core.ui.Spacing
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Stato di un paese sulla mappa del mondo, deciso dall'elenco regioni del manifest. */
enum class CountryStatus { DOWNLOADED, AVAILABLE }

/**
 * Mappa del mondo leggera e offline: confini Natural Earth 1:50m inclusi nell'app
 * (assets/world, generati da tools/data-pipeline generateWorldMap), paesi colorati per stato
 * (scaricato / disponibile nel catalogo / non disponibile) e cliccabili. Non usa tile: funziona
 * anche senza nessuna regione scaricata.
 */
@Composable
fun WorldMap(
    countryStatus: Map<String, CountryStatus>,
    onCountryClick: (iso: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)
    val mapView = rememberMapViewWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val styleJson = remember(countryStatus, colors, dark) {
        worldStyleJson(
            countryStatus = countryStatus,
            ocean = if (dark) "#17344a" else "#a7cfe8",
            land = colors.surfaceDim.hex(),
            available = colors.primaryContainer.hex(),
            downloaded = colors.primary.hex(),
            border = colors.outline.hex(),
            label = colors.onSurface.hex(),
            labelHalo = colors.surface.hex(),
        )
    }
    val currentOnCountryClick by rememberUpdatedState(onCountryClick)
    var configuredStyle by remember { mutableStateOf<String?>(null) }
    var listenerAdded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { view ->
            view.getMapAsync { map ->
                if (!listenerAdded) {
                    listenerAdded = true
                    map.cameraPosition = CameraPosition.Builder().target(LatLng(25.0, 12.0)).zoom(0.8).build()
                    map.setMaxZoomPreference(6.0)
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.addOnMapClickListener { point ->
                        val features = map.queryRenderedFeatures(map.projection.toScreenLocation(point), "countries-fill")
                        val iso = features.firstNotNullOfOrNull { it.getStringProperty("iso") }
                        if (iso != null) currentOnCountryClick(iso)
                        iso != null
                    }
                }
                if (configuredStyle != styleJson) {
                    configuredStyle = styleJson
                    map.setStyle(Style.Builder().fromJson(styleJson))
                }
            }
        },
    )
    WorldMapLegend(modifier = Modifier.align(Alignment.TopStart).padding(Spacing.m))
    }
}

// Legenda dei tre stati: il colore da solo non basta (WCAG 1.4.1), servono le etichette.
@Composable
private fun WorldMapLegend(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainer.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            LegendRow(colors.primary, stringResource(R.string.world_legend_downloaded))
            LegendRow(colors.primaryContainer, stringResource(R.string.world_legend_available))
            LegendRow(colors.surfaceDim, stringResource(R.string.world_legend_unavailable))
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).background(color, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(modifier = Modifier.width(Spacing.s))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** MapView con il ciclo di vita legato a quello della schermata (condiviso da MapScreen e WorldMap). */
@Composable
internal fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return mapView
}

private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

internal fun worldStyleJson(
    countryStatus: Map<String, CountryStatus>,
    ocean: String,
    land: String,
    available: String,
    downloaded: String,
    border: String,
    label: String,
    labelHalo: String,
): String {
    fun isoList(status: CountryStatus) =
        countryStatus.filterValues { it == status }.keys.sorted().joinToString(",") { "\"$it\"" }
    val downloadedIsos = isoList(CountryStatus.DOWNLOADED)
    val availableIsos = isoList(CountryStatus.AVAILABLE)
    // "match" non accetta liste vuote: i rami si aggiungono solo se c'e' almeno un paese.
    val fillColor = if (downloadedIsos.isEmpty() && availableIsos.isEmpty()) "\"$land\"" else buildString {
        append("[\"match\", [\"get\", \"iso\"], ")
        if (downloadedIsos.isNotEmpty()) append("[$downloadedIsos], \"$downloaded\", ")
        if (availableIsos.isNotEmpty()) append("[$availableIsos], \"$available\", ")
        append("\"$land\"]")
    }
    return """
        {
          "version": 8,
          "glyphs": "asset://fonts/NotoSansRegular/{range}.pbf",
          "sources": {
            "countries": { "type": "geojson", "data": "asset://world/countries.geojson", "attribution": "Natural Earth" },
            "labels": { "type": "geojson", "data": "asset://world/country-labels.geojson" }
          },
          "layers": [
            { "id": "ocean", "type": "background", "paint": { "background-color": "$ocean" } },
            { "id": "countries-fill", "type": "fill", "source": "countries", "paint": { "fill-color": $fillColor } },
            { "id": "countries-line", "type": "line", "source": "countries", "paint": { "line-color": "$border", "line-width": ["interpolate", ["linear"], ["zoom"], 0, 0.3, 6, 1.2] } },
            { "id": "country-labels", "type": "symbol", "source": "labels",
              "filter": ["<=", ["get", "rank"], ["+", 2, ["*", 1.5, ["zoom"]]]],
              "layout": { "text-field": ["get", "name"], "text-font": ["NotoSansRegular"], "text-size": ["interpolate", ["linear"], ["zoom"], 0, 9, 6, 14], "symbol-sort-key": ["get", "rank"], "text-max-width": 7 },
              "paint": { "text-color": "$label", "text-halo-color": "$labelHalo", "text-halo-width": 1.2 } }
          ]
        }
    """.trimIndent()
}
