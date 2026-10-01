package com.pockettravel.feature.map

import android.Manifest
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Navigazione passo passo verso un punto, solo con il GPS attivo. Con bici e auto l'avviso su
 * autovelox, limiti di velocita' e zone a traffico limitato resta in cima in ogni stato: i dati di
 * percorso (BRouter/OSM) non li hanno.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    state: NavigationUiState,
    destinationName: String,
    travelMode: TravelMode,
    onTravelModeChange: (TravelMode) -> Unit,
    // Profilo in sedia a rotelle (a piedi con "Con disabilita'"): avviso sui dati e "Accetto qualche gradino".
    wheelchair: Boolean,
    allowSteps: Boolean,
    onAllowStepsChange: (Boolean) -> Unit,
    // Mappa della schermata: lo stile della scheda Mappa, con una sorgente per ognuna delle regioni del percorso.
    tileSource: OfflineTileSource,
    regionIds: List<String>,
    // Pacchetto Percorsi da scaricare: senza, "Scarica i percorsi" invece di un messaggio muto. Se e' quello
    // di un'altra regione (partenza o arrivo fuori dalle regioni con i percorsi) ne arriva il nome.
    routingPackage: RoutingPackageState,
    missingRegionName: String?,
    onDownloadRouting: () -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // Il GPS vuole la posizione precisa: con la sola approssimativa si resta senza navigazione.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onPermissionResult(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    // Schermo acceso mentre si naviga, come in ogni navigatore.
    val view = LocalView.current
    val navigating = state is NavigationUiState.Navigating
    DisposableEffect(navigating) {
        view.keepScreenOn = navigating
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (destinationName.isBlank()) stringResource(R.string.navigation_title_no_name)
                        else stringResource(R.string.navigation_title, destinationName),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.navigation_close))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // Solo con un veicolo (bici e auto; camper e moto usano l'auto): a piedi autovelox e ZTL
            // non contano. In navigazione in una riga: lo spazio serve alla mappa.
            if (travelMode != TravelMode.WALK) SpeedCameraNotice()
            TravelModeSelector(travelMode, onTravelModeChange)
            if (wheelchair) WheelchairOptions(allowSteps, onAllowStepsChange)
            when (state) {
                NavigationUiState.NeedsPermission -> Message(
                    text = stringResource(R.string.navigation_permission),
                    action = stringResource(R.string.navigation_permission_grant),
                    onAction = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                )
                NavigationUiState.GpsDisabled -> Message(
                    text = stringResource(R.string.navigation_gps_disabled),
                    action = stringResource(R.string.navigation_gps_settings),
                    onAction = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
                )
                NavigationUiState.WaitingForFix -> Waiting(stringResource(R.string.navigation_waiting_fix))
                is NavigationUiState.Calculating -> {
                    Waiting(
                        stringResource(R.string.navigation_calculating_elapsed, state.elapsedSeconds),
                        announcement = stringResource(R.string.navigation_calculating),
                    )
                    // Stima del motore: per i calcoli brevi non serve, compare dopo il primo secondo.
                    if (state.elapsedSeconds >= 1) {
                        LinearProgressIndicator(progress = { state.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.navigation_calculating_progress, (state.progress * 100).roundToInt()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.elapsedSeconds >= LONG_CALCULATION_SECONDS) {
                        Text(stringResource(R.string.navigation_calculating_long), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                NavigationUiState.Arrived -> Message(
                    text = stringResource(R.string.navigation_arrived),
                    action = stringResource(R.string.navigation_close),
                    onAction = onClose,
                )
                is NavigationUiState.Unavailable if state.result == RouteResult.NoRoutingData -> when (routingPackage) {
                    RoutingPackageState.MISSING -> Message(
                        text = if (missingRegionName != null) stringResource(R.string.navigation_routing_missing_region, missingRegionName)
                        else stringResource(R.string.navigation_routing_missing),
                        action = if (missingRegionName != null) stringResource(R.string.navigation_routing_download_region, missingRegionName)
                        else stringResource(R.string.navigation_routing_download),
                        onAction = onDownloadRouting,
                    )
                    RoutingPackageState.DOWNLOADING -> Waiting(stringResource(R.string.navigation_routing_downloading))
                    // Percorsi installati ma dati mancanti: partenza o arrivo fuori dalla loro zona.
                    RoutingPackageState.INSTALLED, RoutingPackageState.UNKNOWN -> Message(
                        text = stringResource(R.string.navigation_outside_routing),
                        action = stringResource(R.string.navigation_retry),
                        onAction = onRetry,
                    )
                }
                is NavigationUiState.Unavailable -> Message(
                    text = stringResource(
                        when (state.result) {
                            RouteResult.NoRoutingData -> R.string.navigation_no_routing_data
                            RouteResult.NotFound -> R.string.navigation_not_found
                            RouteResult.TimedOut -> R.string.navigation_timed_out
                            else -> R.string.navigation_failed
                        },
                    ),
                    action = stringResource(R.string.navigation_retry),
                    onAction = onRetry,
                )
                is NavigationUiState.Navigating -> Guidance(state, tileSource, regionIds)
            }
        }
    }
}

@Composable
private fun SpeedCameraNotice() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(Spacing.m))
            // L'invito a rispettare la segnaletica in grassetto, dopo l'elenco di quello che non si segnala.
            val notShown = stringResource(R.string.navigation_no_speed_cameras)
            val followSigns = stringResource(R.string.navigation_follow_road_signs)
            Text(
                buildAnnotatedString {
                    append(notShown)
                    append(' ')
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(followSigns) }
                    append('.')
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Percorso in sedia a rotelle: i dati OSM su scale, cordoli e fondi sono incompleti, e le scale sono
 * vietate finche' l'utente non accetta qualche gradino (allow_steps del profilo wheelchair).
 */
@Composable
internal fun WheelchairOptions(allowSteps: Boolean, onAllowStepsChange: (Boolean) -> Unit) {
    // Una riga sola: la mappa sotto prende lo spazio che resta.
    ListItem(
        headlineContent = { Text(stringResource(R.string.navigation_allow_steps)) },
        supportingContent = { Text(stringResource(R.string.navigation_wheelchair_notice), style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Switch(checked = allowSteps, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = allowSteps, role = Role.Switch, onValueChange = onAllowStepsChange),
    )
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Button(onClick = onAction) { Text(action) }
}

// [announcement]: quello che TalkBack legge e annuncia, fisso anche quando [text] cambia ogni secondo
// (il calcolo del percorso), altrimenti la live region lo ripeterebbe di continuo.
@Composable
private fun Waiting(text: String, announcement: String = text) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = announcement; liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(modifier = Modifier.width(Spacing.m))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ColumnScope.Guidance(state: NavigationUiState.Navigating, tileSource: OfflineTileSource, regionIds: List<String>) {
    val progress = state.progress
    val route = state.route
    // Nomi delle strade per indice del punto della svolta, trovati dalla mappa (NavigationMap).
    var streetNames by remember(route) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val tracker = remember(route) { NavigationTracker(route) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Decorativa: il testo accanto dice gia' la svolta.
                Icon(turnIcon(progress.nextInstruction.type), contentDescription = null, modifier = Modifier.size(56.dp))
                Spacer(modifier = Modifier.width(Spacing.l))
                // liveRegion: TalkBack legge la nuova indicazione quando cambia.
                Text(
                    text = turnText(progress.nextInstruction),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            streetNames[progress.nextInstruction.pointIndex]?.let { street ->
                Text(street, style = MaterialTheme.typography.titleMedium)
            }
            if (progress.nextInstruction.type != TurnType.ARRIVE || progress.distanceToNextMeters > 0) {
                Text(
                    // Sotto i 10 m la svolta e' adesso: "Ora" invece di "Tra 10 m".
                    text = if (progress.distanceToNextMeters < NOW_METERS) stringResource(R.string.navigation_now)
                    else stringResource(R.string.navigation_in_distance, distanceText(progress.distanceToNextMeters)),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            if (progress.offRoute) {
                Text(
                    text = stringResource(R.string.navigation_off_route),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    NavigationMap(
        tileSource = tileSource,
        regionIds = regionIds,
        route = route,
        pathToNext = progress.pathToNext,
        position = state.position,
        distanceToNextMeters = progress.distanceToNextMeters,
        onStreetNames = { found -> streetNames = streetNames + found },
        // Altezza minima: con l'avviso e le opzioni sopra, il solo peso la schiacciava a zero.
        modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp).clip(MaterialTheme.shapes.medium),
    )
    // Tempo rimasto in proporzione alla distanza rimasta, dalla durata stimata da BRouter.
    val remainingSeconds = if (route.distanceMeters > 0) route.durationSeconds * progress.remainingMeters / route.distanceMeters else 0.0
    Text(
        text = stringResource(R.string.navigation_remaining, distanceText(progress.remainingMeters), durationText(remainingSeconds)),
        style = MaterialTheme.typography.bodyLarge,
    )
    val upcoming = route.instructions.drop(progress.nextInstructionIndex + 1)
    if (upcoming.isNotEmpty()) {
        // Due svolte visibili, le altre scorrendo: lo spazio serve alla mappa.
        LazyColumn(modifier = Modifier.heightIn(max = 128.dp)) {
            itemsIndexed(upcoming) { index, instruction ->
                if (index > 0) HorizontalDivider()
                // Metri dalla svolta precedente (per la prima, da quella mostrata sopra).
                val previous = if (index == 0) progress.nextInstruction else upcoming[index - 1]
                val legMeters = tracker.distanceBetween(previous.pointIndex, instruction.pointIndex)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.m)) {
                    Icon(turnIcon(instruction.type), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(Spacing.m))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = turnText(instruction), style = MaterialTheme.typography.bodyLarge)
                        streetNames[instruction.pointIndex]?.let { street ->
                            Text(street, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.width(Spacing.m))
                    Text(distanceText(legMeters), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun turnText(instruction: TurnInstruction): String = when (instruction.type) {
    TurnType.CONTINUE -> stringResource(R.string.turn_continue)
    TurnType.SLIGHT_LEFT -> stringResource(R.string.turn_slight_left)
    TurnType.LEFT -> stringResource(R.string.turn_left)
    TurnType.SHARP_LEFT -> stringResource(R.string.turn_sharp_left)
    TurnType.SLIGHT_RIGHT -> stringResource(R.string.turn_slight_right)
    TurnType.RIGHT -> stringResource(R.string.turn_right)
    TurnType.SHARP_RIGHT -> stringResource(R.string.turn_sharp_right)
    TurnType.KEEP_LEFT -> stringResource(R.string.turn_keep_left)
    TurnType.KEEP_RIGHT -> stringResource(R.string.turn_keep_right)
    TurnType.EXIT_LEFT -> stringResource(R.string.turn_exit_left)
    TurnType.EXIT_RIGHT -> stringResource(R.string.turn_exit_right)
    TurnType.U_TURN -> stringResource(R.string.turn_u_turn)
    TurnType.ROUNDABOUT, TurnType.ROUNDABOUT_LEFT -> stringResource(R.string.turn_roundabout, instruction.roundaboutExit)
    TurnType.ARRIVE -> stringResource(R.string.turn_arrive)
}

// Frecce di Material Symbols, come le altre icone dell'app. Uscite = rampe, "mantieni" = biforcazione.
@Composable
internal fun turnIcon(type: TurnType): ImageVector = ImageVector.vectorResource(
    when (type) {
        TurnType.CONTINUE -> UiR.drawable.ms_straight
        TurnType.SLIGHT_LEFT -> UiR.drawable.ms_turn_slight_left
        TurnType.LEFT -> UiR.drawable.ms_turn_left
        TurnType.SHARP_LEFT -> UiR.drawable.ms_turn_sharp_left
        TurnType.SLIGHT_RIGHT -> UiR.drawable.ms_turn_slight_right
        TurnType.RIGHT -> UiR.drawable.ms_turn_right
        TurnType.SHARP_RIGHT -> UiR.drawable.ms_turn_sharp_right
        TurnType.KEEP_LEFT -> UiR.drawable.ms_fork_left
        TurnType.KEEP_RIGHT -> UiR.drawable.ms_fork_right
        TurnType.EXIT_LEFT -> UiR.drawable.ms_ramp_left
        TurnType.EXIT_RIGHT -> UiR.drawable.ms_ramp_right
        TurnType.U_TURN -> UiR.drawable.ms_u_turn_left
        TurnType.ROUNDABOUT -> UiR.drawable.ms_roundabout_right
        TurnType.ROUNDABOUT_LEFT -> UiR.drawable.ms_roundabout_left
        TurnType.ARRIVE -> UiR.drawable.ms_flag
    },
)

// Sotto il chilometro a decine di metri, sopra in km con un decimale nel formato della lingua.
@Composable
internal fun distanceText(meters: Double): String {
    // Arrotondato prima del confronto: 996 m sono gia' "1,0 km", non "1000 m".
    val rounded = (meters / 10).roundToInt() * 10
    return if (rounded < 1_000) {
        stringResource(R.string.navigation_meters, rounded)
    } else {
        val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
        stringResource(R.string.navigation_kilometers, format.format(meters / 1_000))
    }
}

@Composable
internal fun durationText(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    return if (minutes < 60) stringResource(R.string.navigation_minutes, minutes)
    else stringResource(R.string.navigation_hours_minutes, minutes / 60, minutes % 60)
}

@Composable
internal fun TravelModeSelector(selected: TravelMode, onSelect: (TravelMode) -> Unit) {
    val label = stringResource(R.string.navigation_travel_mode)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }) {
        TravelMode.entries.forEachIndexed { index, mode ->
            val (icon, text) = when (mode) {
                TravelMode.WALK -> UiR.drawable.ms_directions_walk to R.string.usage_mode_walk
                TravelMode.BIKE -> UiR.drawable.ms_directions_bike to R.string.usage_mode_bike
                TravelMode.CAR -> UiR.drawable.ms_directions_car to R.string.usage_mode_car
            }
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, TravelMode.entries.size),
                icon = {},
            ) {
                Icon(ImageVector.vectorResource(icon), contentDescription = stringResource(text), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Mappa della navigazione: il percorso intero, il tratto fino alla prossima svolta piu' marcato e
 * la posizione GPS, con la camera che la segue. Nascosta a TalkBack: le indicazioni sono nel
 * testo sopra.
 */
@Composable
private fun NavigationMap(
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
    // Con worldFallback: se la posizione esce dal riquadro scaricato restano i paesi (e, con la rete, il mondo) invece del vuoto.
    val styleJson = remember(tileSource, regionIds, dark, language) {
        tileSource.navigationStyleJson(regionIds, dark = dark, language = language)
    }
    // Letti dall'header delle mappe installate: 127 byte, una volta per regione.
    val regionBounds = remember(tileSource, regionIds) { regionIds.mapNotNull(tileSource::regionBounds) }
    val routeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f).toArgb()
    val nextColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val positionColor = MaterialTheme.colorScheme.primary.toArgb()
    val positionStroke = MaterialTheme.colorScheme.surface.toArgb()
    // Sorgenti dello stile corrente: null durante un cambio di stile (tema, lingua).
    var sources by remember { mutableStateOf<NavigationSources?>(null) }

    LaunchedEffect(styleJson) {
        sources = null
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                val routeSource = GeoJsonSource(ROUTE_SOURCE).also(style::addSource)
                val nextSource = GeoJsonSource(NEXT_SOURCE).also(style::addSource)
                val positionSource = GeoJsonSource(POSITION_SOURCE).also(style::addSource)
                style.addLayer(
                    LineLayer(ROUTE_SOURCE, ROUTE_SOURCE).withProperties(
                        PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    LineLayer(NEXT_SOURCE, NEXT_SOURCE).withProperties(
                        PropertyFactory.lineColor(nextColor), PropertyFactory.lineWidth(8f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                style.addLayer(
                    CircleLayer(POSITION_SOURCE, POSITION_SOURCE).withProperties(
                        PropertyFactory.circleColor(positionColor), PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeColor(positionStroke), PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                sources = NavigationSources(routeSource, nextSource, positionSource)
            }
        }
    }
    LaunchedEffect(sources, route) {
        sources?.route?.setGeoJson(lineString(route.points))
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

    // Nomi delle strade: i segmenti di percorso (.rd5) non li hanno, la mappa si'. A mappa ferma e
    // disegnata si leggono le strade delle tile gia' caricate e, per ogni svolta, si prende quella in
    // cui si entra; le svolte lontane restano senza nome finche' le loro tile non vengono caricate.
    // OnDidBecomeIdle e non la fine del movimento della camera: querySourceFeatures prima che il
    // renderer esista manda in crash MapLibre (SIGSEGV in MapRenderer::actor, visto sull'emulatore).
    val currentOnStreetNames by rememberUpdatedState(onStreetNames)
    DisposableEffect(mapView, route, language) {
        var attached: MapLibreMap? = null
        val lookup = MapView.OnDidBecomeIdleListener {
            val style = attached?.style?.takeIf { it.isFullyLoaded } ?: return@OnDidBecomeIdleListener
            // Le strade di tutte le regioni del percorso: una sorgente ognuna (REGION_SOURCE_PREFIX).
            val roads = style.sources.filterIsInstance<VectorSource>().filter { it.id.startsWith(REGION_SOURCE_PREFIX) }
                .flatMap { it.querySourceFeatures(arrayOf(ROADS_SOURCE_LAYER), null) }.flatMap { it.toNamedRoads(language) }
            if (roads.isEmpty()) return@OnDidBecomeIdleListener
            val found = route.instructions.mapNotNull { instruction ->
                NavigationTracker.streetProbe(route, instruction)
                    ?.let { probe -> NavigationTracker.nearestRoadName(probe, roads) }
                    ?.let { name -> instruction.pointIndex to name }
            }.toMap()
            if (found.isNotEmpty()) currentOnStreetNames(found)
        }
        mapView.getMapAsync { map -> attached = map }
        mapView.addOnDidBecomeIdleListener(lookup)
        onDispose { mapView.removeOnDidBecomeIdleListener(lookup) }
    }

    AndroidView(factory = { mapView }, modifier = modifier.clearAndSetSemantics {})
}

private class NavigationSources(val route: GeoJsonSource, val next: GeoJsonSource, val position: GeoJsonSource)

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
private const val NEXT_SOURCE = "navigation-next"
private const val POSITION_SOURCE = "navigation-position"
private const val CAMERA_ANIMATION_MILLIS = 500

/** Fuori dalla mappa scaricata resta il mondo online (dati fino a z8): oltre questo zoom non mostra piu' niente. */
private const val OUTSIDE_REGION_MAX_ZOOM = 11.0

/**
 * Zoom che segue la posizione: vicino alla svolta si vede l'incrocio, lontano la strada davanti
 * (in autostrada la prossima svolta puo' essere a decine di km). Fuori dal riquadro della mappa
 * scaricata ([insideRegion] falso) al massimo [OUTSIDE_REGION_MAX_ZOOM].
 */
internal fun followZoom(distanceToNextMeters: Double, insideRegion: Boolean): Double {
    val zoom = when {
        distanceToNextMeters < 150 -> 17.0
        distanceToNextMeters < 400 -> 16.0
        distanceToNextMeters < 1_000 -> 15.0
        distanceToNextMeters < 3_000 -> 14.0
        distanceToNextMeters < 8_000 -> 13.0
        else -> 12.0
    }
    return if (insideRegion) zoom else minOf(zoom, OUTSIDE_REGION_MAX_ZOOM)
}

/** Sotto questa distanza dalla svolta si dice "Ora". */
private const val NOW_METERS = 10.0

/** Da quanti secondi di calcolo avvisare che un percorso lungo in auto puo' richiedere minuti. */
private const val LONG_CALCULATION_SECONDS = 5L
