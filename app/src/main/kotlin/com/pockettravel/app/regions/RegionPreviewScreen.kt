package com.pockettravel.app.regions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.feature.guide.GuideScreen

// Anteprima della guida Wikivoyage per una regione non ancora installata: scarica e importa solo
// content.db (vedi RegionGuidePreviewDownloader), niente mappa/routing — quelli restano dietro al
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
                        Icon(imageVector = AppIcons.Back, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Anteprima senza download", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Mappa e assistente offline richiedono il pacchetto completo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { viewModel.downloadFull(); onDownloadFull() }) {
                        Text("Scarica")
                    }
                }
            }

            when (val current = state) {
                RegionPreviewState.Loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                is RegionPreviewState.Error -> EmptyState(
                    icon = AppIcons.OfflineWifi,
                    title = current.message,
                    modifier = Modifier.fillMaxSize(),
                )

                RegionPreviewState.Ready -> GuideScreen(regionId = regionId, onOpenSource = onOpenSource)
            }
        }
    }
}
