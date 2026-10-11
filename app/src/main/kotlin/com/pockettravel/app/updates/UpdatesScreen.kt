package com.pockettravel.app.updates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.app.regions.RegionDownloadFlow
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.app.regions.RegionRowActions
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.app.regions.rowActions
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnMeteredNetwork
import com.pockettravel.core.ui.R as UiR

// Schermata "Aggiornamenti" (da Altro e dalle notifiche): app, nazioni, guide e modello IA con una versione nuova. Le nazioni
// si aggiornano dal percorso di download dell'elenco (RegionListViewModel e RegionDownloadFlow), non da uno proprio.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    onBack: () -> Unit,
    regionListViewModel: RegionListViewModel,
    viewModel: UpdatesViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState by regionListViewModel.uiState.collectAsStateWithLifecycle()
    val downloadingRegions by regionListViewModel.downloadingRegions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Aperta dalla notifica prima dell'elenco, la lingua dei nomi delle nazioni non è ancora stata passata al ViewModel.
    val locale = LocalLocale.current.platformLocale
    LaunchedEffect(locale) { regionListViewModel.setLocale(locale) }

    val snackbarHostState = remember { SnackbarHostState() }
    val message = (state.message ?: listState.message)?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
            regionListViewModel.onMessageShown()
        }
    }

    val outdated = outdatedRegions(listState.items)
    val pending = pendingRegionUpdates(listState.items, downloadingRegions)
    val rowActions = regionListViewModel.rowActions()
    var confirmUpdateAll by rememberSaveable { mutableStateOf(false) }
    val nothingToUpdate = updatesCount(state.appUpdate != null, outdated.size, state.guidesUpdate != null, state.modelUpdate != null) == 0
    val checking = state.isChecking || listState.isLoading

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.more_updates)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            state.appUpdate?.let { AppSection(it) }
            if (outdated.isNotEmpty()) {
                RegionsSection(
                    outdated = outdated,
                    pending = pending,
                    rowActions = rowActions,
                    onUpdateAll = {
                        if (updateAllNeedsConfirmation(isOnMeteredNetwork(context), pending)) confirmUpdateAll = true else updateAll(pending, rowActions)
                    },
                )
            }
            state.guidesUpdate?.let { GuidesSection(it, syncing = state.guidesSyncing, onUpdate = viewModel::updateGuides) }
            state.modelUpdate?.let { ModelSection(it, downloading = state.modelDownloading, progress = state.modelProgress, onDownload = viewModel::downloadModel) }

            if (nothingToUpdate) NothingToUpdate(checking, offline = state.offline)

            CheckNowButton(
                checking = checking,
                onClick = {
                    regionListViewModel.checkForUpdatesNow()
                    viewModel.refresh(userRequested = true)
                },
            )
        }
    }

    if (confirmUpdateAll) {
        ConfirmationDialog(
            title = stringResource(R.string.updates_metered_title, updateTotal(pending).text(context)),
            message = stringResource(R.string.updates_metered_message),
            confirmLabel = stringResource(R.string.regions_large_download_confirm),
            destructive = false,
            onConfirm = { confirmUpdateAll = false; updateAll(pending, rowActions) },
            onDismiss = { confirmUpdateAll = false },
        )
    }
}

// Lo stesso download di ogni riga dell'elenco (spazio libero, varianti della rete stradale, reti dei mezzi pubblici, celle dei civici).
private fun updateAll(pending: List<RegionUiItem>, actions: RegionRowActions) {
    pending.forEach { actions.onDownload(it.regionId) }
}

// Senza aggiornamenti: la rotella finche' il primo controllo non ha risposto, poi "Tutto aggiornato", o senza rete
// l'avviso che il controllo non si e' potuto fare.
@Composable
private fun NothingToUpdate(checking: Boolean, offline: Boolean) {
    when {
        checking -> Box(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxl), contentAlignment = Alignment.Center) {
            PocketTravelLoadingIndicator()
        }
        offline -> EmptyState(
            icon = AppIcons.OfflineWifi,
            title = stringResource(R.string.updates_offline_title),
            subtitle = stringResource(R.string.updates_offline),
        )
        else -> EmptyState(icon = AppIcons.Check, title = stringResource(R.string.updates_up_to_date))
    }
}

@Composable
private fun CheckNowButton(checking: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = !checking,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl, bottom = Spacing.l),
    ) {
        if (checking) {
            PocketTravelLoadingIndicator(modifier = Modifier.size(24.dp))
        } else {
            Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(stringResource(R.string.regions_check_updates), modifier = Modifier.padding(start = Spacing.s))
    }
}
