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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.PoiColors
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.label
import com.pockettravel.core.ui.safeWebUrl
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

private fun iconIdFor(category: PoiCategory, badge: AccessibilityBadge? = null) =
    PIN_ICON_PREFIX + category.name + (badge?.let { "-" + it.imageId } ?: "")

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
    // Categorie nascoste, salvate per tutte le regioni (MapFilterPreferences): le cambia il foglio dei filtri.
    hiddenCategories: Set<PoiCategory> = emptySet(),
    onHiddenCategoriesChange: (Set<PoiCategory>) -> Unit = {},
    // Modalita' "Con disabilita'": via i POI che OSM segna come non accessibili in sedia a rotelle.
    hideInaccessible: Boolean = false,
    // Con "Con disabilita'": solo i posti accessibili (anche in parte) e i parcheggi per disabili.
    onlyAccessible: Boolean = false,
    onOnlyAccessibleChange: (Boolean) -> Unit = {},
    // Apre la navigazione verso il punto scelto; null = niente pulsante "Indicazioni".
    onNavigate: ((MapPin) -> Unit)? = null,
    // Orari dei mezzi pubblici nella scheda di treni, metro, autobus e traghetti: pacchetto della regione,
    // tabellone del POI aperto (null finche' si legge), download e il POI di cui leggere le partenze.
    transitPackage: TransitPackageState = TransitPackageState.UNKNOWN,
    transitBoard: TransitBoard? = null,
    onDownloadTransit: () -> Unit = {},
    onTransitStopChange: (MapPin?) -> Unit = {},
    // Area inquadrata a ogni fermo della mappa, dopo il primo inquadramento: i segnalini si leggono solo li'.
    onViewportChange: (MapBounds) -> Unit = {},
) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)

    val mapView = rememberMapViewWithLifecycle()
    // Stile scuro quando l'app e' in tema scuro (segue il tema effettivo, non solo il sistema).
    val darkMap = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val language = LocalLocale.current.platformLocale.language
    val styleJson = remember(tileSource, regionId, darkMap, mapSource, language) { tileSource.styleJson(regionId, dark = darkMap, language = language) }
    var configuredStyle by remember { mutableStateOf<String?>(null) }
    // Sorgente dei segnalini dello stile corrente: null durante un cambio di stile.
    var pinsSource by remember { mutableStateOf<GeoJsonSource?>(null) }
    // Saveable: il foglio resta aperto dopo una rotazione o un cambio di tema.
    var showLegend by rememberSaveable { mutableStateOf(false) }
    // Saveable come id, non come MapPin: resta valido dopo una rotazione risolvendolo di nuovo
    // sulla lista pins corrente, invece di riaprire il foglio su un pin ormai stantio.
    var selectedPinId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPin = pins.firstOrNull { it.id == selectedPinId }
    // Le partenze si leggono solo per un POI di trasporto aperto e con gli orari installati; anche quando
    // il pacchetto finisce di installarsi con la scheda aperta.
    LaunchedEffect(selectedPin, transitPackage) {
        onTransitStopChange(selectedPin?.takeIf { it.category in TRANSIT_CATEGORIES && transitPackage == TransitPackageState.INSTALLED })
    }
    var cameraFitted by remember { mutableStateOf(false) }
    // Centro e zoom (lat, lon, zoom) dell'ultima vista: una rotazione ricrea la mappa, e senza
    // si tornerebbe all'inquadratura iniziale su tutti i segnalini.
    var savedCamera by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    var parkingZoom by remember { mutableStateOf(false) }
    val currentOnViewportChange by rememberUpdatedState(onViewportChange)
    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            val update = { parkingZoom = map.cameraPosition.zoom >= PARKING_MIN_ZOOM }
            // Move per i gesti, idle anche per gli spostamenti via codice (il fit iniziale).
            map.addOnCameraMoveListener(update)
            map.addOnCameraIdleListener {
                update()
                // Solo dopo il primo inquadramento: prima la camera e' ancora sulla vista iniziale del mondo.
                if (cameraFitted) {
                    map.cameraPosition.target?.let { savedCamera = doubleArrayOf(it.latitude, it.longitude, map.cameraPosition.zoom) }
                    val visible = map.projection.visibleRegion.latLngBounds
                    currentOnViewportChange(MapBounds(visible.longitudeWest, visible.latitudeSouth, visible.longitudeEast, visible.latitudeNorth))
                }
            }
            // Tocco su un segnalino: il primo sotto il dito nel layer dei POI.
            map.addOnMapClickListener { latLng ->
                val id = map.queryRenderedFeatures(map.projection.toScreenLocation(latLng), PINS_LAYER)
                    .firstNotNullOfOrNull { it.getStringProperty(PIN_ID) }
                if (id != null) selectedPinId = id
                id != null
            }
        }
    }
    // Parcheggi per disabili solo con "Con disabilita'" (hideInaccessible), agli stessi zoom degli altri parcheggi.
    val visiblePins = pins.filter {
        it.category !in hiddenCategories &&
            (it.category != PoiCategory.PARCHEGGIO || parkingZoom) &&
            (it.category != PoiCategory.PARCHEGGIO_DISABILI || (hideInaccessible && parkingZoom)) &&
            !(hideInaccessible && it.wheelchair == "no") &&
            !(hideInaccessible && onlyAccessible && pinBadgeOf(it) == null && it.category != PoiCategory.PARCHEGGIO_DISABILI)
    }
    val presentCategories = PoiCategory.entries.filter { category ->
        (category != PoiCategory.PARCHEGGIO_DISABILI || hideInaccessible) && pins.any { it.category == category }
    }
    // Segnalini ridisegnati solo quando cambiano quelli visibili o la sorgente (nuovo stile),
    // non a ogni ricomposizione.
    LaunchedEffect(pinsSource, visiblePins, hideInaccessible) {
        val source = pinsSource ?: return@LaunchedEffect
        // Con "Con disabilita'" i segnalini accessibili hanno il distintivo: varianti delle icone
        // create solo per le combinazioni presenti, non per tutte le categorie in anticipo.
        val badgeOf = { pin: MapPin -> if (hideInaccessible) pinBadgeOf(pin) else null }
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            visiblePins.mapNotNullTo(mutableSetOf()) { pin -> badgeOf(pin)?.let { pin.category to it } }
                .forEach { (category, badge) ->
                    val id = iconIdFor(category, badge)
                    if (style.getImage(id) == null) style.addImage(id, poiPinBitmap(context, category, badge))
                }
            source.setGeoJson(pinsFeatureCollection(visiblePins, badgeOf))
        }
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

                // PmtilesExtractor scarica solo le tile che intersecano il bounding box della regione (vedi
                // core:sync/PmtilesExtractor.tileRangeFor), per tenere piccolo il pacchetto: ai livelli di zoom
                // bassi le tile vicine che non toccano il bbox non esistono, e zoomando a mano dalla vista mondo
                // meta' schermo mostrerebbe solo il "background". Per questo la camera si inquadra sui pin della
                // regione (gia' ben dentro il bbox estratto) appena sono disponibili. Una tantum (guardia
                // cameraFitted): dopo il primo fit l'utente resta libero di tornare alla vista mondo senza che ogni
                // ricomposizione lo riporti sulla regione.
                // Con la mappa installata si inquadra il suo riquadro (la zona scelta, per i paesi grandi): i segnalini
                // sono solo quelli dell'area visibile, non tutti quelli della regione.
                val restored = savedCamera
                val mapBounds = if (!cameraFitted && restored == null) tileSource.regionBounds(regionId) else null
                if (!cameraFitted && (restored != null || mapBounds != null || pins.isNotEmpty())) {
                    cameraFitted = true
                    view.getMapAsync { map ->
                        // LatLngBounds.Builder.build() vuole almeno 2 punti: con un solo pin si
                        // centra la camera su di lui. Dopo una rotazione, la vista di prima.
                        val update = if (restored != null) {
                            CameraUpdateFactory.newLatLngZoom(LatLng(restored[0], restored[1]), restored[2])
                        } else if (mapBounds != null) {
                            CameraUpdateFactory.newLatLngBounds(
                                LatLngBounds.Builder().include(LatLng(mapBounds.maxLat, mapBounds.minLon)).include(LatLng(mapBounds.minLat, mapBounds.maxLon)).build(),
                                0,
                            )
                        } else if (pins.size == 1) {
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

        // Filtri nel foglio a gruppi che si apre dal pulsante in basso: con 20 e piu' categorie una fila
        // sopra la mappa la coprirebbe e ne mostrerebbe solo tre o quattro.
        // Il numero sul pulsante dice quante categorie della regione sono nascoste.
        if (presentCategories.isNotEmpty()) {
            val hiddenCount = presentCategories.count { it in hiddenCategories }
            val filtersLabel = if (hiddenCount > 0) {
                pluralStringResource(R.plurals.map_filters_open_hidden, hiddenCount, hiddenCount)
            } else {
                stringResource(R.string.map_legend_open)
            }
            BadgedBox(
                // Nel colore primario, non in quello di errore del Badge: non e' un avviso.
                badge = {
                    if (hiddenCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                            Text(hiddenCount.toString())
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.l),
            ) {
                SmallFloatingActionButton(onClick = { showLegend = true }) {
                    Icon(AppIcons.Filter, contentDescription = filtersLabel)
                }
            }
        }
    }

    if (showLegend) {
        MapLegendSheet(
            presentCategories = presentCategories.toSet(),
            hiddenCategories = hiddenCategories,
            onHiddenCategoriesChange = onHiddenCategoriesChange,
            // Il filtro "Solo posti accessibili" c'e' solo con "Con disabilita'".
            onlyAccessible = onlyAccessible.takeIf { hideInaccessible },
            onOnlyAccessibleChange = onOnlyAccessibleChange,
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
                    // categoria generica della mappa ("Ristoranti e bar" non dice nulla in piu').
                    val type = poiTypeLabel(pin.osmTag)?.let { stringResource(it) }
                    // Nome nella lingua dell'interfaccia; quello locale sotto, piu' piccolo, per
                    // riconoscerlo sui cartelli ("Kiyomizu-dera" / "清水寺").
                    val shownName = pin.displayName(LocalLocale.current.platformLocale.language)
                    Column {
                        Text(
                            text = shownName ?: type ?: stringResource(pin.category.label()),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (shownName != null && shownName != pin.name) {
                            Text(
                                text = pin.name.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                toiletsWheelchairLabel(pin.toiletsWheelchair)?.let { label ->
                    PoiDetailRow(ImageVector.vectorResource(UiR.drawable.ms_accessible), stringResource(label))
                }
                pin.capacityDisabled?.let { count ->
                    PoiDetailRow(ImageVector.vectorResource(UiR.drawable.ms_accessible), stringResource(R.string.poi_disabled_parking, count))
                }
                pin.address?.let { PoiDetailRow(AppIcons.Place, it) }
                pin.openingHours?.let { OpeningHoursDetail(it) }
                if (pin.category in TRANSIT_CATEGORIES) TransitDeparturesSection(transitPackage, transitBoard, hideInaccessible, onDownloadTransit)
                onNavigate?.let { navigate ->
                    Spacer(modifier = Modifier.padding(top = Spacing.l))
                    Button(
                        onClick = {
                            selectedPinId = null
                            navigate(pin)
                        },
                    ) {
                        Icon(AppIcons.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.poi_directions))
                    }
                }
                pin.phone?.let { phone ->
                    Spacer(modifier = Modifier.padding(top = Spacing.l))
                    FilledTonalButton(
                        onClick = {
                            // Tablet solo Wi-Fi o profilo senza telefono: nessuna app per ACTION_DIAL.
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())) }
                            selectedPinId = null
                        },
                    ) {
                        Icon(AppIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.poi_call, phone))
                    }
                }
                pin.website?.let { website ->
                    Spacer(modifier = Modifier.padding(top = Spacing.s))
                    FilledTonalButton(
                        onClick = {
                            // In OSM il sito c'e' spesso senza schema ("www.esempio.it"), ed e' modificabile da
                            // chiunque: si apre solo http/https, tutto il resto si scarta.
                            safeWebUrl(website)?.let { url ->
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                            }
                        },
                    ) {
                        Icon(AppIcons.Web, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.poi_website))
                    }
                }
                pin.email?.let { email ->
                    Spacer(modifier = Modifier.padding(top = Spacing.s))
                    FilledTonalButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$email".toUri())) } },
                    ) {
                        Icon(AppIcons.Mail, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.poi_email, email))
                    }
                }
            }
        }
    }
}

// Una riga della scheda del POI: icona piccola e testo (accessibilita', indirizzo, orari).
@Composable
private fun PoiDetailRow(icon: ImageVector, text: String, iconSize: Dp = 16.dp) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = Spacing.l)) {
        PoiDetailIcon(icon, iconSize)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// Stesso spazio per l'icona in tutte le righe (indirizzo, orari...), cosi' testi e icone restano in
// colonna; centrata sulla prima riga di testo. size piu' piccolo per i glifi pieni (l'orologio e' un
// cerchio intero e a pari misura sembra piu' grande del segnaposto).
@Composable
private fun PoiDetailIcon(icon: ImageVector, size: Dp = 16.dp) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 2.dp, end = 2.dp).size(16.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(size))
    }
    Spacer(modifier = Modifier.width(Spacing.s))
}

// Orari come tabella (giorni a sinistra, fasce a destra, oggi in grassetto); se la stringa OSM usa
// una sintassi che parseOpeningHours non interpreta, il testo com'e' (formatOpeningHours).
@Composable
private fun OpeningHoursDetail(raw: String) {
    val labels = openingHoursLabels()
    val rows = remember(raw, labels) { parseOpeningHours(raw, LocalDate.now().dayOfWeek.value - 1, labels) }
    if (rows == null) {
        PoiDetailRow(AppIcons.Schedule, formatOpeningHours(raw, labels), iconSize = 14.dp)
        return
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = Spacing.l)) {
        PoiDetailIcon(AppIcons.Schedule, size = 14.dp)
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

@Composable
private fun openingHoursLabels() = OpeningHoursLabels(
    days = stringArrayResource(R.array.opening_hours_days).toList(),
    closed = stringResource(R.string.opening_hours_closed),
    alwaysOpen = stringResource(R.string.opening_hours_always_open),
    holidays = stringResource(R.string.opening_hours_holidays),
)

// Cerchio nel colore della categoria con il glifo bianco: stesso aspetto della testa del
// segnalino, usato nel foglio dei filtri e nella scheda del POI.
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

// Badge del segnalino: dal tag wheelchair del posto o, se manca, da quello dei suoi bagni.
private fun pinBadgeOf(pin: MapPin): AccessibilityBadge? =
    accessibilityBadgeOf(pin.wheelchair) ?: accessibilityBadgeOf(pin.toiletsWheelchair)

// Come wheelchairLabel, per il tag OSM "toilets:wheelchair".
@StringRes
private fun toiletsWheelchairLabel(value: String?): Int? = when (value) {
    "yes", "designated" -> R.string.poi_toilets_wheelchair_yes
    "limited" -> R.string.poi_toilets_wheelchair_limited
    "no" -> R.string.poi_toilets_wheelchair_no
    else -> null
}

// Un punto per segnalino, con l'id del pin (per il tocco) e l'icona della sua categoria.
private fun pinsFeatureCollection(pins: List<MapPin>, badgeOf: (MapPin) -> AccessibilityBadge?): FeatureCollection =
    FeatureCollection.fromFeatures(
        pins.map { pin ->
            Feature.fromGeometry(Point.fromLngLat(pin.longitude, pin.latitude)).apply {
                addStringProperty(PIN_ID, pin.id)
                addStringProperty(PIN_ICON, iconIdFor(pin.category, badgeOf(pin)))
            }
        },
    )
