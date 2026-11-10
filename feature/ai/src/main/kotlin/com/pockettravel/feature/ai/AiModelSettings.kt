package com.pockettravel.feature.ai

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnCellularNetwork
import kotlin.math.roundToInt
import com.pockettravel.core.ui.R as UiR

/**
 * Configurazione dell'assistente per le Impostazioni: motore (solo quelli che il dispositivo regge), modelli
 * sul dispositivo con download/eliminazione, oppure servizio, chiave e modello online. Il ViewModel e' lo stesso
 * della chat: fuori da una regione non serve altro, la regione conta solo quando si fa una domanda.
 */
@Composable
fun AiSettingsSection(
    viewModel: AiAssistantViewModel = hiltViewModel(key = aiViewModelKey()),
    readAloudViewModel: ReadAloudViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val networkVoices by readAloudViewModel.networkVoices.collectAsStateWithLifecycle()
    AiSettingsContent(
        uiState = uiState,
        actions = viewModel.toActions(regionId = ""),
        networkVoices = networkVoices,
        onNetworkVoicesChanged = readAloudViewModel::setNetworkVoices,
    )
}

@Composable
internal fun AiSettingsContent(
    uiState: AiUiState,
    actions: AiActions,
    // Le voci di rete per "Ascolta" (ReadAloudViewModel): non fanno parte dello stato dell'assistente.
    networkVoices: Boolean = false,
    onNetworkVoicesChanged: (Boolean) -> Unit = {},
) {
    var showRemoveKeyConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteHistoryConfirm by rememberSaveable { mutableStateOf(false) }
    if (showDeleteHistoryConfirm) {
        DeleteHistoryConfirmation(onConfirm = { showDeleteHistoryConfirm = false; actions.onDeleteHistory() }, onDismiss = { showDeleteHistoryConfirm = false })
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(R.string.ai_settings_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Sotto i 3 GB di RAM esiste solo Online (vedi AssistantHeader): niente selettore.
        if (uiState.isDeviceCapable) ModeSelector(uiState, actions, modifier = Modifier.fillMaxWidth())
        when (uiState.mode) {
            AiEngineMode.ON_DEVICE -> ModelList(uiState, actions)
            AiEngineMode.ONLINE -> {
                ApiKeySetup(uiState, actions)
                if (uiState.isApiKeyConfigured) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.ai_api_key_saved), modifier = Modifier.weight(1f))
                        TextButton(onClick = { showRemoveKeyConfirm = true }) { Text(stringResource(R.string.ai_remove_key)) }
                    }
                }
                SharePersonalDataSwitch(uiState.sharesPersonalData, actions.onSharesPersonalDataChanged)
            }
        }
        // "Ascolta" vale con entrambi i motori.
        SettingSwitch(R.string.ai_read_aloud_network_title, R.string.ai_read_aloud_network_hint, networkVoices, onNetworkVoicesChanged)
        // Anche con il motore Online: le conversazioni salvate prima restano finche' non si cancellano.
        if (uiState.savedConversations > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(
                    pluralStringResource(R.plurals.ai_history_saved_count, uiState.savedConversations, uiState.savedConversations),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showDeleteHistoryConfirm = true }) { Text(stringResource(R.string.ai_history_delete_all)) }
            }
        }
    }
    if (showRemoveKeyConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.ai_remove_key_title),
            message = stringResource(R.string.ai_remove_key_message),
            onConfirm = { showRemoveKeyConfirm = false; actions.onClearApiKey() },
            onDismiss = { showRemoveKeyConfirm = false },
        )
    }
}

/** Lista dei modelli disponibili per la fascia RAM del dispositivo (uiState.availableModels,
 *  gia' filtrata da AiAssistantViewModel). Un solo modello installato alla volta: selezionarne
 *  uno diverso da quello scaricato lo sostituisce a download finito (vedi LlmModelManager.selectAndDownload).
 *  Non private: riusata anche dallo step di onboarding "Scarica il modello IA" (modulo :app,
 *  quindi serve visibilita' public — un internal qui non basterebbe, e' un modulo diverso). */
@Composable
fun ModelListCard(uiState: AiUiState, viewModel: AiAssistantViewModel) {
    ModelList(uiState, viewModel.toActions(regionId = ""))
}

@Composable
internal fun ModelList(uiState: AiUiState, actions: AiActions) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.ai_choose_model),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = Spacing.s),
        )
        // Premessa: la differenza tra i due gruppi, prima delle liste
        Text(
            stringResource(R.string.ai_models_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Spacing.s),
        )
        uiState.availableModels.groupBy { it.origin }.toSortedMap().forEach { (origin, models) ->
            Text(
                stringResource(if (origin == ModelOrigin.ADDESTRATO) R.string.ai_models_trained else R.string.ai_models_official),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs).semantics { heading() },
            )
            Text(
                stringResource(if (origin == ModelOrigin.ADDESTRATO) R.string.ai_models_trained_desc else R.string.ai_models_official_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s),
            )
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    models.forEach { definition ->
                        ModelRow(definition = definition, isSelected = definition.id == uiState.listedModelId, uiState = uiState, actions = actions)
                    }
                }
            }
        }
        ImportModelButton(uiState, actions)
    }
}

/** "Importa da file": il selettore dei documenti, poi la copia e il confronto con il catalogo. */
@Composable
private fun ImportModelButton(uiState: AiUiState, actions: AiActions) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val (name, size) = queryDocument(context, uri)
            actions.onImportModel(name, size) { context.contentResolver.openInputStream(uri) }
        }
    }
    Column(modifier = Modifier.padding(top = Spacing.m)) {
        TextButton(enabled = !uiState.isImportingModel, onClick = { picker.launch(arrayOf("*/*")) }) {
            Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.s))
            Text(stringResource(R.string.ai_model_import))
        }
        if (uiState.isImportingModel) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs))
        }
        uiState.importError?.let {
            Text(
                text = stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

// Dopo un errore del download il pulsante propone "Riprova" invece di "Scarica il modello".
internal fun Int?.isDownloadError(): Boolean =
    this == R.string.ai_error_download || this == R.string.ai_error_integrity || this == R.string.ai_error_space

@Composable
private fun ModelRow(definition: LlmModelDefinition, isSelected: Boolean, uiState: AiUiState, actions: AiActions) {
    val context = LocalContext.current
    var showLargeDownloadWarning by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    // Peso del file e, quando e' stimata, la RAM che il modello usa (ModelRamEstimates).
    val size = Formatter.formatShortFileSize(context, definition.sizeBytes).let { file ->
        uiState.ramEstimates[definition.id]?.let {
            "$file · ${stringResource(R.string.ai_model_ram_estimate, Formatter.formatShortFileSize(context, it))}"
        } ?: file
    }
    val available = definition.sha256 != null

    Column {
        ListItem(
            supportingContent = {
                Column {
                    Text(
                        when {
                            !available -> "$size · ${stringResource(R.string.ai_model_coming_soon)}"
                            isSelected -> "$size · ${stringResource(R.string.ai_model_selected)}"
                            else -> size
                        },
                    )
                    // Modello di una fascia di RAM sopra quella del dispositivo, dalla stima o dal catalogo.
                    if (definition.id in uiState.modelsNeedingMoreRam) {
                        Text(
                            stringResource(R.string.ai_model_needs_more_ram, uiState.minRamTier(definition).gigabytes),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            leadingContent = {
                Icon(if (isSelected) AppIcons.Check else AppIcons.AiAssistant, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(enabled = !isSelected && available) { actions.onModelSelected(definition.id) },
            content = { Text(definition.displayName) },
        )
        // sha256 == null: modello non ancora pubblicato (in attesa di fine-tuning/upload
        // proprio, vedi LlmModelCatalog) — nessun pulsante di download da mostrare, non c'e'
        // nulla da scaricare finche' non viene pubblicata un'impronta reale.
        if (isSelected && available) {
            Column(modifier = Modifier.padding(start = 56.dp, end = Spacing.l, bottom = Spacing.l)) {
                uiState.errorMessage?.let {
                    Text(text = stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = Spacing.s))
                }
                val downloadProgress = uiState.downloadProgress
                if (uiState.isModelDownloaded && uiState.pendingModelId == null) {
                    // Gia' installato (la lista si vede anche dalle Impostazioni, non solo prima del download).
                    Text(stringResource(R.string.ai_model_downloaded), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.ai_delete_model))
                    }
                    TextButton(onClick = { actions.sendableModelFile(definition)?.let { shareModelFile(context, it) } }) {
                        Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.ai_model_send))
                    }
                } else if (downloadProgress != null) {
                    Text(
                        stringResource(R.string.ai_model_downloading, (downloadProgress * PERCENT_SCALE).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DownloadProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs))
                    TextButton(onClick = actions.onCancelDownload) { Text(stringResource(UiR.string.confirm_cancel)) }
                } else {
                    Button(
                        onClick = {
                            if (definition.sizeBytes > LARGE_DOWNLOAD_WARNING_BYTES && isOnCellularNetwork(context)) {
                                showLargeDownloadWarning = true
                            } else {
                                actions.onDownloadModel()
                            }
                        },
                    ) {
                        Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(if (uiState.errorMessage.isDownloadError()) R.string.ai_model_retry else R.string.ai_model_download))
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.ai_delete_model_title),
            message = stringResource(R.string.ai_delete_model_message),
            onConfirm = { showDeleteConfirm = false; actions.onDeleteModel() },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showLargeDownloadWarning) {
        ConfirmationDialog(
            title = stringResource(R.string.ai_large_download_title),
            message = stringResource(R.string.ai_large_download_message, definition.displayName, size),
            confirmLabel = stringResource(R.string.ai_large_download_confirm),
            destructive = false,
            onConfirm = { showLargeDownloadWarning = false; actions.onDownloadModel() },
            onDismiss = { showLargeDownloadWarning = false },
        )
    }
}

// I risultati salvati prima della misura in token/s restano in parole al secondo fino a un nuovo benchmark.
@Composable
internal fun benchmarkLine(result: BenchmarkResult): String =
    if (result.genTokensPerSecond > 0f) {
        stringResource(R.string.ai_benchmark_tokens, result.genTokensPerSecond.roundToInt(), result.promptTokensPerSecond.roundToInt(), result.qualityScore)
    } else {
        stringResource(R.string.ai_benchmark_result, result.wordsPerSecond.toInt(), result.qualityScore)
    }

@Composable
internal fun BenchmarkComparisonDialog(uiState: AiUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_close)) } },
        title = { Text(stringResource(R.string.ai_benchmark_dialog_title)) },
        text = {
            Column {
                // Solo i modelli con un risultato salvato (LlmModelCatalog.ALL puo' includere
                // modelli mai scaricati su questo dispositivo, es. per una fascia RAM diversa) — un
                // modello non ancora provato non aggiunge informazione utile al confronto.
                val results = uiState.allBenchmarkResults.sortedByDescending { it.qualityScore }
                if (results.isEmpty()) {
                    Text(stringResource(R.string.ai_benchmark_none))
                } else {
                    results.forEach { result ->
                        val displayName = LlmModelCatalog.ALL.firstOrNull { it.id == result.modelId }?.displayName ?: result.modelId
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                benchmarkLine(result),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
    )
}
