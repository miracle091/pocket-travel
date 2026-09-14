package com.pockettravel.app.regions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionListScreen(
    onRegionClick: (String) -> Unit,
    onOpenStorage: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenVault: () -> Unit,
    viewModel: RegionListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Regioni") },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Altre opzioni")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Spazio di archiviazione") },
                            leadingIcon = { MenuIcon(AppIcons.Settings) },
                            onClick = { menuExpanded = false; onOpenStorage() },
                        )
                        DropdownMenuItem(
                            text = { Text("Fonti ufficiali") },
                            leadingIcon = { MenuIcon(AppIcons.OfficialAuthority) },
                            onClick = { menuExpanded = false; onOpenSources() },
                        )
                        DropdownMenuItem(
                            text = { Text("Licenze") },
                            leadingIcon = { MenuIcon(AppIcons.Info) },
                            onClick = { menuExpanded = false; onOpenLicenses() },
                        )
                        DropdownMenuItem(
                            text = { Text("Documenti") },
                            leadingIcon = { MenuIcon(AppIcons.passport()) },
                            onClick = { menuExpanded = false; onOpenVault() },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp)) {
            when {
                uiState.isLoading && uiState.items.isEmpty() -> CircularProgressIndicator()
                uiState.errorMessage != null -> Text(text = uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
                else -> LazyColumn {
                    items(uiState.items, key = { it.regionId }) { item ->
                        RegionRow(item = item, viewModel = viewModel, onClick = { onRegionClick(item.regionId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuIcon(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
}

@Composable
private fun RegionRow(item: RegionUiItem, viewModel: RegionListViewModel, onClick: () -> Unit) {
    val workInfo by remember(item.regionId) { viewModel.observeDownloadProgress(item.regionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val isDownloading = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    // Una regione non ancora scaricata non ha contenuti locali da mostrare: il tap non porta
    // da nessuna parte, l'unica azione sensata resta il tasto "Scarica" qui sotto.
    val isNavigable = item.status != RegionStatus.NOT_INSTALLED

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(enabled = isNavigable, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.World,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = item.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(text = "${item.sizeBytes / (1024 * 1024)} MB · ${item.status.label()}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                RegionActions(item = item, isDownloading = isDownloading, viewModel = viewModel)
            }

            if (isDownloading) {
                val filesDone = workInfo?.progress?.getInt(RegionPackageDownloadWorker.KEY_FILES_DONE, 0) ?: 0
                val totalFiles = workInfo?.progress?.getInt(RegionPackageDownloadWorker.KEY_TOTAL_FILES, 0) ?: 0
                val progress = if (totalFiles > 0) filesDone / totalFiles.toFloat() else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun RegionActions(item: RegionUiItem, isDownloading: Boolean, viewModel: RegionListViewModel) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            RegionStatus.NOT_INSTALLED -> Button(onClick = { viewModel.download(item.regionId) }, enabled = !isDownloading) {
                Text("Scarica")
            }
            RegionStatus.UPDATE_AVAILABLE -> Button(onClick = { viewModel.download(item.regionId) }, enabled = !isDownloading) {
                Text("Aggiorna")
            }
            RegionStatus.INSTALLED -> OutlinedButton(onClick = { showDeleteConfirm = true }) {
                Text("Elimina")
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = "Eliminare ${item.displayName}?",
            message = "I contenuti scaricati per questa regione verranno rimossi dal dispositivo.",
            onConfirm = { showDeleteConfirm = false; viewModel.delete(item.regionId) },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

private fun RegionStatus.label(): String = when (this) {
    RegionStatus.NOT_INSTALLED -> "non installata"
    RegionStatus.INSTALLED -> "installata"
    RegionStatus.UPDATE_AVAILABLE -> "aggiornamento disponibile"
}
