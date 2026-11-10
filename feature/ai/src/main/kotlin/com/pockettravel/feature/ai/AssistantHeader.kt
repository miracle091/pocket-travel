package com.pockettravel.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantHeader(uiState: AiUiState, isReady: Boolean, actions: AiActions) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    // Benchmark e confronto tra modelli: strumenti di sviluppo, solo nelle build di debug.
    val debugBuild = isDebugBuild()
    var showRemoveConfirm by rememberSaveable { mutableStateOf(false) }
    var showTaskManager by rememberSaveable { mutableStateOf(false) }
    val isOnDevice = uiState.mode == AiEngineMode.ON_DEVICE

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = if (isReady) Spacing.xs else Spacing.l, top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sotto i 3 GB di RAM l'assistente e' solo Online (vedi AiAssistantViewModel: mode e'
        // gia' forzato a ONLINE li'): niente selettore da mostrare, esiste un solo motore.
        if (uiState.isDeviceCapable) {
            ModeSelector(uiState, actions, modifier = Modifier.weight(1f))
        } else {
            Text(
                text = stringResource(R.string.ai_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
        if (isReady) {
            // La cronologia esiste solo con il motore Sul dispositivo: con Online non si salva niente.
            if (isOnDevice) {
                IconButton(onClick = actions.onOpenHistory) {
                    Icon(AppIcons.History, contentDescription = stringResource(R.string.ai_history))
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(AppIcons.More, contentDescription = stringResource(R.string.ai_more_actions))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (uiState.askedQuestion != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_new_conversation)) },
                            leadingIcon = { Icon(AppIcons.Add, contentDescription = null) },
                            onClick = { menuExpanded = false; actions.onNewConversation() },
                        )
                    }
                    if (isOnDevice && debugBuild) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (uiState.isBenchmarking) R.string.ai_benchmark_running else R.string.ai_benchmark_run)) },
                            enabled = !uiState.isBenchmarking,
                            onClick = { menuExpanded = false; actions.onRunBenchmark() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_benchmark_compare)) },
                            onClick = { menuExpanded = false; actions.onShowBenchmarkComparison() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_taskmgr_title)) },
                            onClick = { menuExpanded = false; showTaskManager = true },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (isOnDevice) R.string.ai_delete_model else R.string.ai_remove_key)) },
                        leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; showRemoveConfirm = true },
                    )
                }
            }
        }
    }

    val result = uiState.benchmarkResult
    val showBenchmark = debugBuild && isReady && isOnDevice
    if (showBenchmark && result != null && result.modelId == uiState.selectedModelId) {
        val base = benchmarkLine(result)
        Text(
            text = if (result.loadTimeMs > 0) stringResource(R.string.ai_benchmark_load_time, base, result.loadTimeMs / MS_PER_SECOND) else base,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
        )
        if (result.overheating) {
            Text(
                text = stringResource(R.string.ai_benchmark_overheating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
            )
        }
    }

    if (showTaskManager && debugBuild) LlmTaskManagerDialog(onDismiss = { showTaskManager = false })

    if (showRemoveConfirm) {
        ConfirmationDialog(
            title = stringResource(if (isOnDevice) R.string.ai_delete_model_title else R.string.ai_remove_key_title),
            message = stringResource(if (isOnDevice) R.string.ai_delete_model_message else R.string.ai_remove_key_message),
            onConfirm = {
                showRemoveConfirm = false
                if (isOnDevice) actions.onDeleteModel() else actions.onClearApiKey()
            },
            onDismiss = { showRemoveConfirm = false },
        )
    }
}

/** Un modello gia' sul dispositivo e utilizzabile, diverso da quello scelto (che non e' scaricato); null se non c'e'. */
internal fun downloadedAlternative(uiState: AiUiState): LlmModelDefinition? =
    if (uiState.downloadProgress != null) null
    else uiState.availableModels.firstOrNull { it.id != uiState.selectedModelId && it.id in uiState.downloadedModelIds }

// Il modello scelto non e' scaricato ma un altro si': usare quello subito, o scaricare quello scelto.
@Composable
internal fun DownloadedModelChoice(uiState: AiUiState, other: LlmModelDefinition, actions: AiActions) {
    val selected = uiState.availableModels.firstOrNull { it.id == uiState.selectedModelId }?.displayName ?: uiState.selectedModelId
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.m),
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(stringResource(R.string.ai_downloaded_model_choice, other.displayName, selected), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.s)) {
                FilledTonalButton(onClick = { actions.onModelSelected(other.id) }) { Text(stringResource(R.string.ai_use_model, other.displayName)) }
                TextButton(onClick = actions.onDownloadModel) { Text(stringResource(R.string.ai_download_selected, selected)) }
            }
        }
    }
}

// La scheda IA c'e' perche' una chiave e' salvata, ma il motore scelto e' "Sul dispositivo" senza modello:
// si ricorda la chiave, per usarla subito, e la si puo' togliere se e' scaduta.
@Composable
internal fun SavedKeyReminder(uiState: AiUiState, actions: AiActions) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.m),
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(stringResource(R.string.ai_saved_key_reminder, uiState.onlineProvider.displayName), style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.padding(top = Spacing.s)) {
                FilledTonalButton(onClick = { actions.onModeChanged(AiEngineMode.ONLINE) }) { Text(stringResource(R.string.ai_use_online)) }
                Spacer(modifier = Modifier.width(Spacing.s))
                TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.ai_remove_key)) }
            }
        }
    }
    if (confirmRemove) {
        ConfirmationDialog(
            title = stringResource(R.string.ai_remove_key_title),
            message = stringResource(R.string.ai_remove_key_message),
            onConfirm = {
                confirmRemove = false
                actions.onClearApiKey()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
internal fun ModeSelector(uiState: AiUiState, actions: AiActions, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        AiEngineMode.entries.forEachIndexed { index, candidate ->
            SegmentedButton(
                selected = candidate == uiState.mode,
                onClick = { actions.onModeChanged(candidate) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AiEngineMode.entries.size),
            ) {
                Text(stringResource(if (candidate == AiEngineMode.ON_DEVICE) R.string.ai_mode_on_device else R.string.ai_mode_online))
            }
        }
    }
}
