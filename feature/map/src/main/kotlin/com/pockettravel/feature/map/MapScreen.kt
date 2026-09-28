package com.pockettravel.feature.map

import android.content.Intent
import androidx.annotation.StringRes
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.PoiColors
import com.pockettravel.core.ui.Spacing
import java.time.LocalDate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import com.pockettravel.core.ui.R as UiR

private const val PIN_ICON_PREFIX = "pocket-travel-pin-"
private const val PINS_SOURCE = "pocket-travel-pins"
private const val PINS_LAYER = "pocket-travel-pins"
private const val PIN_ID = "id"
private const val PIN_ICON = "icon"

// I parcheggi sono tanti e fitti (a Rimini oltre 800): solo da vicino, per non coprire il resto.
private const val PARKING_MIN_ZOOM = 15.0

// Zoom del fit iniziale quando la regione ha un solo pin (niente bounds da inquadrare).
private const val SINGLE_PIN_ZOOM = 15.0

private fun iconIdFor(category: PoiCategory) = PIN_ICON_PREFIX + category.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    tileSource: OfflineTileSource,
    regionId: String,
    // Solo per invalidare la cache dello stile (remember piu' sotto) quando cambia la sorgente
    // usata (mappa scaricata, anteprima installata, connettivita'): il valore vero e proprio lo
    // rilegge tileSource.styleJson.
    mapSource: MapSourceState,
    pins: List<MapPin> = emptyList(),
    // Categorie nascoste, salvate per tutte le regioni (MapFilterPreferences): chip e legenda le cambiano.
    hiddenCategories: Set<PoiCategory> = emptySet(),
    onHiddenCategoriesChange: (Set<PoiCategory>) -> Unit = {},
    // Modalita' "Con disabilità": via i POI che OSM segna come non accessibili in sedia a rotelle.
    hideInaccessible: Boolean = false,
) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)

    val mapView = rememberMapViewWithLifecycle()
    // Stile scuro quando l'app e' in tema scuro (segue il tema effettivo, non solo il sistema).
    val darkMap = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val styleJson = remember(tileSource, regionId, darkMap, mapSource) { tileSource.styleJson(regionId, dark = darkMap) }
    var configuredStyle by remember { mutableStateOf<String?>(null) }
    // Sorgente dei segnalini dello stile corrente: null durante un cambio di stile.
    var pinsSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    // Saveable: il foglio resta aperto dopo una rotazione o un cambio di tema.
    var showLegend by rememberSaveable { mutableStateOf(false) }
    // Saveable come id, non come MapPin: resta valido dopo una rotazione risolvendolo di nuovo
    // sulla lista pins corrente, invece di riaprire il foglio su un pin ormai stantio.
    var selectedPinId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPin = pins.firstOrNull { it.id == selectedPinId }
    var cameraFitted by remember { mutableStateOf(false) }
    var parkingZoom by remember { mutableStateOf(false) }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            val update = { parkingZoom = map.cameraPosition.zoom >= PARKING_MIN_ZOOM }
            // Move per i gesti, idle anche per gli spostamenti via codice (il fit iniziale).
            map.addOnCameraMoveListener(update)
            map.addOnCameraIdleListener(update)
            // Tocco su un segnalino: il primo sotto il dito nel layer dei POI.
            map.addOnMapClickListener { latLng ->
                val id = map.queryRenderedFeatures(map.projection.toScreenLocation(latLng), PINS_LAYER)
                    .firstNotNullOfOrNull { it.getStringProperty(PIN_ID) }
                if (id != null) selectedPinId = id
                id != null
            }
        }
    }
    val visiblePins = pins.filter {
        it.category !in hiddenCategories && (it.category != PoiCategory.PARCHEGGIO || parkingZoom) &&
            !(hideInaccessible && it.wheelchair == "no")
    }
    val presentCategories = PoiCategory.entries.filter { category -> pins.any { it.category == category } }
    // Segnalini ridisegnati solo quando cambiano quelli visibili o la sorgente (nuovo stile),
    // non a ogni ricomposizione.
    LaunchedEffect(pinsSource, visiblePins) {
        pinsSource?.setGeoJson(pinsFeatureCollection(visiblePins))
    }

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
                        map.prefetchZoomDelta = 1
                        // La sorgente dei segnalini appartiene allo stile corrente: non va piu' usata
                        // dopo setStyle, che ne crea una nuova nel callback.
                        pinsSource = null
                        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                            PoiCategory.entries.forEach { category ->
                                style.addImage(iconIdFor(category), poiPinBitmap(context, category))
                            }
                            // Usata solo quando lo stile non ha alcuna sorgente (MapSourceKind.NONE),
                            // ma aggiunta sempre, come le icone dei POI sopra.
                            style.addImage(MISSING_MAP_HATCH_IMAGE, missingMapHatchBitmap(context, darkMap))
                            val source = GeoJsonSource(PINS_SOURCE)
                            style.addSource(source)
                            style.addLayer(
                                SymbolLayer(PINS_LAYER, PINS_SOURCE).withProperties(
                                    PropertyFactory.iconImage(Expression.get(PIN_ICON)),
                                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                                ),
                            )
                            pinsSource = source
                        }
                    }
                }

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
                        // LatLngBounds.Builder.build() vuole almeno 2 punti: con un solo pin si
                        // centra la camera su di lui.
                        val update = if (pins.size == 1) {
                            CameraUpdateFactory.newLatLngZoom(LatLng(pins[0].latitude, pins[0].longitude), SINGLE_PIN_ZOOM)
                        } else {
                            val boundsBuilder = LatLngBounds.Builder()
                            pins.forEach { pin -> boundsBuilder.include(LatLng(pin.latitude, pin.longitude)) }
                            CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 64)
                        }
                        map.moveCamera(update)
                    }
                }
            },
        )

        // Filtri flottanti sopra la mappa: ogni chip porta colore e glifo del proprio segnalino. La
        // legenda completa, a gruppi, si apre dal pulsante in basso.
        if (presentCategories.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopStart),
                contentPadding = PaddingValues(horizontal = Spacing.m, vertical = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                items(presentCategories) { category ->
                    val selected = category !in hiddenCategories
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onHiddenCategoriesChange(if (selected) hiddenCategories + category else hiddenCategories - category)
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
            SmallFloatingActionButton(
                onClick = { showLegend = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.l),
            ) {
                Icon(AppIcons.Layers, contentDescription = stringResource(R.string.map_legend_open))
            }
        }
    }

    if (showLegend) {
        MapLegendSheet(
            presentCategories = presentCategories.toSet(),
            hiddenCategories = hiddenCategories,
            onHiddenCategoriesChange = onHiddenCategoriesChange,
            onDismiss = { showLegend = false },
        )
    }

    selectedPin?.let { pin ->
        ModalBottomSheet(onDismissRequest = { selectedPinId = null }) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xxl)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PoiBadge(pin.category, size = 40)
                    Spacer(modifier = Modifier.width(Spacing.l))
                    // Titolo: il nome, o il tipo preciso se OSM non ha un nome. Sotto il tipo, non la
                    // categoria generica della mappa ("Dove mangiare e bere" non dice nulla in piu').
                    val type = poiTypeLabel(pin.osmTag)?.let { stringResource(it) }
                    Column {
                        Text(
                            text = pin.name ?: type ?: stringResource(pin.category.label()),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (pin.name != null && type != null) {
                            Text(
                                text = type,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                wheelchairLabel(pin.wheelchair)?.let { label ->
                    PoiDetailRow(ImageVector.vectorResource(UiR.drawable.ms_accessible), stringResource(label))
                }
                pin.address?.let { PoiDetailRow(AppIcons.Place, it) }
                pin.openingHours?.let { OpeningHoursDetail(it) }
                pin.phone?.let { phone ->
                    Spacer(modifier = Modifier.padding(top = Spacing.l))
                    FilledTonalButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$phone".toUri()))
                            selectedPinId = null
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

// Una riga della scheda del POI: icona piccola e testo (accessibilita', indirizzo, orari).
@Composable
private fun PoiDetailRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = Spacing.l)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(Spacing.s))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// Orari come tabella (giorni a sinistra, fasce a destra, oggi in grassetto); se la stringa OSM usa
// una sintassi che parseOpeningHours non interpreta, il testo com'e' (formatOpeningHours).
@Composable
private fun OpeningHoursDetail(raw: String) {
    val rows = remember(raw) { parseOpeningHours(raw, LocalDate.now().dayOfWeek.value - 1) }
    if (rows == null) {
        PoiDetailRow(AppIcons.Schedule, formatOpeningHours(raw))
        return
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = Spacing.l)) {
        Icon(AppIcons.Schedule, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(Spacing.s))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            rows.forEach { row ->
                val weight = if (row.includesToday) FontWeight.Bold else FontWeight.Normal
                Row {
                    Text(row.days, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, modifier = Modifier.width(80.dp))
                    Text(row.hours, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
                }
            }
        }
    }
}

// Cerchio nel colore della categoria con il glifo bianco: stesso aspetto della testa del
// segnalino, usato nei chip e nella scheda del POI.
@Composable
internal fun PoiBadge(category: PoiCategory, size: Int) {
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

// Solo i valori OSM con un significato chiaro; gli altri (es. "unknown") come se mancasse.
@StringRes
private fun wheelchairLabel(value: String?): Int? = when (value) {
    "yes", "designated" -> R.string.poi_wheelchair_yes
    "limited" -> R.string.poi_wheelchair_limited
    "no" -> R.string.poi_wheelchair_no
    else -> null
}

// Un punto per segnalino, con l'id del pin (per il tocco) e l'icona della sua categoria.
private fun pinsFeatureCollection(pins: List<MapPin>): FeatureCollection =
    FeatureCollection.fromFeatures(
        pins.map { pin ->
            Feature.fromGeometry(Point.fromLngLat(pin.longitude, pin.latitude)).apply {
                addStringProperty(PIN_ID, pin.id)
                addStringProperty(PIN_ICON, iconIdFor(pin.category))
            }
        },
    )
