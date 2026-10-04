package com.pockettravel.app.regions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.guide.GuideScreen
import com.pockettravel.core.ui.R as UiR

// Anteprima della guida Wikivoyage per una regione non ancora installata: legge il pacchetto guide
// (scaricato qui se manca, vedi RegionPreviewViewModel), niente mappa/routing/POI — quelli restano dietro al
// download completo, avviabile da qui col banner in alto. Riusa GuideScreen cosi' com'e': legge
// da Room come per una regione installata, senza distinguere tra "installata" e "solo anteprima".
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegionPreviewScreen(
    regionId: String,
    displayName: String,
    onBack: () -> Unit,
    // La riga della regione nell'elenco e le sue azioni: il download completo passa dallo stesso percorso dell'elenco
    // (civici, spazio libero, avviso su rete cellulare, scelta della zona). item e' null finche' l'elenco non l'ha.
    item: RegionUiItem?,
    rowActions: RegionRowActions,
    onDownloadFull: () -> Unit,
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    viewModel: RegionPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(regionId) { viewModel.load() }
    // Si torna all'elenco (dove si segue l'avanzamento) solo quando il download e' davvero partito, non alla prima pressione.
    val downloadActions = remember(rowActions, onDownloadFull) {
        rowActions.copy(
            onDownload = { id -> rowActions.onDownload(id); onDownloadFull() },
            onZoneChange = { id, zone, download ->
                rowActions.onZoneChange(id, zone, download)
                if (download && id == regionId) onDownloadFull()
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Banner M3: superficie tertiaryContainer con testo e azione, non una card generica.
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            ) {
                Row(modifier = Modifier.padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Info, contentDescription = null)
                    Spacer(modifier = Modifier.width(Spacing.m))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.preview_banner_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.preview_banner_body), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.width(Spacing.s))
                    // Solo con la guida pronta e la regione nell'elenco: prima non ci sarebbe nulla da scaricare.
                    if (item != null) {
                        RegionDownloadFlow(item = item, actions = downloadActions) { _, onDownloadClick ->
                            FilledTonalButton(onClick = onDownloadClick, enabled = state == RegionPreviewState.Ready) {
                                Text(stringResource(R.string.preview_download))
                            }
                        }
                    } else {
                        FilledTonalButton(onClick = {}, enabled = false) { Text(stringResource(R.string.preview_download)) }
                    }
                }
            }

            when (state) {
                RegionPreviewState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PocketTravelLoadingIndicator()
                }

                RegionPreviewState.Error -> EmptyState(
                    icon = AppIcons.OfflineWifi,
                    title = stringResource(R.string.preview_error),
                    modifier = Modifier.fillMaxSize(),
                )

                RegionPreviewState.Ready -> GuideScreen(regionId = regionId, onOpenSource = onOpenSource)
            }
        }
    }
}
