package com.pockettravel.feature.map

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pockettravel.core.ui.R as UiR
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mappa della navigazione: il percorso intero, il tratto fino alla prossima svolta piu' marcato e
 * la posizione GPS, con la camera che la segue. Nascosta a TalkBack: le indicazioni sono nel
 * testo sopra.
 */
@Composable
internal fun NavigationMap(
    tileSource: OfflineTileSource,
    regionIds: List<String>,
    route: Route,
    pathToNext: List<RoutePoint>,
    position: RoutePoint,
    distanceToNextMeters: Double,
    onStreetNames: (Map<Int, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)
    val mapView = rememberMapViewWithLifecycle()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val language = LocalLocale.current.platformLocale.language
    // Con worldFallback: se la posizione esce dal riquadro scaricato restano le nazioni (e, con la rete, il mondo) invece del vuoto.
    // Letti dai file delle mappe, fuori dal main thread (stile null finche' non e' pronto).
    val styleJson by produceState<String?>(null, tileSource, regionIds, dark, language) {
        value = withContext(Dispatchers.IO) { tileSource.navigationStyleJson(regionIds, dark = dark, language = language) }
    }
    // Letti dall'header delle mappe installate: 127 byte, una volta per regione.
    val regionBounds by produceState(emptyList<MapBounds>(), tileSource, regionIds) {
        value = withContext(Dispatchers.IO) { regionIds.mapNotNull(tileSource::regionBounds) }
    }
    val routeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f).toArgb()
    val nextColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val positionColor = MaterialTheme.colorScheme.primary.toArgb()
    val positionStroke = MaterialTheme.colorScheme.surface.toArgb()
    val destinationColor = MaterialTheme.colorScheme.error.toArgb()
    // Sorgenti dello stile corrente: null durante un cambio di stile (tema, lingua).
    var sources by remember { mutableStateOf<NavigationSources?>(null) }

    LaunchedEffect(styleJson) {
        val json = styleJson ?: return@LaunchedEffect
        sources = null
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(json)) { style ->
                val routeSource = GeoJsonSource(ROUTE_SOURCE).also(style::addSource)
                val nextSource = GeoJsonSource(NEXT_SOURCE).also(style::addSource)
                val positionSource = GeoJsonSource(POSITION_SOURCE).also(style::addSource)
                val destinationSource = GeoJsonSource(DESTINATION_SOURCE).also(style::addSource)
                style.addLayer(
                    LineLayer(ROUTE_SOURCE, ROUTE_SOURCE).withProperties(
                        PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(ROUTE_LINE_WIDTH),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(NEXT_SOURCE, NEXT_SOURCE).withProperties(
                        PropertyFactory.lineColor(nextColor), PropertyFactory.lineWidth(NEXT_LINE_WIDTH),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                // Arrivo sotto la posizione: vicino alla destinazione il punto blu resta sopra la bandiera.
                style.addImage(DESTINATION_PIN_IMAGE, pinBitmap(context, destinationColor, UiR.drawable.ms_flag, scale = DESTINATION_PIN_SCALE))
                style.addLayer(destinationPinLayer(DESTINATION_SOURCE))
                style.addLayer(
                    CircleLayer(POSITION_SOURCE, POSITION_SOURCE).withProperties(
                        PropertyFactory.circleColor(positionColor), PropertyFactory.circleRadius(POSITION_DOT_RADIUS),
                        PropertyFactory.circleStrokeColor(positionStroke), PropertyFactory.circleStrokeWidth(POSITION_STROKE_WIDTH),
                    ),
                )
                sources = NavigationSources(routeSource, nextSource, positionSource, destinationSource)
            }
        }
    }
    LaunchedEffect(sources, route) {
        sources?.route?.setGeoJson(lineString(route.points))
        route.points.lastOrNull()?.let { end -> sources?.destination?.setGeoJson(Point.fromLngLat(end.longitude, end.latitude)) }
    }
    LaunchedEffect(sources, pathToNext, position) {
        val current = sources ?: return@LaunchedEffect
        current.next.setGeoJson(lineString(pathToNext))
        current.position.setGeoJson(Point.fromLngLat(position.longitude, position.latitude))
        mapView.getMapAsync { map ->
            val zoom = followZoom(distanceToNextMeters, insideRegion = regionBounds.any { it.contains(position.latitude, position.longitude) })
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.latitude, position.longitude), zoom), CAMERA_ANIMATION_MILLIS)
        }
    }

    // Nomi delle strade: i segmenti della rete stradale (.rd5) non li hanno, la mappa si'. A mappa ferma e
    // disegnata si leggono le strade delle tile gia' caricate e, per ogni svolta, si prende quella in
    // cui si entra; le svolte lontane restano senza nome finche' le loro tile non vengono caricate.
    // OnDidBecomeIdle e non la fine del movimento della camera: querySourceFeatures prima che il
    // renderer esista manda in crash MapLibre (SIGSEGV in MapRenderer::actor, visto sull'emulatore).
    // Sul main resta solo querySourceFeatures: la conversione delle strade e l'abbinamento alle svolte (O(svolte x
    // segmenti)) si fanno su Default, e solo per le svolte ancora senza nome; finite quelle, a ogni idle non si fa piu' nulla.
    val currentOnStreetNames by rememberUpdatedState(onStreetNames)
    val scope = rememberCoroutineScope()
    DisposableEffect(mapView, route, language) {
        var attached: MapLibreMap? = null
        var lookupJob: Job? = null
        val resolved = mutableSetOf<Int>()
        val probes = route.instructions.mapNotNull { instruction ->
            NavigationTracker.streetProbe(route, instruction)?.let { probe -> instruction.pointIndex to probe }
        }
        val lookup = MapView.OnDidBecomeIdleListener {
            if (lookupJob?.isActive == true) return@OnDidBecomeIdleListener
            val pending = probes.filter { (pointIndex, _) -> pointIndex !in resolved }
            if (pending.isEmpty()) return@OnDidBecomeIdleListener
            val style = attached?.style?.takeIf { it.isFullyLoaded } ?: return@OnDidBecomeIdleListener
            // Le strade di tutte le regioni del percorso: una sorgente ognuna (REGION_SOURCE_PREFIX).
            val features = style.sources.filterIsInstance<VectorSource>().filter { it.id.startsWith(REGION_SOURCE_PREFIX) }
                .flatMap { it.querySourceFeatures(arrayOf(ROADS_SOURCE_LAYER), null) }
            if (features.isEmpty()) return@OnDidBecomeIdleListener
            lookupJob = scope.launch {
                val found = withContext(Dispatchers.Default) {
                    val roads = features.flatMap { it.toNamedRoads(language) }
                    pending.mapNotNull { (pointIndex, probe) ->
                        NavigationTracker.nearestRoadName(probe, roads)?.let { name -> pointIndex to name }
                    }.toMap()
                }
                if (found.isNotEmpty()) {
                    resolved += found.keys
                    currentOnStreetNames(found)
                }
            }
        }
        mapView.getMapAsync { map -> attached = map }
        mapView.addOnDidBecomeIdleListener(lookup)
        onDispose {
            mapView.removeOnDidBecomeIdleListener(lookup)
            lookupJob?.cancel()
        }
    }

    // Mappa solo visiva, il percorso sta nel testo: clearAndSetSemantics non basta per la View Android di
    // MapLibre, che TalkBack leggerebbe comunque (e per prima, prima dei campi).
    AndroidView(
        factory = { mapView.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } },
        modifier = modifier.clearAndSetSemantics {},
    )
}

private class NavigationSources(val route: GeoJsonSource, val next: GeoJsonSource, val position: GeoJsonSource, val destination: GeoJsonSource)

// Nome nella lingua dell'app come le etichette della mappa (labelField): in italiano name:it ->
// name:en -> name, in inglese name:en -> name. Una MultiLineString diventa piu' linee.
private fun Feature.toNamedRoads(language: String): List<NamedRoad> {
    val keys = if (language == "en") listOf("name:en", "name") else listOf("name:it", "name:en", "name")
    val name = keys.firstNotNullOfOrNull { key -> if (hasProperty(key)) getStringProperty(key)?.takeIf { it.isNotBlank() } else null }
        ?: return emptyList()
    val lines = when (val geometry = geometry()) {
        is LineString -> listOf(geometry.coordinates())
        is MultiLineString -> geometry.coordinates()
        else -> emptyList()
    }
    return lines.map { line -> NamedRoad(name, line.map { RoutePoint(it.latitude(), it.longitude()) }) }
}

private fun lineString(points: List<RoutePoint>): LineString =
    LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })

// Sorgenti vettoriali delle regioni ("region-<id>") e layer delle strade dello stile (vedi OfflineTileSource).
private const val REGION_SOURCE_PREFIX = "region"
private const val ROADS_SOURCE_LAYER = "roads"
private const val ROUTE_SOURCE = "navigation-route"
private const val DESTINATION_SOURCE = "navigation-destination"
private const val NEXT_SOURCE = "navigation-next"
private const val POSITION_SOURCE = "navigation-position"
private const val CAMERA_ANIMATION_MILLIS = 500

// Spessori e raggi (px) dei livelli della mappa: tratto del percorso, prossima svolta, posizione.
private const val ROUTE_LINE_WIDTH = 6f
private const val NEXT_LINE_WIDTH = 8f
private const val POSITION_DOT_RADIUS = 8f
private const val POSITION_STROKE_WIDTH = 3f

// Segnalino d'arrivo comune a Navigatore e navigazione: goccia rossa con la bandiera (pinBitmap),
// sempre visibile anche sopra etichette e altri segnalini, appoggiata con la punta sul punto.
internal const val DESTINATION_PIN_IMAGE = "route-destination-pin"
internal const val DESTINATION_PIN_SCALE = 1.4f

internal fun destinationPinLayer(sourceId: String): SymbolLayer =
    SymbolLayer("$sourceId-pin", sourceId).withProperties(
        PropertyFactory.iconImage(DESTINATION_PIN_IMAGE),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    )
