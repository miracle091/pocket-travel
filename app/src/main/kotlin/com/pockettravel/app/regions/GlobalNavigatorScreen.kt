package com.pockettravel.app.regions

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.map.NavigationPlannerScreen
import com.pockettravel.feature.map.NavigationPlannerViewModel
import com.pockettravel.feature.map.NavigationViewModel

/**
 * Pianificatore e guida, uno solo per tutta l'app (legati all'activity, non alla schermata): la scheda della regione e
 * il Navigatore della barra mostrano la stessa destinazione e la stessa guida in corso, e una guida avviata da una
 * parte non compare dall'altra come "interrotta" (con un secondo GPS acceso).
 */
@Composable
fun navigatorViewModels(): Pair<NavigationPlannerViewModel, NavigationViewModel> {
    val activity = LocalActivity.current as ComponentActivity
    return hiltViewModel<NavigationPlannerViewModel>(activity) to hiltViewModel<NavigationViewModel>(activity)
}

/**
 * Il Navigatore con la rete stradale da scaricare (catalogo e download stanno in app, non in feature:map): nella scheda della
 * regione [regionId] (quella regione per prima) e nella barra principale (null, tutte le regioni scaricate).
 */
@Composable
fun RegionNavigatorScreen(
    regionId: String?,
    plannerViewModel: NavigationPlannerViewModel,
    navigationViewModel: NavigationViewModel,
    routingViewModel: RoutingDownloadViewModel = hiltViewModel(),
) {
    LaunchedEffect(regionId) { routingViewModel.load(regionId) }
    val downloadProgress by routingViewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadFailed by routingViewModel.downloadFailed.collectAsStateWithLifecycle()
    NavigationPlannerScreen(
        regionId = regionId,
        viewModel = plannerViewModel,
        navigationViewModel = navigationViewModel,
        onDownloadRouting = routingViewModel::downloadRouting,
        downloadProgress = downloadProgress,
        downloadFailed = downloadFailed,
        findMissingRegions = routingViewModel::missingRoutingRegions,
    )
}

/** Il Navigatore della barra principale: cerca e naviga fra tutte le nazioni scaricate. Senza nessuna, l'invito a scaricarne una. */
@Composable
fun GlobalNavigatorScreen(onOpenCountries: () -> Unit, routingViewModel: RoutingDownloadViewModel = hiltViewModel()) {
    val hasRegions by routingViewModel.hasRegions.collectAsStateWithLifecycle()
    val (plannerViewModel, navigationViewModel) = navigatorViewModels()
    // Senza barra del titolo: la voce selezionata nella barra in basso dice gia' dove si e', e la mappa ha piu' spazio.
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            when (hasRegions) {
                true -> RegionNavigatorScreen(regionId = null, plannerViewModel = plannerViewModel, navigationViewModel = navigationViewModel, routingViewModel = routingViewModel)
                false -> Column(
                    modifier = Modifier.fillMaxSize().padding(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.navigator_no_regions), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Button(onClick = onOpenCountries) { Text(stringResource(R.string.navigator_open_countries)) }
                }
                // Elenco delle nazioni installate non ancora letto: niente, per non mostrare l'invito per un attimo.
                null -> Unit
            }
        }
    }
}
