package com.pockettravel.feature.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pockettravel.core.data.PoiCategory
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.PoiColors
import com.pockettravel.core.ui.Spacing
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

private const val PIN_ICON_PREFIX = "pocket-travel-pin-"

private fun iconIdFor(category: PoiCategory) = PIN_ICON_PREFIX + category.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(tileSource: OfflineTileSource, regionId: String, pins: List<MapPin> = emptyList()) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)

    val mapView = rememberMapViewWithLifecycle()
    // Stile scuro quando l'app e' in tema scuro (segue il tema effettivo, non solo il sistema).
    val darkMap = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val styleJson = remember(tileSource, regionId, darkMap) { tileSource.styleJson(regionId, dark = darkMap) }
    var configuredStyle by remember { mutableStateOf<String?>(null) }
    var symbolManager by remember { mutableStateOf<SymbolManager?>(null) }
    var selectedCategories by remember { mutableStateOf(PoiCategory.entries.toSet()) }
    var symbolPinMap by remember { mutableStateOf<Map<Long, MapPin>>(emptyMap()) }
    var selectedPin by remember { mutableStateOf<MapPin?>(null) }
    var cameraFitted by remember { mutableStateOf(false) }
    val visiblePins = pins.filter { it.category in selectedCategories }
    val presentCategories = PoiCategory.entries.filter { category -> pins.any { it.category == category } }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                if (configuredStyle != styleJson) {
                    configuredStyle = styleJson
                    view.getMapAsync { map ->
                        // Basso, non il default (4 livelli): con le tile pmtiles:// gia' tutte in
                        // locale (l'intero z0-z14 scaricato all'installazione da PmtilesExtractor),
                        // un placeholder piu' vicino allo zoom target non costa una richiesta di
                        // rete in piu' come costerebbe con tile remote, solo un parsing leggermente
                        // anticipato di una tile che verra' comunque renderizzata.
                        map.setPrefetchZoomDelta(1)
                        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                            PoiCategory.entries.forEach { category ->
                                style.addImage(iconIdFor(category), poiPinBitmap(context, category))
                            }
                            // Cambio di stile (tema chiaro/scuro): il vecchio SymbolManager e'
                            // legato allo stile precedente, va chiuso prima di crearne uno nuovo.
                            symbolManager?.onDestroy()
                            symbolManager = SymbolManager(view, map, style).apply {
                                addClickListener { symbol ->
                                    selectedPin = symbolPinMap[symbol.id]
                                    true
                                }
                            }
                            symbolPinMap = renderPins(symbolManager, visiblePins)
                        }
                    }
                }
                symbolPinMap = renderPins(symbolManager, visiblePins)

                // PmtilesExtractor scarica solo le tile che intersecano il bounding box della
                // regione (vedi core:sync/PmtilesExtractor.tileRangeFor), per tenere piccolo il
                // pacchetto — ai livelli di zoom bassi una singola tile copre un'area enorme, e le
                // tile "vicine" che non toccano il bbox non vengono mai scaricate. La mappa parte
                // pero' sempre dalla vista mondo (nessun fit iniziale): zoomando manualmente da li'
                // verso la regione si attraversa una fascia di zoom bassa dove meta' schermo mostra
                // il solo "background" (bug osservato: "carica sempre a sezioni quando faccio lo
                // zoom" — non tile che arrivano in ritardo, tile che semplicemente non esistono
                // nel pacchetto scaricato). Il fix reale e' non passarci mai: centrare/zoomare la
                // camera sui pin della regione (gia' ben dentro il bbox estratto) non appena sono
                // disponibili, cosi' l'utente apre la mappa gia' inquadrato sull'area completa
                // invece di doverci arrivare a mano dalla vista mondo. Una tantum (guardia
                // cameraFitted): dopo il primo fit l'utente deve restare libero di ripristinare la
                // vista mondo senza che ogni ricomposizione lo forzi indietro sulla regione.
                if (!cameraFitted && pins.isNotEmpty()) {
                    cameraFitted = true
                    view.getMapAsync { map ->
                        val boundsBuilder = LatLngBounds.Builder()
                        pins.forEach { pin -> boundsBuilder.include(LatLng(pin.latitude, pin.longitude)) }
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 64))
                    }
                }
            },
        )

        // Filtri flottanti sopra la mappa: ogni chip porta colore e glifo del proprio segnalino,
        // quindi fa anche da legenda.
        if (presentCategories.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
                contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(presentCategories) { category ->
                    val selected = category in selectedCategories
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedCategories = if (selected) selectedCategories - category else selectedCategories + category
                        },
                        label = { Text(stringResource(category.label())) },
                        leadingIcon = { PoiBadge(category, size = 20) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        elevation = FilterChipDefaults.filterChipElevation(elevation = 3.dp),
                    )
                }
            }
        }
    }

    selectedPin?.let { pin ->
        ModalBottomSheet(onDismissRequest = { selectedPin = null }) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xxl)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PoiBadge(pin.category, size = 40)
                    Spacer(modifier = Modifier.width(Spacing.l))
                    Column {
                        Text(
                            text = pin.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(pin.category.label()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                pin.phone?.let { phone ->
                    Spacer(modifier = Modifier.padding(top = Spacing.l))
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                            selectedPin = null
                        },
                    ) {
                        Icon(AppIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.poi_call, phone))
                    }
                }
            }
        }
    }
}

// Cerchio nel colore della categoria con il glifo bianco: stesso aspetto della testa del
// segnalino, usato nei chip e nella scheda del POI.
@Composable
private fun PoiBadge(category: PoiCategory, size: Int) {
    Surface(shape = CircleShape, color = category.pinColor(), modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            val glyph = category.glyph()
            if (glyph != null) {
                Icon(
                    imageVector = ImageVector.vectorResource(glyph),
                    contentDescription = null,
                    tint = PoiColors.Glyph,
                    modifier = Modifier.size((size * 0.6f).dp),
                )
            } else {
                Surface(shape = CircleShape, color = PoiColors.Glyph, modifier = Modifier.size((size * 0.3f).dp)) {}
            }
        }
    }
}

private fun renderPins(symbolManager: SymbolManager?, pins: List<MapPin>): Map<Long, MapPin> {
    symbolManager ?: return emptyMap()
    symbolManager.deleteAll()
    return pins.associate { pin ->
        val symbol = symbolManager.create(
            SymbolOptions()
                .withLatLng(LatLng(pin.latitude, pin.longitude))
                .withIconImage(iconIdFor(pin.category))
                .withIconAnchor("bottom"),
        )
        symbol.id to pin
    }
}
