package com.pockettravel.feature.map

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
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

/**
 * Tab Navigazione dell'hub, sul modello delle app di navigazione: la mappa a tutto schermo, in alto
 * la ricerca della meta (poi partenza, arrivo e mezzo), in basso un pannello con i recenti o con
 * tempo, distanza, "Avvia" e le svolte del percorso. La ricerca occupa tutto lo schermo, come in
 * Google Maps. La guida passo passo resta NavigationScreen ([onStartNavigation]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationPlannerScreen(
    regionId: String,
    viewModel: NavigationPlannerViewModel,
    onStartNavigation: (NavigationPlace) -> Unit,
) {
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    val from by viewModel.from.collectAsStateWithLifecycle()
    val to by viewModel.to.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val travelMode by viewModel.travelMode.collectAsStateWithLifecycle()
    val routing by viewModel.routing.collectAsStateWithLifecycle()
    val allowSteps by viewModel.allowSteps.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onPermissionResult(result.values.any { it })
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val field = searching
    if (field != null) {
        BackHandler(onBack = viewModel::cancelSearch)
        PlannerSearch(field = field, viewModel = viewModel, recents = recents)
        return
    }

    val sheetState = rememberBottomSheetScaffoldState()
    // Altezza della scheda in alto: la mappa inquadra il percorso nello spazio libero sotto.
    var overlayHeightPx by remember { mutableStateOf(0) }
    val ready = preview as? PlannerPreview.Ready
    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = if (to == null) 220.dp else 180.dp,
        sheetContent = {
            PlannerSheet(
                to = to,
                startsFromMe = from == null,
                preview = preview,
                recents = recents,
                onRecent = viewModel::setDestination,
                onRemoveRecent = viewModel::removeRecent,
                onStart = { to?.let(onStartNavigation) },
                onRetry = viewModel::refreshPreview,
                onRequestPermission = requestPermission,
                wheelchairOptions = if (routing.wheelchair && to != null) {
                    { WheelchairOptions(allowSteps = allowSteps, onAllowStepsChange = viewModel::setAllowSteps) }
                } else {
                    null
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            PlannerMap(
                tileSource = viewModel.tileSource,
                regionIds = ready?.regionIds ?: listOf(regionId),
                route = ready?.route,
                from = from?.point,
                to = to?.point,
                topPaddingPx = overlayHeightPx.toFloat(),
                bottomPaddingPx = 220 * LocalContext.current.resources.displayMetrics.density,
                modifier = Modifier.fillMaxSize(),
            )
            Column(modifier = Modifier.fillMaxWidth().onSizeChanged { overlayHeightPx = it.height }) {
                val destination = to
                if (destination == null) {
                    SearchPill(onClick = { viewModel.startSearch(PlannerField.TO) }, modifier = Modifier.padding(Spacing.m))
                } else {
                    RoutePanel(
                        from = from,
                        to = destination,
                        onEditFrom = { viewModel.startSearch(PlannerField.FROM) },
                        onEditTo = { viewModel.startSearch(PlannerField.TO) },
                        onSwap = viewModel::swap,
                        travelMode = travelMode,
                        onTravelModeChange = viewModel::setTravelMode,
                    )
                }
            }
        }
    }
}

// "Dove vuoi andare?": la barra a pillola in cima alla mappa, come la ricerca di Google Maps.
@Composable
private fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.l)) {
            Icon(AppIcons.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(stringResource(R.string.planner_where_to), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Partenza e arrivo su due righe basse, il pulsante per scambiarli e i mezzi in chip piccole: un
// pannello agganciato al bordo in alto, come le indicazioni di Google Maps, che lascia libera la mappa.
@Composable
private fun RoutePanel(
    from: NavigationPlace?,
    to: NavigationPlace,
    onEditFrom: () -> Unit,
    onEditTo: () -> Unit,
    onSwap: () -> Unit,
    travelMode: TravelMode,
    onTravelModeChange: (TravelMode) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = Spacing.m, top = Spacing.s, bottom = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.weight(1f)) {
                    PlaceField(
                        icon = ImageVector.vectorResource(UiR.drawable.ms_trip_origin),
                        iconTint = MaterialTheme.colorScheme.primary,
                        label = stringResource(R.string.planner_from),
                        text = from?.name ?: stringResource(R.string.planner_my_position),
                        onClick = onEditFrom,
                    )
                    PlaceField(
                        icon = AppIcons.Place,
                        iconTint = MaterialTheme.colorScheme.error,
                        label = stringResource(R.string.planner_to),
                        text = to.name,
                        onClick = onEditTo,
                    )
                }
                // Con la propria posizione come partenza non si scambia: l'arrivo resterebbe senza meta.
                IconButton(onClick = onSwap, enabled = from != null) {
                    Icon(ImageVector.vectorResource(UiR.drawable.ms_swap_vert), contentDescription = stringResource(R.string.planner_swap))
                }
            }
            // Solo il mezzo scelto ha anche il nome: tre chip con l'icona stanno in una riga stretta.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.xs)) {
                TravelMode.entries.forEach { mode ->
                    val (icon, text) = when (mode) {
                        TravelMode.WALK -> UiR.drawable.ms_directions_walk to R.string.usage_mode_walk
                        TravelMode.BIKE -> UiR.drawable.ms_directions_bike to R.string.usage_mode_bike
                        TravelMode.CAR -> UiR.drawable.ms_directions_car to R.string.usage_mode_car
                    }
                    val selected = mode == travelMode
                    val name = stringResource(text)
                    FilterChip(
                        selected = selected,
                        onClick = { onTravelModeChange(mode) },
                        label = {
                            if (selected) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                Icon(ImageVector.vectorResource(icon), contentDescription = name, modifier = Modifier.size(18.dp))
                            }
                        },
                        leadingIcon = if (selected) {
                            { Icon(ImageVector.vectorResource(icon), contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

// Riga bassa come un campo di testo, senza etichetta visibile (la dice l'icona): TalkBack la legge.
@Composable
private fun PlaceField(icon: ImageVector, iconTint: Color, label: String, text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = "$label: $text" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlannerSheet(
    to: NavigationPlace?,
    startsFromMe: Boolean,
    preview: PlannerPreview,
    recents: List<NavigationPlace>,
    onRecent: (NavigationPlace) -> Unit,
    onRemoveRecent: (NavigationPlace) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onRequestPermission: () -> Unit,
    wheelchairOptions: (@Composable () -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.l)) {
        when {
            to == null -> Recents(recents, onRecent, onRemoveRecent)
            preview is PlannerPreview.Ready -> RouteSummary(preview.route, startsFromMe, onStart, wheelchairOptions)
            preview is PlannerPreview.Calculating -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = Spacing.m).semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(modifier = Modifier.width(Spacing.m))
                Text(stringResource(R.string.navigation_calculating), style = MaterialTheme.typography.bodyLarge)
            }
            preview is PlannerPreview.NeedsPermission ->
                SheetMessage(stringResource(R.string.planner_permission), stringResource(R.string.navigation_permission_grant), onRequestPermission)
            preview is PlannerPreview.NoLocation ->
                SheetMessage(stringResource(R.string.planner_no_location), stringResource(R.string.navigation_retry), onRetry)
            preview is PlannerPreview.Unavailable -> SheetMessage(
                stringResource(
                    when (preview.result) {
                        RouteResult.NoRoutingData -> R.string.planner_no_routing_data
                        RouteResult.NotFound -> R.string.navigation_not_found
                        RouteResult.TimedOut -> R.string.navigation_timed_out
                        else -> R.string.navigation_failed
                    },
                ),
                stringResource(R.string.navigation_retry),
                onRetry,
            )
            else -> Unit
        }
    }
}

@Composable
private fun Recents(recents: List<NavigationPlace>, onRecent: (NavigationPlace) -> Unit, onRemove: (NavigationPlace) -> Unit) {
    Text(
        stringResource(R.string.planner_recents),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = Spacing.s).semantics { heading() },
    )
    if (recents.isEmpty()) {
        Text(stringResource(R.string.planner_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    recents.forEach { place ->
        ListItem(
            headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(ImageVector.vectorResource(UiR.drawable.ms_history), contentDescription = null) },
            trailingContent = {
                IconButton(onClick = { onRemove(place) }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.planner_remove_recent, place.name))
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onRecent(place) },
        )
    }
}

// Tempo in grande e distanza, poi "Avvia" e le svolte: il pannello di un percorso in Google Maps.
@Composable
private fun RouteSummary(route: Route, startsFromMe: Boolean, onStart: () -> Unit, wheelchairOptions: (@Composable () -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.s)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(durationText(route.durationSeconds), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(distanceText(route.distanceMeters), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (startsFromMe) {
            Button(onClick = onStart) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_navigation), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(stringResource(R.string.planner_start))
            }
        }
    }
    // Sotto tempo e distanza: cambia il percorso come il mezzo, ma non deve coprire la mappa in alto.
    wheelchairOptions?.invoke()
    if (!startsFromMe) {
        Text(stringResource(R.string.planner_preview_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.s))
    Text(
        stringResource(R.string.planner_steps),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = Spacing.s).semantics { heading() },
    )
    route.instructions.forEach { instruction ->
        ListItem(
            headlineContent = { Text(turnText(instruction)) },
            supportingContent = if (instruction.distanceToNextMeters > 0) {
                { Text(distanceText(instruction.distanceToNextMeters)) }
            } else {
                null
            },
            leadingContent = { Icon(turnIcon(instruction.type), contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun SheetMessage(text: String, action: String, onAction: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite })
    OutlinedButton(onClick = onAction) { Text(action) }
}

// Ricerca a tutto schermo: campo in alto, sotto "La mia posizione" (solo per la partenza), i recenti a
// campo vuoto e poi i risultati con tipo, distanza e regione.
@Composable
private fun PlannerSearch(field: PlannerField, viewModel: NavigationPlannerViewModel, recents: List<NavigationPlace>) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column {
            TextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = {
                    Text(stringResource(if (field == PlannerField.FROM) R.string.planner_search_start else R.string.planner_where_to))
                },
                leadingIcon = {
                    IconButton(onClick = viewModel::cancelSearch) {
                        Icon(ImageVector.vectorResource(UiR.drawable.ms_arrow_back), contentDescription = stringResource(R.string.planner_back))
                    }
                },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { viewModel.setQuery("") }) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.planner_clear)) } }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (field == PlannerField.FROM) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.planner_my_position)) },
                            leadingContent = { Icon(ImageVector.vectorResource(UiR.drawable.ms_my_location), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable(onClick = viewModel::useMyPosition),
                        )
                    }
                }
                if (query.trim().length < 2) {
                    items(recents) { place ->
                        ListItem(
                            headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(ImageVector.vectorResource(UiR.drawable.ms_history), contentDescription = null) },
                            modifier = Modifier.clickable { viewModel.choose(place) },
                        )
                    }
                } else if (results.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.planner_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.l),
                        )
                    }
                } else {
                    items(results) { result -> ResultRow(result, onClick = { viewModel.choose(result.place) }) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: PlannerResult, onClick: () -> Unit) {
    val details = listOfNotNull(
        result.typeLabel?.let { stringResource(it) },
        result.distanceMeters?.let { distanceText(it) },
        result.otherRegionName,
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(result.place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details.isNotEmpty()) {
            { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        leadingContent = { Icon(AppIcons.Place, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Mappa di sfondo della tab: la regione, poi il percorso con la partenza (cerchio pieno) e l'arrivo
 * (cerchio rosso), inquadrati lasciando libero lo spazio del pannello in basso. Nascosta a TalkBack:
 * tempo, distanza e svolte sono nel pannello.
 */
@Composable
private fun PlannerMap(
    tileSource: OfflineTileSource,
    regionIds: List<String>,
    route: Route?,
    from: RoutePoint?,
    to: RoutePoint?,
    topPaddingPx: Float,
    bottomPaddingPx: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MapLibreInitializer.ensureInitialized(context)
    val mapView = rememberMapViewWithLifecycle()
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val language = LocalLocale.current.platformLocale.language
    val styleJson = remember(tileSource, regionIds, dark, language) { tileSource.navigationStyleJson(regionIds, dark = dark, language = language) }
    val regionBounds = remember(tileSource, regionIds) { regionIds.firstNotNullOfOrNull(tileSource::regionBounds) }
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val startColor = MaterialTheme.colorScheme.surface.toArgb()
    val endColor = MaterialTheme.colorScheme.error.toArgb()
    var sources by remember { mutableStateOf<PlannerSources?>(null) }

    LaunchedEffect(styleJson) {
        sources = null
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                val routeSource = GeoJsonSource(PLANNER_ROUTE_SOURCE).also(style::addSource)
                val startSource = GeoJsonSource(PLANNER_START_SOURCE).also(style::addSource)
                val endSource = GeoJsonSource(PLANNER_END_SOURCE).also(style::addSource)
                style.addLayer(
                    LineLayer(PLANNER_ROUTE_SOURCE, PLANNER_ROUTE_SOURCE).withProperties(
                        PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    CircleLayer(PLANNER_START_SOURCE, PLANNER_START_SOURCE).withProperties(
                        PropertyFactory.circleColor(startColor), PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleStrokeColor(routeColor), PropertyFactory.circleStrokeWidth(4f),
                    ),
                )
                style.addLayer(
                    CircleLayer(PLANNER_END_SOURCE, PLANNER_END_SOURCE).withProperties(
                        PropertyFactory.circleColor(endColor), PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleStrokeColor(startColor), PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                sources = PlannerSources(routeSource, startSource, endSource)
            }
        }
    }
    LaunchedEffect(sources, route, from, to, topPaddingPx) {
        val current = sources ?: return@LaunchedEffect
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
                framed.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(framed[0].latitude, framed[0].longitude), 15.0))
                regionBounds != null -> {
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(regionBounds.minLat, regionBounds.minLon))
                        .include(LatLng(regionBounds.maxLat, regionBounds.maxLon))
                        .build()
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0, 0, 0, bottomPaddingPx.toInt()))
                }
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier.clearAndSetSemantics {})
}

private class PlannerSources(val route: GeoJsonSource, val start: GeoJsonSource, val end: GeoJsonSource)

private fun pointCollection(point: RoutePoint?): FeatureCollection =
    FeatureCollection.fromFeatures(listOfNotNull(point?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }))

private fun lineString(points: List<RoutePoint>): LineString =
    LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })

private const val PLANNER_ROUTE_SOURCE = "planner-route"
private const val PLANNER_START_SOURCE = "planner-start"
private const val PLANNER_END_SOURCE = "planner-end"
