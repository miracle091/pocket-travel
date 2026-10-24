package com.pockettravel.feature.map

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.mutableIntStateOf
import java.time.ZoneId
import java.time.LocalDateTime
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

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
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ActiveNavigationContent(
    state: NavigationStatus.Navigating,
    destinationName: String,
    routeProfile: RouteProfile,
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
    var lastVibratedIndex by remember(route) { mutableIntStateOf(-1) }
    LaunchedEffect(progress.nextInstructionIndex, turnNow) {
        if (turnNow && routeProfile == RouteProfile.WALK && walkingHaptics && progress.nextInstructionIndex != lastVibratedIndex) {
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
            TurnBanner(progress, streetNames[progress.nextInstruction.pointIndex], rememberSpeedMps(progress.remainingMeters, routeProfile))
            if (drivingSide != null && routeProfile == RouteProfile.CAR) DrivingSideNotice(drivingSide)
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
            // Senza posizione recente (galleria) la posizione sulla mappa e' l'ultima nota: lo si dice, e non si
            // parla di fuori percorso, che con la posizione ferma non si puo' sapere.
            if (progress.offRoute || state.signalLost) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Text(
                        stringResource(if (state.signalLost) R.string.navigation_signal_lost else R.string.navigation_off_route),
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
                if (routeProfile != RouteProfile.WALK) {
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
            // "Nazione con guida a" e il lato in grassetto: e' la parola che conta.
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

/** Sotto questa distanza dalla svolta si dice "Ora". */
private const val NOW_METERS = 10.0
