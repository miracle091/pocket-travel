package com.pockettravel.feature.map

import androidx.compose.ui.window.DialogProperties
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.flow.filterNotNull
import java.time.LocalDateTime
import com.pockettravel.core.ui.R as UiR

/**
 * Tab Navigazione dell'hub, sul modello delle app di navigazione: la mappa a tutto schermo, in alto
 * la ricerca della destinazione (poi partenza, arrivo e mezzo), in basso un pannello con i recenti o con
 * tempo, distanza, "Avvia" e le svolte del percorso. La ricerca occupa tutto lo schermo, come in
 * Google Maps. In navigazione la scheda mostra la navigazione passo passo (NavigationInstructions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationPlannerScreen(
    // La regione della scheda che ospita il Navigatore (per prima nella ricerca), null nel Navigatore della barra principale.
    regionId: String?,
    viewModel: NavigationPlannerViewModel,
    // La navigazione passo passo, nella stessa scheda: "Avvia" la fa partire, "Termina" torna qui.
    navigationViewModel: NavigationViewModel,
    // Senza la rete stradale della regione: avvia il download (l'hub lo sa fare), il ricalcolo poi e' automatico.
    // Con gli id di altre regioni: quelle del catalogo tra partenza e arrivo (vuota = la regione aperta).
    // carOnlyAllowed: il mezzo e' l'auto, quindi basta la rete stradale solo auto se l'utente li ha scelti.
    onDownloadRouting: (regionIds: List<String>, carOnlyAllowed: Boolean) -> Unit,
    // Avanzamento 0..1 del download in corso, null se nessuno: la barra come nell'elenco delle regioni.
    downloadProgress: Float?,
    // Il download e' fallito (lavoro finito in errore o catalogo non raggiungibile): si puo' riprovare.
    downloadFailed: Boolean,
    // Le regioni senza rete stradale che coprono i punti (partenza, linea in mezzo, arrivo), in ordine, dal catalogo che il
    // Navigatore non vede (core:sync); vuota se non se ne trova nessuna (anche offline).
    findMissingRegions: suspend (points: List<RoutePoint>, usableRouting: Set<String>) -> List<MissingRegion>,
) {
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    val planner by viewModel.uiState.collectAsStateWithLifecycle()
    val from = planner.from
    val to = planner.to
    val searching = planner.searching
    val preview = planner.preview
    val routeProfile = planner.routeProfile
    val mapRegionIds = planner.mapRegionIds
    val arriveBy = planner.arriveBy
    // Se partenza, arrivo o la linea in mezzo escono dalle zone della rete stradale: quali regioni mancano. Si rilegge quando se ne installa una.
    val routingRegionIds = planner.routingRegionIds
    val noRoutingData = (preview as? PlannerPreview.Unavailable)?.result == RouteResult.NoRoutingData
    var missingRegions by remember { mutableStateOf(emptyList<MissingRegion>()) }
    LaunchedEffect(noRoutingData, routingRegionIds, to, from) {
        missingRegions = if (noRoutingData) findMissingRegions(routePoints(viewModel.startPoint(), to?.point), routingRegionIds) else emptyList()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // L'anteprima da "La mia posizione" e il GPS vogliono la posizione precisa: con la sola approssimativa no.
        viewModel.onPermissionResult(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Guida interrotta dal sistema (app chiusa in background): si chiede se riprenderla.
    val navigation by navigationViewModel.uiState.collectAsStateWithLifecycle()
    navigation.resumeOffer?.let { place ->
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

    // In navigazione la scheda e' il navigatore: mappa a tutto schermo e indicazioni (NavigationInstructions).
    val guiding = navigation.target
    // Destinazione appena raggiunta: la guida si chiude da sola e il Navigatore lo dice con un avviso.
    var arrivedAt by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val guidingTarget = guiding
    if (guidingTarget != null) {
        val navigationState = navigation.status
        val navigationRegions = navigation.regionIds
        val navigationMode = navigation.routeProfile
        val navigationArriveBy = navigation.arriveBy
        val walkingHaptics = navigation.walkingHaptics
        val drivingSide = navigation.drivingSideWarning
        val arrivedAtMillis = navigation.arrivedAtMillis
        // Con "Spegni il GPS all'arrivo" tolto la guida resta aperta: all'arrivo solo la vibrazione.
        LaunchedEffect(arrivedAtMillis) {
            if (navigationState != NavigationStatus.Arrived && isRecentArrival(arrivedAtMillis, System.currentTimeMillis())) {
                if (navigationMode == RouteProfile.WALK && walkingHaptics) NavigationHaptics.arrived(context)
            }
        }
        BackHandler(onBack = navigationViewModel::stop)
        // Rete stradale mancante a meta' strada: come nell'anteprima, la regione da scaricare; a download finito si riprova da soli.
        val navigationNoRouting = (navigationState as? NavigationStatus.Unavailable)?.result == RouteResult.NoRoutingData
        var navigationMissing by remember { mutableStateOf(emptyList<MissingRegion>()) }
        LaunchedEffect(navigationNoRouting, routingRegionIds) {
            navigationMissing = if (navigationNoRouting) findMissingRegions(routePoints(viewModel.startPoint(), guidingTarget.point), routingRegionIds) else emptyList()
        }
        var seenRoutingRegionIds by remember { mutableStateOf(routingRegionIds) }
        LaunchedEffect(routingRegionIds) {
            if (navigationNoRouting && (routingRegionIds - seenRoutingRegionIds).isNotEmpty()) navigationViewModel.retry()
            seenRoutingRegionIds = routingRegionIds
        }
        LaunchedEffect(navigationState) {
            if (navigationState == NavigationStatus.Arrived) {
                // L'arrivo puo' essere avvenuto mentre la scheda non era aperta (il GPS lo ascolta il ViewModel):
                // dopo qualche minuto non si annuncia piu', la guida si chiude e basta.
                if (isRecentArrival(arrivedAtMillis, System.currentTimeMillis())) {
                    arrivedAt = guidingTarget.name
                    if (navigationMode == RouteProfile.WALK && walkingHaptics) NavigationHaptics.arrived(context)
                }
                // Viaggio concluso: il Navigatore riparte da "Dove vuoi andare?", senza il percorso ormai vecchio.
                viewModel.clearDestination()
                navigationViewModel.stop()
            }
        }
        NavigationInstructions(
            state = navigationState,
            destinationName = guidingTarget.name,
            routeProfile = navigationMode,
            arriveBy = navigationArriveBy,
            tileSource = navigationViewModel.tileSource,
            regionIds = navigationRegions.ifEmpty { mapRegionIds },
            onPermissionResult = navigationViewModel::onPermissionResult,
            onRetry = navigationViewModel::retry,
            onClose = navigationViewModel::stop,
            walkingHaptics = walkingHaptics,
            onStreetNames = navigationViewModel::onStreetNames,
            drivingSide = drivingSide,
            missingRegions = navigationMissing,
            downloadFailed = downloadFailed,
            downloadProgress = downloadProgress,
            onDownloadRouting = { onDownloadRouting(navigationMissing.map { it.regionId }, navigationMode == RouteProfile.CAR) },
        )
        return
    }

    NavigationPlannerContent(
        state = NavigationPlannerState(
            from = from,
            to = to,
            searching = searching,
            query = planner.query,
            results = planner.results,
            preview = preview,
            routeProfile = routeProfile,
            wheelchair = planner.routing.wheelchair,
            allowSteps = planner.allowSteps,
            recents = planner.recents,
            mapRegionIds = mapRegionIds,
            routingInstalled = planner.routingInstalled,
            arriveBy = arriveBy,
            reminder = planner.reminder,
            missingRegions = missingRegions,
            downloadProgress = downloadProgress,
            downloadFailed = downloadFailed,
            arrivedAt = arrivedAt,
        ),
        actions = NavigationPlannerActions(
            onStartSearch = viewModel::startSearch,
            onCancelSearch = viewModel::cancelSearch,
            onSetQuery = viewModel::setQuery,
            onChoose = viewModel::choose,
            onUseMyPosition = viewModel::useMyPosition,
            onRecent = viewModel::setDestination,
            onRemoveRecent = viewModel::removeRecent,
            onClearRecents = viewModel::clearRecents,
            onStart = {
                // Si parte adesso: l'avviso "e' ora di partire" non serve piu'.
                viewModel.cancelReminder()
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // Il percorso gia' calcolato nell'anteprima non si ricalcola da zero (se la posizione e' sul percorso).
                to?.let { navigationViewModel.start(regionId ?: it.regionId, it, routeProfile, arriveBy, preview as? PlannerPreview.Ready) }
            },
            onSwap = viewModel::swap,
            onRouteProfileChange = viewModel::setRouteProfile,
            onArriveByChange = viewModel::setArriveBy,
            onSetReminder = viewModel::setReminder,
            onRetry = viewModel::refreshPreview,
            onRequestPermission = requestPermission,
            onDownloadRouting = { onDownloadRouting(missingRegions.map { it.regionId }, routeProfile == RouteProfile.CAR) },
            onAllowStepsChange = viewModel::setAllowSteps,
            onArrivalAnnounced = { arrivedAt = null },
        ),
        map = { regionIds, route, topPaddingPx, bottomPaddingPx, modifier ->
            PlannerMap(
                tileSource = viewModel.tileSource,
                regionIds = regionIds,
                route = route,
                from = from?.point,
                to = to?.point,
                position = planner.idlePosition,
                nearby = planner.nearbyPois,
                topPaddingPx = topPaddingPx,
                bottomPaddingPx = bottomPaddingPx,
                modifier = modifier,
            )
        },
    )
}

/** Lo stato che il Navigatore mostra, senza il ViewModel: la scheda si prova (anteprime, test) passandolo a mano. */
internal data class NavigationPlannerState(
    val from: NavigationPlace? = null,
    val to: NavigationPlace? = null,
    val searching: PlannerField? = null,
    val query: String = "",
    val results: List<PlannerResult> = emptyList(),
    val preview: PlannerPreview = PlannerPreview.Idle,
    val routeProfile: RouteProfile = RouteProfile.WALK,
    // Percorso in sedia a rotelle: sotto tempo e distanza si offre "Accetta qualche gradino".
    val wheelchair: Boolean = false,
    val allowSteps: Boolean = false,
    val recents: List<NavigationPlace> = emptyList(),
    val mapRegionIds: List<String> = emptyList(),
    val routingInstalled: Boolean = true,
    val arriveBy: LocalDateTime? = null,
    val reminder: LocalDateTime? = null,
    // Le regioni del catalogo senza rete stradale tra partenza e arrivo, in ordine (vuota se non si sono trovate).
    val missingRegions: List<MissingRegion> = emptyList(),
    val downloadProgress: Float? = null,
    val downloadFailed: Boolean = false,
    // Destinazione appena raggiunta: se non e' null se ne mostra l'avviso, poi si chiama onArrivalAnnounced.
    val arrivedAt: String? = null,
)

internal class NavigationPlannerActions(
    val onStartSearch: (PlannerField) -> Unit = {},
    val onCancelSearch: () -> Unit = {},
    val onSetQuery: (String) -> Unit = {},
    val onChoose: (NavigationPlace) -> Unit = {},
    val onUseMyPosition: () -> Unit = {},
    val onRecent: (NavigationPlace) -> Unit = {},
    val onRemoveRecent: (NavigationPlace) -> Unit = {},
    val onClearRecents: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onSwap: () -> Unit = {},
    val onRouteProfileChange: (RouteProfile) -> Unit = {},
    val onArriveByChange: (LocalDateTime?) -> Unit = {},
    val onSetReminder: (LocalDateTime) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRequestPermission: () -> Unit = {},
    val onDownloadRouting: () -> Unit = {},
    val onAllowStepsChange: (Boolean) -> Unit = {},
    val onArrivalAnnounced: () -> Unit = {},
)

/**
 * Il Navigatore fuori dalla guida: la ricerca a tutto schermo o la mappa con il pannello in alto e il riquadro in
 * basso. La mappa MapLibre e' uno slot ([map]: regioni, percorso, margini in pixel e modificatore), cosi' i test
 * non creano una vista GL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationPlannerContent(
    state: NavigationPlannerState,
    actions: NavigationPlannerActions,
    map: @Composable (regionIds: List<String>, route: Route?, topPaddingPx: Float, bottomPaddingPx: Float, modifier: Modifier) -> Unit,
) {
    val field = state.searching
    if (field != null) {
        BackHandler(onBack = actions.onCancelSearch)
        PlannerSearch(
            field = field,
            query = state.query,
            results = state.results,
            recents = state.recents,
            onQueryChange = actions.onSetQuery,
            onBack = actions.onCancelSearch,
            onUseMyPosition = actions.onUseMyPosition,
            onChoose = actions.onChoose,
        )
        return
    }

    val to = state.to
    val sheetState = rememberBottomSheetScaffoldState()
    // arrivedAt si azzera prima di mostrare l'avviso, senza annullare l'effetto: con showSnackbar prima, una
    // ricomposizione durante l'avviso lo mostrerebbe una seconda volta.
    val resources = LocalResources.current
    val arrivedAt = rememberUpdatedState(state.arrivedAt)
    val onArrivalAnnounced = rememberUpdatedState(actions.onArrivalAnnounced)
    LaunchedEffect(Unit) {
        snapshotFlow { arrivedAt.value }.filterNotNull().collect { name ->
            onArrivalAnnounced.value()
            sheetState.snackbarHostState.showSnackbar(resources.getString(R.string.navigation_arrived_at, name))
        }
    }
    // Altezza del riquadro in alto: la mappa inquadra il percorso nello spazio libero sotto.
    var overlayHeightPx by remember { mutableStateOf(0) }
    val ready = state.preview as? PlannerPreview.Ready
    BottomSheetScaffold(
        scaffoldState = sheetState,
        // Senza destinazione i recenti restano nascosti: si vede solo il titolo "Recenti", si trascina su per aprirli.
        sheetPeekHeight = if (to == null) RECENTS_PEEK_HEIGHT else 180.dp,
        sheetContent = {
            PlannerSheet(
                to = to,
                startsFromMe = state.from == null,
                preview = state.preview,
                recents = state.recents,
                onRecent = actions.onRecent,
                onRemoveRecent = actions.onRemoveRecent,
                onClearRecents = actions.onClearRecents,
                onStart = actions.onStart,
                arriveBy = state.arriveBy,
                reminder = state.reminder,
                onArriveByChange = actions.onArriveByChange,
                onSetReminder = actions.onSetReminder,
                onRetry = actions.onRetry,
                onRequestPermission = actions.onRequestPermission,
                routingInstalled = state.routingInstalled,
                downloadProgress = state.downloadProgress,
                downloadFailed = state.downloadFailed,
                routeProfile = state.routeProfile,
                missingRegions = state.missingRegions,
                onDownloadRouting = actions.onDownloadRouting,
                wheelchairOptions = if (state.wheelchair && to != null) {
                    { WheelchairOptions(allowSteps = state.allowSteps, onAllowStepsChange = actions.onAllowStepsChange) }
                } else {
                    null
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Senza regioni note (elenco delle installate non ancora letto) nessuna mappa: lo stile ne vuole almeno una.
            val mapRegions = ready?.regionIds ?: state.mapRegionIds
            if (mapRegions.isNotEmpty()) map(
                mapRegions,
                ready?.route,
                overlayHeightPx.toFloat(),
                (if (to == null) RECENTS_PEEK_HEIGHT.value else ROUTE_PEEK_HEIGHT_DP) * LocalContext.current.resources.displayMetrics.density,
                Modifier.fillMaxSize(),
            )
            Column(modifier = Modifier.fillMaxWidth().onSizeChanged { overlayHeightPx = it.height }) {
                if (to == null) {
                    SearchPill(onClick = { actions.onStartSearch(PlannerField.TO) }, modifier = Modifier.padding(Spacing.m))
                } else {
                    RoutePanel(
                        from = state.from,
                        to = to,
                        onEditFrom = { actions.onStartSearch(PlannerField.FROM) },
                        onEditTo = { actions.onStartSearch(PlannerField.TO) },
                        onSwap = actions.onSwap,
                        routeProfile = state.routeProfile,
                        onRouteProfileChange = actions.onRouteProfileChange,
                    )
                }
            }
        }
    }
}


// Maniglia del riquadro e riga "Recenti": quanto resta visibile del riquadro senza meta.
private val RECENTS_PEEK_HEIGHT = 112.dp

// Altezza (dp) del riquadro con il percorso calcolato, per inquadrare la mappa sopra di esso.
private const val ROUTE_PEEK_HEIGHT_DP = 220f

// Partenza, arrivo e la linea retta in mezzo, dalla partenza: la prima regione senza rete stradale e' la piu' vicina
// (da San Marino a Riga l'Italia, poi l'Austria...). Senza partenza nota solo l'arrivo.
private fun routePoints(start: RoutePoint?, destination: RoutePoint?): List<RoutePoint> =
    if (start != null && destination != null) straightLinePoints(start, destination) else listOfNotNull(start, destination)
