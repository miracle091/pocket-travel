package com.pockettravel.feature.map

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import kotlin.math.ceil
import androidx.compose.runtime.SideEffect
import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import android.os.Build
import android.app.Activity
import android.Manifest
import androidx.annotation.PluralsRes
import androidx.compose.ui.res.pluralStringResource
import java.time.ZoneId
import java.time.LocalDateTime
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
import com.pockettravel.core.ui.DownloadProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.provider.Settings
import android.view.View
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Guida passo passo dentro la scheda Navigatore, come nelle app di navigazione: la mappa a tutto
 * schermo che segue la posizione, in alto il riquadro con la prossima svolta (e quella dopo), in basso
 * tempo e distanza rimasti, ora di arrivo e "Termina"; toccando il pannello si vedono le svolte
 * successive. Gli altri stati (permesso, GPS, calcolo, errori) prendono lo stesso schermo.
 * Con bici e auto l'avviso su autovelox, limiti e zone a traffico limitato resta nel pannello: i
 * dati di percorso (BRouter/OSM) non li hanno.
 */
@Composable
fun NavigationGuidance(
    state: NavigationUiState,
    destinationName: String,
    travelMode: TravelMode,
    // Ora a cui si vuole arrivare ("Arriva alle…"), null se non scelta: il pannello dice se si e' in ritardo.
    arriveBy: LocalDateTime?,
    // Mappa della guida: lo stile della scheda Mappa, con una sorgente per ognuna delle regioni del percorso.
    tileSource: OfflineTileSource,
    regionIds: List<String>,
    onPermissionResult: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    // Vibrazione a piedi (Impostazioni) e nomi delle strade trovati dalla mappa, per la notifica della guida.
    walkingHaptics: Boolean = true,
    onStreetNames: (Map<Int, String>) -> Unit = {},
    // In auto, il lato di guida del paese se diverso da quello di casa: un avviso sempre visibile.
    drivingSide: DrivingSide? = null,
    // Percorsi mancanti a meta' strada: la regione del catalogo da scaricare (null se non si sa) e il suo avanzamento 0..1.
    missingRegion: MissingRegion? = null,
    downloadProgress: Float? = null,
    onDownloadRouting: () -> Unit = {},
) {
    val context = LocalContext.current
    // Il GPS vuole la posizione precisa: con la sola approssimativa si resta senza navigazione.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        onPermissionResult(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    // Schermo acceso mentre si naviga, come in ogni navigatore, e guida visibile anche sopra la schermata
    // di blocco: col telefono bloccato si vede la svolta senza sbloccarlo (il resto dell'app resta protetto).
    val view = LocalView.current
    val activity = LocalActivity.current
    val navigating = state is NavigationUiState.Navigating
    DisposableEffect(navigating) {
        view.keepScreenOn = navigating
        activity?.showWhenLocked(navigating)
        onDispose {
            view.keepScreenOn = false
            activity?.showWhenLocked(false)
        }
    }

    if (state is NavigationUiState.Navigating) {
        Guidance(state, destinationName, travelMode, arriveBy, tileSource, regionIds, onClose, walkingHaptics, onStreetNames, drivingSide)
        return
    }
    // Senza percorso da seguire: un messaggio al centro, con la meta in cima e sempre "Termina".
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (destinationName.isNotBlank()) {
                Text(
                    stringResource(R.string.navigation_title, destinationName),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
            }
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
                    Waiting(stringResource(R.string.navigation_calculating))
                    // Stima del motore: per i calcoli brevi non serve, compare dopo il primo secondo.
                    if (state.elapsedSeconds >= 1) {
                        DownloadProgressIndicator(progress = { state.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.navigation_calculating_progress, (state.progress * 100).roundToInt()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.elapsedSeconds >= LONG_CALCULATION_SECONDS) {
                        Text(stringResource(R.string.navigation_calculating_long), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
                // L'arrivo lo chiude il Navigatore (snackbar e vibrazione, o in silenzio): qui non si resta.
                NavigationUiState.Arrived -> Unit
                // Percorsi mancanti a meta' strada: partenza o arrivo fuori dalla zona dei percorsi scaricati.
                is NavigationUiState.Unavailable -> if (state.result == RouteResult.NoRoutingData && missingRegion != null) {
                    if (downloadProgress != null) {
                        Waiting(stringResource(R.string.planner_routing_progress, (downloadProgress * 100).toInt()))
                        DownloadProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Message(
                            text = stringResource(R.string.navigation_missing_routing),
                            action = stringResource(R.string.navigation_routing_download_region, missingRegion.name),
                            onAction = onDownloadRouting,
                        )
                    }
                } else {
                    Message(
                        text = stringResource(
                            when (state.result) {
                                RouteResult.NoRoutingData -> R.string.navigation_outside_routing
                                RouteResult.NotFound -> R.string.navigation_not_found
                                RouteResult.TimedOut -> R.string.navigation_timed_out
                                else -> R.string.navigation_failed
                            },
                        ),
                        action = stringResource(R.string.navigation_retry),
                        onAction = onRetry,
                    )
                }
                is NavigationUiState.Navigating -> Unit
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.navigation_close)) }
        }
    }
}

@Composable
private fun SpeedCameraNotice() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(Spacing.s))
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun Guidance(
    state: NavigationUiState.Navigating,
    destinationName: String,
    travelMode: TravelMode,
    arriveBy: LocalDateTime?,
    tileSource: OfflineTileSource,
    regionIds: List<String>,
    onClose: () -> Unit,
    walkingHaptics: Boolean,
    onStreetNames: (Map<Int, String>) -> Unit,
    drivingSide: DrivingSide?,
) {
    val progress = state.progress
    val route = state.route
    // Nomi delle strade per indice del punto della svolta, trovati dalla mappa (NavigationMap).
    var streetNames by remember(route) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val tracker = remember(route) { NavigationTracker(route) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    val upcoming = route.instructions.drop(progress.nextInstructionIndex + 1)
    // A piedi una vibrazione leggera quando la svolta e' "Ora", una volta per svolta (l'arrivo ha la sua):
    // con il GPS che oscilla attorno ai 10 m la stessa svolta tornerebbe "Ora" piu' volte.
    val context = LocalContext.current
    val turnNow = progress.distanceToNextMeters < NOW_METERS && progress.nextInstruction.type != TurnType.ARRIVE
    var lastVibratedIndex by remember(route) { mutableStateOf(-1) }
    LaunchedEffect(progress.nextInstructionIndex, turnNow) {
        if (turnNow && travelMode == TravelMode.WALK && walkingHaptics && progress.nextInstructionIndex != lastVibratedIndex) {
            lastVibratedIndex = progress.nextInstructionIndex
            NavigationHaptics.turn(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavigationMap(
            tileSource = tileSource,
            regionIds = regionIds,
            route = route,
            pathToNext = progress.pathToNext,
            position = state.position,
            distanceToNextMeters = progress.distanceToNextMeters,
            onStreetNames = { found ->
                streetNames = streetNames + found
                onStreetNames(found)
            },
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.s)) {
            TurnBanner(progress, streetNames[progress.nextInstruction.pointIndex], rememberSpeedMps(progress.remainingMeters, travelMode))
            if (drivingSide != null && travelMode == TravelMode.CAR) DrivingSideNotice(drivingSide)
            // "Poi": la svolta dopo, in piccolo sotto il riquadro, come nelle app di navigazione.
            upcoming.firstOrNull()?.takeIf { progress.nextInstruction.type != TurnType.ARRIVE }?.let { then ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s)) {
                        Text(stringResource(R.string.navigation_then), style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Icon(turnIcon(then.type), contentDescription = turnText(then), modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (progress.offRoute) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text(
                        stringResource(R.string.navigation_off_route),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        // Pannello in basso: tempo rimasto in grande, distanza e ora di arrivo, "Termina"; toccandolo le svolte.
        Surface(
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
                // Tempo rimasto in proporzione alla distanza rimasta, dalla durata stimata da BRouter.
                val remainingSeconds = if (route.distanceMeters > 0) route.durationSeconds * progress.remainingMeters / route.distanceMeters else 0.0
                // Con un'ora di arrivo scelta, il tempo rimasto diventa rosso quando non ci si arriva piu'.
                val late = arriveBy?.let { minutesLate(it, remainingSeconds) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.navigation_show_steps)) { showSteps = !showSteps },
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            durationText(remainingSeconds),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (late != null && late > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.navigation_distance_arrival, distanceText(progress.remainingMeters), arrivalTime(remainingSeconds)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Un pulsante tondo con la X, come nelle app di navigazione: il nome lo legge TalkBack.
                    FilledTonalIconButton(
                        onClick = onClose,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(AppIcons.Close, contentDescription = stringResource(R.string.navigation_close))
                    }
                }
                if (arriveBy != null && late != null) ArrivalStatus(arriveBy, late)
                if (travelMode != TravelMode.WALK) {
                    Spacer(modifier = Modifier.height(Spacing.s))
                    SpeedCameraNotice()
                }
                AnimatedVisibility(visible = showSteps && upcoming.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp).padding(top = Spacing.s)) {
                        itemsIndexed(upcoming) { index, instruction ->
                            if (index > 0) HorizontalDivider()
                            // Metri dalla svolta precedente (per la prima, da quella nel riquadro in alto).
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
        }
    }
}

// In orario o in ritardo sull'ora scelta con "Arriva alle…": verde o rosso. TalkBack annuncia solo il
// passaggio fra i due stati (l'icona, con una descrizione fissa per stato), non i minuti che cambiano:
// il testo completo resta da leggere al focus.
@Composable
private fun ArrivalStatus(arriveBy: LocalDateTime, minutesLate: Long) {
    val context = LocalContext.current
    val time = remember(arriveBy) { DateFormat.getTimeFormat(context).format(java.util.Date.from(arriveBy.atZone(ZoneId.systemDefault()).toInstant())) }
    val isLate = minutesLate > 0
    Surface(
        color = if (isLate) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (isLate) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(top = Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s)) {
            Icon(
                ImageVector.vectorResource(if (isLate) UiR.drawable.ms_error else UiR.drawable.ms_schedule),
                contentDescription = if (isLate) stringResource(R.string.navigation_running_late) else stringResource(R.string.navigation_on_time, time),
                modifier = Modifier.size(18.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(modifier = Modifier.width(Spacing.s))
            Text(
                if (isLate) {
                    minutesPlural(R.plurals.navigation_late, minutesLate)
                } else {
                    stringResource(R.string.navigation_on_time, time)
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// Sempre visibile in auto dove si guida dall'altro lato rispetto a casa: subito sotto la svolta, dove si guarda.
@Composable
private fun DrivingSideNotice(side: DrivingSide) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s)) {
            Icon(ImageVector.vectorResource(UiR.drawable.ms_directions_car), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(Spacing.s))
            // "Paese con guida a" e il lato in grassetto: e' la parola che conta.
            val prefix = stringResource(R.string.navigation_drive_side)
            val sideText = stringResource(if (side == DrivingSide.LEFT) R.string.navigation_drive_left else R.string.navigation_drive_right)
            Text(
                buildAnnotatedString {
                    append(prefix)
                    append(' ')
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(sideText) }
                },
                style = MaterialTheme.typography.labelLarge,
                // Letto da TalkBack quando compare, poi resta fisso.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// Il riquadro della prossima svolta: freccia grande, distanza in grande, poi la svolta e la strada.
@Composable
private fun TurnBanner(progress: NavigationProgress, street: String?, speedMps: Double) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(Spacing.l)) {
            // Decorativa: il testo accanto dice gia' la svolta.
            Icon(turnIcon(progress.nextInstruction.type), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.width(Spacing.l))
            Column(modifier = Modifier.weight(1f)) {
                // La distanza a schermo cambia a ogni posizione GPS; TalkBack invece la annuncia solo alle soglie
                // (ogni 100 m, poi 50-40-30-20-10 m, allargate con la velocita'): ogni metro sarebbe rumore.
                if (progress.nextInstruction.type != TurnType.ARRIVE || progress.distanceToNextMeters > 0) {
                    val now = stringResource(R.string.navigation_now)
                    val threshold = distanceAnnouncement(progress.distanceToNextMeters, speedMps)
                    val announcement = if (threshold == null) now else distanceText(threshold.toDouble())
                    Text(
                        // Sotto i 10 m la svolta e' adesso: "Ora" invece di "10 m".
                        text = if (progress.distanceToNextMeters < NOW_METERS) now else distanceText(progress.distanceToNextMeters),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = announcement; liveRegion = LiveRegionMode.Polite },
                    )
                }
                // liveRegion: TalkBack legge la nuova indicazione (svolta e strada, stabili per ogni svolta) quando cambia.
                Column(modifier = Modifier.semantics(mergeDescendants = true) { heading(); liveRegion = LiveRegionMode.Polite }) {
                    Text(turnText(progress.nextInstruction), style = MaterialTheme.typography.titleMedium)
                    street?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

// "1 minuto", "2 minuti": 0 e 1 al singolare, mentre la regola di Android per l'italiano mette lo 0 al plurale.
@Composable
internal fun minutesPlural(@PluralsRes id: Int, minutes: Long): String =
    pluralStringResource(id, if (minutes <= 1) 1 else minutes.toInt(), minutes.toInt())

// Ora di arrivo nel formato del telefono (24 ore o AM/PM), da adesso piu' il tempo rimasto.
@Composable
private fun arrivalTime(remainingSeconds: Double): String {
    val context = LocalContext.current
    val minute = System.currentTimeMillis() / 60_000 + (remainingSeconds / 60).roundToInt()
    return remember(minute) { DateFormat.getTimeFormat(context).format(java.util.Date(minute * 60_000)) }
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
    val destinationColor = MaterialTheme.colorScheme.error.toArgb()
    // Sorgenti dello stile corrente: null durante un cambio di stile (tema, lingua).
    var sources by remember { mutableStateOf<NavigationSources?>(null) }

    LaunchedEffect(styleJson) {
        sources = null
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                val routeSource = GeoJsonSource(ROUTE_SOURCE).also(style::addSource)
                val nextSource = GeoJsonSource(NEXT_SOURCE).also(style::addSource)
                val positionSource = GeoJsonSource(POSITION_SOURCE).also(style::addSource)
                val destinationSource = GeoJsonSource(DESTINATION_SOURCE).also(style::addSource)
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
                // Arrivo sotto la posizione: vicino alla meta il punto blu resta sopra la bandiera.
                style.addImage(DESTINATION_PIN_IMAGE, pinBitmap(context, destinationColor, UiR.drawable.ms_flag, scale = DESTINATION_PIN_SCALE))
                style.addLayer(destinationPinLayer(DESTINATION_SOURCE))
                style.addLayer(
                    CircleLayer(POSITION_SOURCE, POSITION_SOURCE).withProperties(
                        PropertyFactory.circleColor(positionColor), PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeColor(positionStroke), PropertyFactory.circleStrokeWidth(3f),
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

/**
 * Velocita' effettiva lungo il percorso (m/s), dalla distanza rimasta che cala nel tempo, smussata perche' il
 * GPS salta. Finche' non c'e' una misura (primi secondi, fermi al semaforo) vale quella tipica del mezzo.
 */
@Composable
private fun rememberSpeedMps(remainingMeters: Double, travelMode: TravelMode): Double {
    val holder = remember { SpeedEstimate() }
    SideEffect { holder.update(remainingMeters, System.nanoTime()) }
    return holder.speed ?: when (travelMode) {
        TravelMode.WALK -> 1.4
        TravelMode.BIKE -> 4.5
        TravelMode.CAR -> 12.0
    }
}

internal class SpeedEstimate {
    var speed: Double? = null
        private set
    private var lastMeters = Double.NaN
    private var lastNanos = 0L

    fun update(remainingMeters: Double, nanos: Long) {
        if (!lastMeters.isNaN() && nanos > lastNanos) {
            val seconds = (nanos - lastNanos) / 1e9
            // Solo misure di almeno mezzo secondo e plausibili (ricalcoli e salti indietro si scartano).
            val measured = (lastMeters - remainingMeters) / seconds
            if (seconds >= 0.5 && measured in 0.0..70.0) {
                speed = speed?.let { it * 0.7 + measured * 0.3 } ?: measured
            }
        }
        lastMeters = remainingMeters
        lastNanos = nanos
    }
}

/**
 * La soglia (in metri) che TalkBack annuncia a [distanceMeters] dalla svolta: ogni 100 m, poi 50, 40, 30, 20,
 * 10 m; null sotto l'ultima (la svolta e' "Ora"). A [speedMps] piu' alte le soglie si moltiplicano (x2 in bici,
 * x5 in citta' in auto, x10 in strada veloce), cosi' l'annuncio arriva con un anticipo simile in secondi. Il
 * valore cambia solo al passaggio di una soglia: e' quello il momento in cui TalkBack lo rilegge.
 */
internal fun distanceAnnouncement(distanceMeters: Double, speedMps: Double): Int? {
    val scale = when {
        speedMps <= 2.5 -> 1
        speedMps <= 7.0 -> 2
        speedMps <= 20.0 -> 5
        else -> 10
    }
    val near = 10 * scale
    if (distanceMeters < near) return null
    val step = if (distanceMeters <= 50 * scale) near else 100 * scale
    return (ceil(distanceMeters / step) * step).toInt()
}

// API 27+ ha il metodo dell'attivita'; su Android 8.0 c'e' solo il flag della finestra.
private fun Activity.showWhenLocked(show: Boolean) {
    if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(show)
    } else {
        @Suppress("DEPRECATION")
        if (show) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }
}

/** Da quanti secondi di calcolo avvisare che un percorso lungo in auto puo' richiedere minuti. */
private const val LONG_CALCULATION_SECONDS = 5L

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
