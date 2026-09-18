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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionListScreen(
    onRegionClick: (String) -> Unit,
    onOpenStorage: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenTutorial: () -> Unit,
    onOpenMap: (String) -> Unit,
    onOpenAi: (String) -> Unit,
    viewModel: RegionListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val lastRegionId = viewModel.lastRegionId()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "Pocket Travel",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                DrawerItem(label = "Regioni", icon = AppIcons.World, selected = true) {
                    scope.launch { drawerState.close() }
                }
                DrawerItem(label = "Mappa", icon = AppIcons.Map, enabled = lastRegionId != null) {
                    scope.launch { drawerState.close() }
                    lastRegionId?.let(onOpenMap)
                }
                DrawerItem(label = "Assistente IA", icon = AppIcons.AiAssistant, enabled = lastRegionId != null) {
                    scope.launch { drawerState.close() }
                    lastRegionId?.let(onOpenAi)
                }
                DrawerItem(label = "Spazio di archiviazione", icon = AppIcons.Settings) {
                    scope.launch { drawerState.close() }
                    onOpenStorage()
                }
                DrawerItem(label = "Fonti ufficiali", icon = AppIcons.OfficialAuthority) {
                    scope.launch { drawerState.close() }
                    onOpenSources()
                }
                DrawerItem(label = "Licenze", icon = AppIcons.Info) {
                    scope.launch { drawerState.close() }
                    onOpenLicenses()
                }
                DrawerItem(label = "Documenti", icon = AppIcons.passport()) {
                    scope.launch { drawerState.close() }
                    onOpenVault()
                }
                HorizontalDivider()
                DrawerItem(label = "Rivedi tutorial", icon = AppIcons.Help) {
                    scope.launch { drawerState.close() }
                    onOpenTutorial()
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Regioni") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.checkForUpdatesNow() }) {
                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Controlla aggiornamenti")
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
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(imageVector = icon, contentDescription = null) },
        selected = selected,
        onClick = { if (enabled) onClick() },
        modifier = Modifier.padding(horizontal = 12.dp).alpha(if (enabled) 1f else 0.4f),
    )
}

// internal, non private: riusata anche dallo step di onboarding "Scarica una regione" (stesso
// modulo :app, pacchetto diverso).
@Composable
internal fun RegionRow(item: RegionUiItem, viewModel: RegionListViewModel, onClick: () -> Unit) {
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
                val bytesDownloaded = workInfo?.progress?.getLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 0L) ?: 0L
                val totalBytes = workInfo?.progress?.getLong(RegionPackageDownloadWorker.KEY_TOTAL_BYTES, 0L) ?: 0L
                val progress = if (totalBytes > 0) bytesDownloaded / totalBytes.toFloat() else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun RegionActions(item: RegionUiItem, isDownloading: Boolean, viewModel: RegionListViewModel) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLargeDownloadWarning by remember { mutableStateOf(false) }

    val startDownload = {
        if (item.sizeBytes > LARGE_DOWNLOAD_WARNING_BYTES) {
            showLargeDownloadWarning = true
        } else {
            viewModel.download(item.regionId)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            RegionStatus.NOT_INSTALLED -> Button(onClick = startDownload, enabled = !isDownloading) {
                Text("Scarica")
            }
            RegionStatus.UPDATE_AVAILABLE -> Button(onClick = startDownload, enabled = !isDownloading) {
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

    if (showLargeDownloadWarning) {
        ConfirmationDialog(
            title = "Download di grandi dimensioni",
            message = "${item.displayName} pesa ${item.sizeBytes / (1024 * 1024)} MB. Continuare?",
            confirmLabel = "Scarica",
            onConfirm = { showLargeDownloadWarning = false; viewModel.download(item.regionId) },
            onDismiss = { showLargeDownloadWarning = false },
        )
    }
}

private fun RegionStatus.label(): String = when (this) {
    RegionStatus.NOT_INSTALLED -> "non installata"
    RegionStatus.INSTALLED -> "installata"
    RegionStatus.UPDATE_AVAILABLE -> "aggiornamento disponibile"
}
