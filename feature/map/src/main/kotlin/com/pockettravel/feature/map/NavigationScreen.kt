package com.pockettravel.feature.map

import androidx.compose.material3.FilledTonalIconButton
import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import android.os.Build
import android.app.Activity
import android.Manifest
import java.time.LocalDateTime
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import com.pockettravel.core.ui.DownloadProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Navigazione passo passo dentro la scheda Navigatore, come nelle app di navigazione: la mappa a tutto
 * schermo che segue la posizione, in alto il riquadro con la prossima svolta (e quella dopo), in basso
 * tempo e distanza rimasti, ora di arrivo e "Termina"; toccando il pannello si vedono le svolte
 * successive. Gli altri stati (permesso, GPS, calcolo, errori) prendono lo stesso schermo.
 * Con bici e auto l'avviso su autovelox, limiti e zone a traffico limitato resta nel pannello: i
 * segmenti della rete stradale (BRouter/OSM) non li hanno.
 */
@Composable
fun NavigationInstructions(
    state: NavigationStatus,
    destinationName: String,
    routeProfile: RouteProfile,
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
    // In auto, il lato di guida della nazione se diverso da quello di casa: un avviso sempre visibile.
    drivingSide: DrivingSide? = null,
    // Rete stradale mancante a meta' strada: le regioni del catalogo da scaricare, in ordine (vuota se non si sa), l'avanzamento
    // 0..1 e l'esito del download.
    missingRegions: List<MissingRegion> = emptyList(),
    downloadProgress: Float? = null,
    downloadFailed: Boolean = false,
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
    val navigating = state is NavigationStatus.Navigating
    DisposableEffect(navigating) {
        view.keepScreenOn = navigating
        activity?.showWhenLocked(navigating)
        onDispose {
            view.keepScreenOn = false
            activity?.showWhenLocked(false)
        }
    }

    if (state is NavigationStatus.Navigating) {
        ActiveNavigationContent(state, destinationName, routeProfile, arriveBy, tileSource, regionIds, onClose, walkingHaptics, onStreetNames, drivingSide)
        return
    }
    // Senza percorso da seguire: un messaggio al centro, con la destinazione in cima e sempre "Termina".
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
                NavigationStatus.NeedsPermission -> Message(
                    text = stringResource(R.string.navigation_permission),
                    action = stringResource(R.string.navigation_permission_grant),
                    onAction = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                )
                NavigationStatus.GpsDisabled -> Message(
                    text = stringResource(R.string.navigation_gps_disabled),
                    action = stringResource(R.string.navigation_gps_settings),
                    onAction = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
                )
                NavigationStatus.WaitingForFix -> Waiting(stringResource(R.string.navigation_waiting_fix))
                is NavigationStatus.Calculating -> {
                    Waiting(stringResource(R.string.navigation_calculating))
                    // Stima del motore: per i calcoli brevi non serve, compare dopo il primo secondo.
                    if (state.elapsedSeconds >= 1) {
                        DownloadProgressIndicator(progress = { state.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.navigation_calculating_progress, (state.progress * PERCENT_SCALE).roundToInt()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.elapsedSeconds >= LONG_CALCULATION_SECONDS) {
                        Text(stringResource(R.string.navigation_calculating_long), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
                // L'arrivo lo chiude il Navigatore (snackbar e vibrazione, o in silenzio): qui non si resta.
                NavigationStatus.Arrived -> Unit
                // Rete stradale mancante a meta' strada: partenza o arrivo fuori dalla zona della rete stradale scaricata.
                is NavigationStatus.Unavailable -> if (state.result == RouteResult.NoRoutingData && missingRegions.isNotEmpty()) {
                    MissingRoutingCard(missingRegions, downloadProgress, downloadFailed, onDownloadRouting)
                } else {
                    RetryMessage(
                        text = stringResource(
                            when (state.result) {
                                RouteResult.NoRoutingData -> R.string.navigation_outside_routing
                                RouteResult.NotFound -> R.string.navigation_not_found
                                RouteResult.TimedOut -> R.string.navigation_timed_out
                                else -> R.string.navigation_failed
                            },
                        ),
                        onRetry = onRetry,
                    )
                }
                is NavigationStatus.Navigating -> Unit
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.navigation_close)) }
        }
    }
}

/**
 * Percorso in sedia a rotelle: i dati OSM su scale, cordoli e fondi sono incompleti, e le scale sono
 * vietate finche' l'utente non accetta qualche gradino (allow_steps del profilo wheelchair).
 */
@Composable
internal fun WheelchairOptions(allowSteps: Boolean, onAllowStepsChange: (Boolean) -> Unit) {
    // Una riga sola: la mappa sotto prende lo spazio che resta. Row invece di ListItem, senza il suo
    // margine laterale, cosi' testo e interruttore si allineano al tempo e al pulsante Avvia sopra.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = allowSteps, role = Role.Switch, onValueChange = onAllowStepsChange)
            .padding(vertical = Spacing.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.navigation_allow_steps), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.navigation_wheelchair_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.m))
        Switch(checked = allowSteps, onCheckedChange = null)
    }
}

@Composable
private fun Message(text: String, action: String, onAction: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Button(onClick = onAction) { Text(action) }
}

// Errore con "Riprova" come icona sulla stessa riga del messaggio, a destra.
@Composable
private fun RetryMessage(text: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(modifier = Modifier.width(Spacing.m))
        FilledTonalIconButton(onClick = onRetry) {
            Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.navigation_retry))
        }
    }
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

// Conversioni di unita'.
private const val PERCENT_SCALE = 100

// API 27+ ha il metodo dell'attivita'; su Android 8.0 c'e' solo il flag della finestra.
private fun Activity.showWhenLocked(show: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(show)
    } else {
        @Suppress("DEPRECATION")
        if (show) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    }
}

/** Da quanti secondi di calcolo avvisare che un percorso lungo in auto puo' richiedere minuti. */
private const val LONG_CALCULATION_SECONDS = 5L
