package com.pockettravel.feature.map

import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.pockettravel.core.ui.R as UiR
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import com.pockettravel.core.data.NearbyPois
import com.pockettravel.core.data.poiCategory
import kotlin.math.cos

/**
 * Mappa di sfondo della scheda: la regione, poi il percorso con la partenza (cerchio pieno) e l'arrivo
 * (cerchio rosso), inquadrati lasciando libero lo spazio del pannello in basso. Nascosta a TalkBack:
 * tempo, distanza e svolte sono nel pannello.
 */
@Composable
internal fun PlannerMap(
    tileSource: OfflineTileSource,
    regionIds: List<String>,
    route: Route?,
    from: RoutePoint?,
    to: RoutePoint?,
    // Senza destinazione: la propria posizione e i POI attorno (null = niente), inquadrati sul raggio della ricerca.
    position: RoutePoint?,
    nearby: NearbyPois?,
    topPaddingPx: Float,
    bottomPaddingPx: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)
    val mapView = rememberMapViewWithLifecycle()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val language = LocalLocale.current.platformLocale.language
    // Stile e riquadro leggono i file delle mappe: fuori dal main thread (null finche' non sono pronti).
    val styleJson by produceState<String?>(null, tileSource, regionIds, dark, language) {
        value = withContext(Dispatchers.IO) { tileSource.navigationStyleJson(regionIds, dark = dark, language = language) }
    }
    val regionBounds by produceState<MapBounds?>(null, tileSource, regionIds) {
        value = withContext(Dispatchers.IO) { regionIds.firstNotNullOfOrNull(tileSource::regionBounds) }
    }
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val startColor = MaterialTheme.colorScheme.surface.toArgb()
    val endColor = MaterialTheme.colorScheme.error.toArgb()
    val positionStroke = MaterialTheme.colorScheme.surface.toArgb()
    var sources by remember { mutableStateOf<PlannerSources?>(null) }

    LaunchedEffect(styleJson) {
        val json = styleJson ?: return@LaunchedEffect
        sources = null
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(json)) { style ->
                val routeSource = GeoJsonSource(PLANNER_ROUTE_SOURCE).also(style::addSource)
                val startSource = GeoJsonSource(PLANNER_START_SOURCE).also(style::addSource)
                val endSource = GeoJsonSource(PLANNER_END_SOURCE).also(style::addSource)
                val poiSource = GeoJsonSource(PLANNER_POI_SOURCE).also(style::addSource)
                val positionSource = GeoJsonSource(PLANNER_POSITION_SOURCE).also(style::addSource)
                // POI vicini sotto percorso e segnalini: icone come nella scheda Mappa, una per categoria presente.
                style.addLayer(
                    SymbolLayer(PLANNER_POI_SOURCE, PLANNER_POI_SOURCE).withProperties(
                        PropertyFactory.iconImage(Expression.get(PLANNER_POI_ICON)),
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    ),
                )
                style.addLayer(
                    LineLayer(PLANNER_ROUTE_SOURCE, PLANNER_ROUTE_SOURCE).withProperties(
                        PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(ROUTE_LINE_WIDTH),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                // Partenza: anello spesso nel colore del percorso con un punto al centro (come Google Maps).
                style.addLayer(
                    CircleLayer(PLANNER_START_SOURCE, PLANNER_START_SOURCE).withProperties(
                        PropertyFactory.circleColor(startColor), PropertyFactory.circleRadius(START_RING_RADIUS),
                        PropertyFactory.circleStrokeColor(routeColor), PropertyFactory.circleStrokeWidth(START_RING_STROKE_WIDTH),
                    ),
                )
                style.addLayer(
                    CircleLayer("$PLANNER_START_SOURCE-dot", PLANNER_START_SOURCE).withProperties(
                        PropertyFactory.circleColor(routeColor), PropertyFactory.circleRadius(START_DOT_RADIUS),
                    ),
                )
                // Arrivo: segnalino rosso con la bandiera, piu' grande di quelli dei POI, punta sul punto.
                style.addImage(DESTINATION_PIN_IMAGE, pinBitmap(context, endColor, UiR.drawable.ms_flag, scale = DESTINATION_PIN_SCALE))
                style.addLayer(destinationPinLayer(PLANNER_END_SOURCE))
                // Posizione come nella navigazione: punto pieno nel colore del percorso con il bordo chiaro, sopra tutto.
                style.addLayer(
                    CircleLayer(PLANNER_POSITION_SOURCE, PLANNER_POSITION_SOURCE).withProperties(
                        PropertyFactory.circleColor(routeColor), PropertyFactory.circleRadius(POSITION_DOT_RADIUS),
                        PropertyFactory.circleStrokeColor(positionStroke), PropertyFactory.circleStrokeWidth(POSITION_STROKE_WIDTH),
                    ),
                )
                sources = PlannerSources(routeSource, startSource, endSource, poiSource, positionSource)
            }
        }
    }
    // La posizione cambia a ogni fix, i POI vicini solo dopo 50 m: effetti separati.
    LaunchedEffect(sources, position) {
        sources?.position?.setGeoJson(pointCollection(position))
    }
    LaunchedEffect(sources, nearby) {
        val current = sources ?: return@LaunchedEffect
        val pois = nearby?.pois.orEmpty()
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            pois.map { it.poiCategory() }.toSet().forEach { category ->
                val id = PLANNER_POI_ICON_PREFIX + category.name
                if (style.getImage(id) == null) style.addImage(id, poiPinBitmap(context, category))
            }
            current.pois.setGeoJson(
                FeatureCollection.fromFeatures(
                    pois.map { poi ->
                        Feature.fromGeometry(Point.fromLngLat(poi.longitude, poi.latitude)).apply { addStringProperty(PLANNER_POI_ICON, PLANNER_POI_ICON_PREFIX + poi.poiCategory().name) }
                    },
                ),
            )
        }
    }
    LaunchedEffect(sources, route, from, to, topPaddingPx, nearby, regionBounds) {
        val current = sources ?: return@LaunchedEffect
        val area = regionBounds
        val points = route?.points.orEmpty()
        current.route.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(points.takeIf { it.size >= 2 }?.let { Feature.fromGeometry(lineString(it)) })))
        val start = points.firstOrNull() ?: from
        val end = to
        current.start.setGeoJson(pointCollection(start))
        current.end.setGeoJson(pointCollection(end))
        mapView.getMapAsync { map ->
            val framed = (points.ifEmpty { listOfNotNull(start, end) })
            when {
                framed.size >= 2 -> {
                    val bounds = LatLngBounds.Builder().apply { framed.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
                    val margin = 64 * context.resources.displayMetrics.density
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, margin.toInt(), topPaddingPx.toInt() + margin.toInt(), margin.toInt(), bottomPaddingPx.toInt() + margin.toInt()))
                }
                framed.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(framed[0].latitude, framed[0].longitude), SINGLE_POINT_ZOOM))
                // Senza destinazione, con la posizione: il cerchio dei POI vicini (150, 300 o 600 m), cioe' lo zoom adatto alla distanza.
                nearby != null && position != null -> {
                    val dLat = nearby.radiusMeters / METERS_PER_DEGREE
                    val dLon = dLat / cos(Math.toRadians(position.latitude))
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(position.latitude - dLat, position.longitude - dLon))
                        .include(LatLng(position.latitude + dLat, position.longitude + dLon))
                        .build()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0, topPaddingPx.toInt(), 0, bottomPaddingPx.toInt()))
                }
                area != null -> {
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(area.minLat, area.minLon))
                        .include(LatLng(area.maxLat, area.maxLon))
                        .build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0, 0, 0, bottomPaddingPx.toInt()))
                }
            }
        }
    }
    // Mappa solo visiva, il percorso sta nel testo: clearAndSetSemantics non basta per la View Android di
    // MapLibre, che TalkBack leggerebbe comunque (e per prima, prima dei campi).
    AndroidView(
        factory = { mapView.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } },
        modifier = modifier.clearAndSetSemantics {},
    )
}

private class PlannerSources(
    val route: GeoJsonSource, val start: GeoJsonSource, val end: GeoJsonSource, val pois: GeoJsonSource, val position: GeoJsonSource,
)

private fun pointCollection(point: RoutePoint?): FeatureCollection =
    FeatureCollection.fromFeatures(listOfNotNull(point?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }))

private fun lineString(points: List<RoutePoint>): LineString =
    LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })

private const val PLANNER_ROUTE_SOURCE = "planner-route"

private const val PLANNER_START_SOURCE = "planner-start"
private const val PLANNER_END_SOURCE = "planner-end"
private const val PLANNER_POI_SOURCE = "planner-poi"
private const val PLANNER_POSITION_SOURCE = "planner-position"
private const val PLANNER_POI_ICON = "icon"
private const val PLANNER_POI_ICON_PREFIX = "planner-poi-"
private const val METERS_PER_DEGREE = 111_320.0

// Spessori e raggi (px) dei livelli della mappa: percorso, anello e punto di partenza, posizione.
private const val ROUTE_LINE_WIDTH = 7f
private const val START_RING_RADIUS = 11f
private const val START_RING_STROKE_WIDTH = 5f
private const val START_DOT_RADIUS = 4f
private const val POSITION_DOT_RADIUS = 8f
private const val POSITION_STROKE_WIDTH = 3f

// Zoom quando il percorso inquadra un solo punto.
private const val SINGLE_POINT_ZOOM = 15.0
