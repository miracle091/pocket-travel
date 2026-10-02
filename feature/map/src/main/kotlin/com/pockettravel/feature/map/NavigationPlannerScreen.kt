package com.pockettravel.feature.map

import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalResources
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import com.pockettravel.core.ui.R as UiR
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import com.pockettravel.core.data.NearbyPois
import com.pockettravel.core.data.poiCategory
import kotlin.math.cos

/**
 * Tab Navigazione dell'hub, sul modello delle app di navigazione: la mappa a tutto schermo, in alto
 * la ricerca della meta (poi partenza, arrivo e mezzo), in basso un pannello con i recenti o con
 * tempo, distanza, "Avvia" e le svolte del percorso. La ricerca occupa tutto lo schermo, come in
 * Google Maps. In navigazione la scheda mostra la guida passo passo (NavigationGuidance).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationPlannerScreen(
    // La regione della tab che ospita il Navigatore (per prima nella ricerca), null nel Navigatore della barra principale.
    regionId: String?,
    viewModel: NavigationPlannerViewModel,
    // La guida passo passo, nella stessa scheda: "Avvia" la fa partire, "Termina" torna qui.
    navigationViewModel: NavigationViewModel,
    // Senza i Percorsi della regione: avvia il download (l'hub lo sa fare), il ricalcolo poi e' automatico.
    // Con gli id di altre regioni: quelle del catalogo tra partenza e arrivo (vuota = la regione aperta).
    onDownloadRouting: (regionIds: List<String>) -> Unit,
    // Avanzamento 0..1 del download in corso, null se nessuno: la barra come nell'elenco delle regioni.
    downloadProgress: Float?,
    // Il download e' fallito (lavoro finito in errore o catalogo non raggiungibile): si puo' riprovare.
    downloadFailed: Boolean,
    // Le regioni senza Percorsi che coprono i punti (partenza, linea in mezzo, arrivo), in ordine, dal catalogo che il
    // Navigatore non vede (core:sync); vuota se non se ne trova nessuna (anche offline).
    findMissingRegions: suspend (List<RoutePoint>) -> List<MissingRegion>,
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
    val mapRegionIds by viewModel.mapRegionIds.collectAsStateWithLifecycle()
    val idlePosition by viewModel.idlePosition.collectAsStateWithLifecycle()
    val nearbyPois by viewModel.nearbyPois.collectAsStateWithLifecycle()
    val routingInstalled by viewModel.routingInstalled.collectAsStateWithLifecycle()
    val arriveBy by viewModel.arriveBy.collectAsStateWithLifecycle()
    val reminder by viewModel.reminder.collectAsStateWithLifecycle()
    // Se partenza, arrivo o la linea in mezzo escono dalle zone dei Percorsi: quali regioni mancano. Si rilegge quando se ne installa una.
    val routingRegionIds by viewModel.routingRegionIds.collectAsStateWithLifecycle()
    val noRoutingData = (preview as? PlannerPreview.Unavailable)?.result == RouteResult.NoRoutingData
    var missingRegions by remember { mutableStateOf(emptyList<MissingRegion>()) }
    LaunchedEffect(noRoutingData, routingRegionIds, to, from) {
        missingRegions = if (noRoutingData) findMissingRegions(routePoints(viewModel.startPoint(), to?.point)) else emptyList()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onPermissionResult(result.values.any { it })
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Guida interrotta dal sistema (app chiusa in background): si chiede se riprenderla.
    val resumeOffer by navigationViewModel.resumeOffer.collectAsStateWithLifecycle()
    resumeOffer?.let { place ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            icon = { Icon(ImageVector.vectorResource(UiR.drawable.ms_navigation), contentDescription = null) },
            title = { Text(stringResource(R.string.navigation_resume_title)) },
            text = { Text(stringResource(R.string.navigation_resume_text, place.name)) },
            confirmButton = { TextButton(onClick = navigationViewModel::resume) { Text(stringResource(R.string.navigation_resume)) } },
            dismissButton = { TextButton(onClick = navigationViewModel::dismissResume) { Text(stringResource(R.string.navigation_resume_no)) } },
        )
    }

    // La notifica della guida (svolta, distanza) da Android 13 chiede il permesso: lo si chiede ad "Avvia",
    // senza bloccare la partenza se viene negato.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // In navigazione la scheda e' il navigatore: mappa a tutto schermo e indicazioni (NavigationGuidance).
    val guiding by navigationViewModel.target.collectAsStateWithLifecycle()
    // Meta appena raggiunta: la guida si chiude da sola e il Navigatore lo dice con un avviso.
    var arrivedAt by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val guidingTarget = guiding
    if (guidingTarget != null) {
        val navigationState by navigationViewModel.uiState.collectAsStateWithLifecycle()
        val navigationRegions by navigationViewModel.regionIds.collectAsStateWithLifecycle()
        val navigationMode by navigationViewModel.travelMode.collectAsStateWithLifecycle()
        val navigationArriveBy by navigationViewModel.arriveBy.collectAsStateWithLifecycle()
        val walkingHaptics by navigationViewModel.walkingHaptics.collectAsStateWithLifecycle()
        val drivingSide by navigationViewModel.drivingSideWarning.collectAsStateWithLifecycle()
        val arrivedAtMillis by navigationViewModel.arrivedAtMillis.collectAsStateWithLifecycle()
        // Con "Spegni il GPS all'arrivo" tolto la guida resta aperta: all'arrivo solo la vibrazione.
        LaunchedEffect(arrivedAtMillis) {
            if (navigationState != NavigationUiState.Arrived && isRecentArrival(arrivedAtMillis, System.currentTimeMillis())) {
                if (navigationMode == TravelMode.WALK && walkingHaptics) NavigationHaptics.arrived(context)
            }
        }
        BackHandler(onBack = navigationViewModel::stop)
        // Percorsi mancanti a meta' strada: come nell'anteprima, la regione da scaricare; a download finito si riprova da soli.
        val guidanceNoRouting = (navigationState as? NavigationUiState.Unavailable)?.result == RouteResult.NoRoutingData
        var guidanceMissing by remember { mutableStateOf(emptyList<MissingRegion>()) }
        LaunchedEffect(guidanceNoRouting, routingRegionIds) {
            guidanceMissing = if (guidanceNoRouting) findMissingRegions(routePoints(viewModel.startPoint(), guidingTarget.point)) else emptyList()
        }
        var seenRoutingRegionIds by remember { mutableStateOf(routingRegionIds) }
        LaunchedEffect(routingRegionIds) {
            if (guidanceNoRouting && (routingRegionIds - seenRoutingRegionIds).isNotEmpty()) navigationViewModel.retry()
            seenRoutingRegionIds = routingRegionIds
        }
        LaunchedEffect(navigationState) {
            if (navigationState == NavigationUiState.Arrived) {
                // L'arrivo puo' essere avvenuto mentre la scheda non era aperta (il GPS lo ascolta il ViewModel):
                // dopo qualche minuto non si annuncia piu', la guida si chiude e basta.
                if (isRecentArrival(navigationViewModel.arrivedAtMillis.value, System.currentTimeMillis())) {
                    arrivedAt = guidingTarget.name
                    if (navigationMode == TravelMode.WALK && walkingHaptics) NavigationHaptics.arrived(context)
                }
                // Viaggio concluso: il Navigatore riparte da "Dove vuoi andare?", senza il percorso ormai vecchio.
                viewModel.clearDestination()
                navigationViewModel.stop()
            }
        }
        NavigationGuidance(
            state = navigationState,
            destinationName = guidingTarget.name,
            travelMode = navigationMode,
            arriveBy = navigationArriveBy,
            tileSource = navigationViewModel.tileSource,
            regionIds = navigationRegions.ifEmpty { mapRegionIds },
            onPermissionResult = navigationViewModel::onPermissionResult,
            onRetry = navigationViewModel::retry,
            onClose = navigationViewModel::stop,
            walkingHaptics = walkingHaptics,
            onStreetNames = navigationViewModel::onStreetNames,
            drivingSide = drivingSide,
            missingRegions = guidanceMissing,
            downloadFailed = downloadFailed,
            downloadProgress = downloadProgress,
            onDownloadRouting = { onDownloadRouting(guidanceMissing.map { it.regionId }) },
        )
        return
    }

    val field = searching
    if (field != null) {
        BackHandler(onBack = viewModel::cancelSearch)
        PlannerSearch(field = field, viewModel = viewModel, recents = recents)
        return
    }

    val sheetState = rememberBottomSheetScaffoldState()
    // arrivedAt si azzera prima di mostrare l'avviso, senza annullare l'effetto: con showSnackbar prima, una
    // ricomposizione durante l'avviso lo mostrerebbe una seconda volta.
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        snapshotFlow { arrivedAt }.filterNotNull().collect { name ->
            arrivedAt = null
            sheetState.snackbarHostState.showSnackbar(resources.getString(R.string.navigation_arrived_at, name))
        }
    }
    // Altezza della scheda in alto: la mappa inquadra il percorso nello spazio libero sotto.
    var overlayHeightPx by remember { mutableStateOf(0) }
    val ready = preview as? PlannerPreview.Ready
    BottomSheetScaffold(
        scaffoldState = sheetState,
        // Senza meta i recenti restano nascosti: si vede solo il titolo "Recenti", si trascina su per aprirli.
        sheetPeekHeight = if (to == null) RECENTS_PEEK_HEIGHT else 180.dp,
        sheetContent = {
            PlannerSheet(
                to = to,
                startsFromMe = from == null,
                preview = preview,
                recents = recents,
                onRecent = viewModel::setDestination,
                onRemoveRecent = viewModel::removeRecent,
                onClearRecents = viewModel::clearRecents,
                onStart = {
                    // Si parte adesso: l'avviso "e' ora di partire" non serve piu'.
                    viewModel.cancelReminder()
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    to?.let { navigationViewModel.start(regionId ?: it.regionId, it, travelMode, arriveBy) }
                },
                arriveBy = arriveBy,
                reminder = reminder,
                onArriveByChange = viewModel::setArriveBy,
                onSetReminder = viewModel::setReminder,
                onRetry = viewModel::refreshPreview,
                onRequestPermission = requestPermission,
                routingInstalled = routingInstalled,
                downloadProgress = downloadProgress,
                downloadFailed = downloadFailed,
                travelMode = travelMode,
                missingRegions = missingRegions,
                onDownloadRouting = { onDownloadRouting(missingRegions.map { it.regionId }) },
                wheelchairOptions = if (routing.wheelchair && to != null) {
                    { WheelchairOptions(allowSteps = allowSteps, onAllowStepsChange = viewModel::setAllowSteps) }
                } else {
                    null
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Senza regioni note (elenco delle installate non ancora letto) nessuna mappa: lo stile ne vuole almeno una.
            val mapRegions = ready?.regionIds ?: mapRegionIds
            if (mapRegions.isNotEmpty()) PlannerMap(
                tileSource = viewModel.tileSource,
                regionIds = mapRegions,
                route = ready?.route,
                from = from?.point,
                to = to?.point,
                position = idlePosition,
                nearby = nearbyPois,
                topPaddingPx = overlayHeightPx.toFloat(),
                bottomPaddingPx = (if (to == null) RECENTS_PEEK_HEIGHT.value else 220f) * LocalContext.current.resources.displayMetrics.density,
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
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = "$label: $text" },
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
    onClearRecents: () -> Unit,
    onStart: () -> Unit,
    arriveBy: LocalDateTime?,
    reminder: LocalDateTime?,
    onArriveByChange: (LocalDateTime?) -> Unit,
    onSetReminder: (LocalDateTime) -> Unit,
    onRetry: () -> Unit,
    onRequestPermission: () -> Unit,
    routingInstalled: Boolean,
    downloadProgress: Float?,
    downloadFailed: Boolean,
    onDownloadRouting: () -> Unit,
    travelMode: TravelMode,
    // Le regioni del catalogo senza Percorsi tra partenza e arrivo, in ordine (vuota se non si sono trovate).
    missingRegions: List<MissingRegion>,
    wheelchairOptions: (@Composable () -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.l)) {
        when {
            to == null -> Recents(recents, onRecent, onRemoveRecent, onClearRecents)
            preview is PlannerPreview.Ready -> RouteSummary(preview.route, startsFromMe, onStart, wheelchairOptions, arriveBy, reminder, onArriveByChange, onSetReminder)
            // Il download si propone se il catalogo dice quali regioni mancano tra partenza e arrivo, o se la regione aperta
            // non ha i Percorsi; altrimenti "nessun dato" vuol dire fuori dalle zone scaricate, e riscaricare non cambierebbe nulla.
            preview is PlannerPreview.Unavailable && preview.result == RouteResult.NoRoutingData && missingRegions.isNotEmpty() ->
                MissingRoutingCard(missingRegions, downloadProgress, downloadFailed, onDownloadRouting)
            preview is PlannerPreview.Unavailable && preview.result == RouteResult.NoRoutingData && !routingInstalled -> {
                if (downloadProgress != null) {
                    Text(
                        stringResource(R.string.planner_routing_progress, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = Spacing.m),
                    )
                    DownloadProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.m))
                } else {
                    SheetMessage(
                        when {
                            downloadFailed -> stringResource(R.string.planner_routing_failed)
                            else -> stringResource(R.string.planner_no_routing_data)
                        },
                        stringResource(R.string.navigation_routing_download),
                        onDownloadRouting,
                    )
                }
            }
            preview is PlannerPreview.Unavailable && preview.result == RouteResult.NoRoutingData ->
                SheetMessage(stringResource(R.string.navigation_outside_routing))
            preview is PlannerPreview.Calculating -> Calculating(preview.progress, travelMode)
            preview is PlannerPreview.NeedsPermission ->
                SheetMessage(stringResource(R.string.planner_permission), stringResource(R.string.navigation_permission_grant), onRequestPermission)
            preview is PlannerPreview.NoLocation ->
                RetryMessage(stringResource(R.string.planner_no_location), onRetry)
            preview is PlannerPreview.Unavailable -> RetryMessage(
                stringResource(
                    when (preview.result) {
                        RouteResult.NotFound -> R.string.navigation_not_found
                        RouteResult.TimedOut -> R.string.navigation_timed_out
                        else -> R.string.navigation_failed
                    },
                ),
                onRetry,
            )
            else -> Unit
        }
    }
}

// Indicatore Expressive (forme che cambiano) e, appena BRouter da' una stima, mezzo, percentuale e barra:
// un calcolo in auto puo' durare minuti, una scritta ferma sembrerebbe un blocco. TalkBack legge solo il
// titolo, non ogni cambio di percentuale.
@Composable
private fun Calculating(progress: Double, travelMode: TravelMode) {
    val mode = stringResource(
        when (travelMode) {
            TravelMode.WALK -> R.string.usage_mode_walk
            TravelMode.BIKE -> R.string.usage_mode_bike
            TravelMode.CAR -> R.string.usage_mode_car
        },
    )
    val title = stringResource(R.string.planner_calculating)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = Spacing.m).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        PocketTravelLoadingIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.width(Spacing.m))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (progress > 0) stringResource(R.string.planner_calculating_progress, mode, (progress * 100).toInt()) else mode,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
    if (progress > 0) {
        DownloadProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.m))
    }
}

@Composable
private fun Recents(recents: List<NavigationPlace>, onRecent: (NavigationPlace) -> Unit, onRemove: (NavigationPlace) -> Unit, onClearAll: () -> Unit) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // Titolo a sinistra, "cancella tutte" a destra: solo se c'e' qualcosa da cancellare.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.planner_recents),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(vertical = Spacing.s).semantics { heading() },
        )
        if (recents.isNotEmpty()) {
            IconButton(onClick = { confirmClear = true }) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.planner_recents_clear))
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(AppIcons.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.planner_recents_clear_title)) },
            text = { Text(stringResource(R.string.planner_recents_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text(stringResource(R.string.planner_recents_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (recents.isEmpty()) {
        Text(stringResource(R.string.planner_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // Righe larghe quanto la scheda: l'icona allineata al titolo "Recenti", la X al cestino (un ListItem aggiungerebbe
    // il suo margine a quello della scheda).
    recents.forEach { place ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onRecent(place) },
        ) {
            Icon(ImageVector.vectorResource(UiR.drawable.ms_history), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(Spacing.l))
            Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { onRemove(place) }) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.planner_remove_recent, place.name))
            }
        }
    }
}

// Tempo in grande e distanza, poi "Avvia" e le svolte: il pannello di un percorso in Google Maps.
@Composable
private fun RouteSummary(
    route: Route,
    startsFromMe: Boolean,
    onStart: () -> Unit,
    wheelchairOptions: (@Composable () -> Unit)?,
    arriveBy: LocalDateTime?,
    reminder: LocalDateTime?,
    onArriveByChange: (LocalDateTime?) -> Unit,
    onSetReminder: (LocalDateTime) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.s)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(durationText(route.durationSeconds), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(distanceText(route.distanceMeters), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (startsFromMe) {
            // Pulsante tondo con l'icona, come la X che chiude la guida: il nome lo legge TalkBack.
            FilledIconButton(onClick = onStart, modifier = Modifier.size(56.dp)) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_navigation), contentDescription = stringResource(R.string.planner_start))
            }
        }
    }
    ArriveBy(route, arriveBy, reminder, onArriveByChange, onSetReminder)
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
    // Row invece di ListItem, senza il suo margine laterale: le svolte si allineano al tempo e al titolo sopra.
    route.instructions.forEach { instruction ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = Spacing.s)
                .semantics(mergeDescendants = true) {},
        ) {
            Icon(turnIcon(instruction.type), contentDescription = null)
            Spacer(modifier = Modifier.width(Spacing.l))
            Column {
                Text(turnText(instruction), style = MaterialTheme.typography.bodyLarge)
                if (instruction.distanceToNextMeters > 0) {
                    Text(
                        distanceText(instruction.distanceToNextMeters),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// "Programma un orario di arrivo": invece di partire subito si sceglie l'ora di arrivo, il Navigatore dice quando partire
// (durata del percorso con il mezzo scelto) e, se si vuole, avvisa con una notifica a quell'ora. Il
// riquadro cambia colore col tempo: neutro, poi "parti tra poco" a 5 minuti dalla partenza, rosso con i
// minuti di ritardo quando partendo adesso non si arriva piu' in tempo. TalkBack annuncia solo il cambio
// di stato (icona con una descrizione fissa per stato), non i minuti: il testo completo si legge al focus.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArriveBy(
    route: Route,
    arrival: LocalDateTime?,
    reminder: LocalDateTime?,
    onArrivalChange: (LocalDateTime?) -> Unit,
    onSetReminder: (LocalDateTime) -> Unit,
) {
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    // Notifiche spente (permesso negato o disattivate): l'avviso non si programma e lo si dice.
    var notificationsOff by rememberSaveable { mutableStateOf(false) }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val format = { time: LocalDateTime -> timeFormat.format(java.util.Date.from(time.atZone(ZoneId.systemDefault()).toInstant())) }
    // Partenza per cui si e' chiesto il permesso: serve alla risposta, che arriva dopo la scelta.
    var pendingDeparture by remember { mutableStateOf<LocalDateTime?>(null) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val departure = pendingDeparture
        pendingDeparture = null
        if (granted && departure != null) onSetReminder(departure) else notificationsOff = true
    }

    if (arrival == null) {
        // Senza il margine sinistro del TextButton l'icona si allinea al tempo e alla distanza sopra.
        TextButton(onClick = { picking = true }, contentPadding = PaddingValues(end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Icon(ImageVector.vectorResource(UiR.drawable.ms_schedule), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.s))
            Text(stringResource(R.string.planner_arrive_by))
        }
    } else {
        // L'orologio avanza anche a schermo fermo: il riquadro passa da solo a "tra poco" e poi al ritardo.
        val now by produceState(LocalDateTime.now()) {
            while (true) {
                delay(15_000)
                value = LocalDateTime.now()
            }
        }
        val departure = departureFor(arrival, route.durationSeconds)
        val late = minutesLate(arrival, route.durationSeconds, now)
        val minutesToDeparture = minutesBetween(now, departure)
        val (container, content) = when {
            late > 0 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            minutesToDeparture < SOON_MINUTES -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        }
        Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.m)) {
                // liveRegion sull'icona: TalkBack annuncia il passaggio a "tra poco" e al ritardo, non ogni minuto.
                val announcement = when {
                    late > 0 -> stringResource(R.string.navigation_running_late)
                    minutesToDeparture < 1 -> stringResource(R.string.planner_leave_now_on_time)
                    minutesToDeparture < SOON_MINUTES -> stringResource(R.string.planner_leave_soon)
                    else -> stringResource(R.string.planner_leave_at, format(departure))
                }
                Icon(
                    ImageVector.vectorResource(if (late > 0) UiR.drawable.ms_error else UiR.drawable.ms_schedule),
                    contentDescription = announcement,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(modifier = Modifier.width(Spacing.m))
                Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(
                        when {
                            late > 0 -> minutesPlural(R.plurals.navigation_late, late)
                            minutesToDeparture < 1 -> stringResource(R.string.planner_leave_now_on_time)
                            minutesToDeparture < SOON_MINUTES -> minutesPlural(R.plurals.planner_leave_in, minutesToDeparture)
                            else -> stringResource(R.string.planner_leave_at, format(departure))
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.planner_to_arrive_at, format(arrival)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = {
                    notificationsOff = false
                    onArrivalChange(null)
                }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.planner_arrive_by_clear))
                }
            }
        }
        if (departure.isAfter(now)) {
            if (reminder == null) {
                FilledTonalButton(onClick = {
                    notificationsOff = false
                    when {
                        NotificationManagerCompat.from(context).areNotificationsEnabled() -> onSetReminder(departure)
                        // Da Android 13 le notifiche vanno chieste; negate o spente dalle impostazioni, niente avviso.
                        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                            pendingDeparture = departure
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        else -> notificationsOff = true
                    }
                }) {
                    Icon(ImageVector.vectorResource(UiR.drawable.ms_notifications), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(stringResource(R.string.planner_remind_me))
                }
            } else {
                Text(
                    stringResource(R.string.planner_reminder_set, format(reminder)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
            }
            if (notificationsOff && reminder == null) {
                Text(
                    stringResource(R.string.planner_notifications_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }

    if (picking) {
        val now = LocalTime.now().plusMinutes((route.durationSeconds / 60).toLong() + 15)
        val pickerState = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.planner_arrive_by_title)) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    notificationsOff = false
                    onArrivalChange(arrivalFor(LocalTime.of(pickerState.hour, pickerState.minute)))
                    picking = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

// Sotto questi minuti dalla partenza il riquadro avvisa che e' quasi ora.
private const val SOON_MINUTES = 5

@Composable
private fun SheetMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite })
    if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
}

// Errore con "Riprova" come icona sulla stessa riga del messaggio, a destra.
@Composable
private fun RetryMessage(text: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.s)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(modifier = Modifier.width(Spacing.m))
        FilledTonalIconButton(onClick = onRetry) {
            Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.navigation_retry))
        }
    }
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
        leadingContent = {
            if (result.isAddress) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_home_pin), contentDescription = null)
            } else {
                Icon(AppIcons.Place, contentDescription = null)
            }
        },
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
    // Senza meta: la propria posizione e i POI attorno (null = niente), inquadrati sul raggio della ricerca.
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
    val styleJson = remember(tileSource, regionIds, dark, language) { tileSource.navigationStyleJson(regionIds, dark = dark, language = language) }
    val regionBounds = remember(tileSource, regionIds) { regionIds.firstNotNullOfOrNull(tileSource::regionBounds) }
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val startColor = MaterialTheme.colorScheme.surface.toArgb()
    val endColor = MaterialTheme.colorScheme.error.toArgb()
    val positionStroke = MaterialTheme.colorScheme.surface.toArgb()
    var sources by remember { mutableStateOf<PlannerSources?>(null) }

    LaunchedEffect(styleJson) {
        sources = null
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
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
                        PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                // Partenza: anello spesso nel colore del percorso con un punto al centro (come Google Maps).
                style.addLayer(
                    CircleLayer(PLANNER_START_SOURCE, PLANNER_START_SOURCE).withProperties(
                        PropertyFactory.circleColor(startColor), PropertyFactory.circleRadius(11f),
                        PropertyFactory.circleStrokeColor(routeColor), PropertyFactory.circleStrokeWidth(5f),
                    ),
                )
                style.addLayer(
                    CircleLayer("$PLANNER_START_SOURCE-dot", PLANNER_START_SOURCE).withProperties(
                        PropertyFactory.circleColor(routeColor), PropertyFactory.circleRadius(4f),
                    ),
                )
                // Arrivo: segnalino rosso con la bandiera, piu' grande di quelli dei POI, punta sul punto.
                style.addImage(DESTINATION_PIN_IMAGE, pinBitmap(context, endColor, UiR.drawable.ms_flag, scale = DESTINATION_PIN_SCALE))
                style.addLayer(destinationPinLayer(PLANNER_END_SOURCE))
                // Posizione come nella guida: punto pieno nel colore del percorso con il bordo chiaro, sopra tutto.
                style.addLayer(
                    CircleLayer(PLANNER_POSITION_SOURCE, PLANNER_POSITION_SOURCE).withProperties(
                        PropertyFactory.circleColor(routeColor), PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeColor(positionStroke), PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                sources = PlannerSources(routeSource, startSource, endSource, poiSource, positionSource)
            }
        }
    }
    LaunchedEffect(sources, position, nearby) {
        val current = sources ?: return@LaunchedEffect
        current.position.setGeoJson(pointCollection(position))
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
    LaunchedEffect(sources, route, from, to, topPaddingPx, nearby) {
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
                // Senza meta, con la posizione: il cerchio dei POI vicini (150, 300 o 600 m), cioe' lo zoom adatto alla distanza.
                nearby != null && position != null -> {
                    val dLat = nearby.radiusMeters / METERS_PER_DEGREE
                    val dLon = dLat / cos(Math.toRadians(position.latitude))
                    val bounds = LatLngBounds.Builder()
                        .include(LatLng(position.latitude - dLat, position.longitude - dLon))
                        .include(LatLng(position.latitude + dLat, position.longitude + dLon))
                        .build()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0, topPaddingPx.toInt(), 0, bottomPaddingPx.toInt()))
                }
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

// Maniglia della scheda e riga "Recenti": quanto resta visibile della scheda senza meta.
private val RECENTS_PEEK_HEIGHT = 112.dp
private const val PLANNER_START_SOURCE = "planner-start"
private const val PLANNER_END_SOURCE = "planner-end"
private const val PLANNER_POI_SOURCE = "planner-poi"
private const val PLANNER_POSITION_SOURCE = "planner-position"
private const val PLANNER_POI_ICON = "icon"
private const val PLANNER_POI_ICON_PREFIX = "planner-poi-"
private const val METERS_PER_DEGREE = 111_320.0

// Partenza, arrivo e la linea retta in mezzo, dalla partenza: la prima regione senza Percorsi e' la piu' vicina
// (da San Marino a Riga l'Italia, poi l'Austria...). Senza partenza nota solo l'arrivo.
private fun routePoints(start: RoutePoint?, destination: RoutePoint?): List<RoutePoint> =
    if (start != null && destination != null) straightLinePoints(start, destination) else listOfNotNull(start, destination)
