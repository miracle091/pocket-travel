package com.pockettravel.app.regions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.guide.GuideScreen
import com.pockettravel.core.ui.R as UiR

// Anteprima della guida Wikivoyage per una regione non ancora installata: legge il pacchetto guide
// (scaricato qui se manca, vedi RegionPreviewViewModel), niente mappa/routing/POI — quelli restano dietro al
// download completo, avviabile da qui col banner in alto. Riusa GuideScreen cosi' com'e': legge
// da Room esattamente come farebbe per una regione installata, non sa (ne' le importa) la
// differenza tra "installata" e "solo anteprima".
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionPreviewScreen(
    regionId: String,
    displayName: String,
    onBack: () -> Unit,
    onDownloadFull: () -> Unit,
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    viewModel: RegionPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(regionId) { viewModel.load(regionId) }

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
                    FilledTonalButton(onClick = { viewModel.downloadFull(); onDownloadFull() }) {
                        Text(stringResource(R.string.preview_download))
                    }
                }
            }

            when (state) {
                RegionPreviewState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
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
