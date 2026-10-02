package com.pockettravel.feature.ai

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnCellularNetwork

@Composable
fun AiAssistantScreen(
    regionId: String,
    viewModel: AiAssistantViewModel = hiltViewModel(),
    onOpenOfficialSource: (url: String) -> Unit = {},
    onNavigationRequest: (NavigationRequest) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.navigationRequests.collect(onNavigationRequest) }
    AiAssistantContent(uiState = uiState, actions = viewModel.toActions(regionId), onOpenOfficialSource = onOpenOfficialSource)
}

// Azioni della schermata, raccolte per separare la UI dal ViewModel (@Preview, onboarding).
data class AiActions(
    val onModeChanged: (AiEngineMode) -> Unit,
    val onModelSelected: (String) -> Unit,
    val onDownloadModel: () -> Unit,
    val onApiKeyInputChanged: (String) -> Unit,
    val onSaveApiKey: () -> Unit,
    val onQuestionChanged: (String) -> Unit,
    val onAsk: () -> Unit,
    val onDeleteModel: () -> Unit,
    val onClearApiKey: () -> Unit,
    val onRunBenchmark: () -> Unit,
    val onShowBenchmarkComparison: () -> Unit,
    val onHideBenchmarkComparison: () -> Unit,
    val onProviderSelected: (OnlineProvider) -> Unit = {},
    val onOnlineModelChanged: (String) -> Unit = {},
)

private fun AiAssistantViewModel.toActions(regionId: String) = AiActions(
    onModeChanged = ::onModeChanged,
    onModelSelected = ::onModelSelected,
    onDownloadModel = ::downloadModel,
    onApiKeyInputChanged = ::onApiKeyInputChanged,
    onSaveApiKey = ::saveApiKey,
    onProviderSelected = ::onProviderSelected,
    onOnlineModelChanged = ::onOnlineModelChanged,
    onQuestionChanged = ::onQuestionChanged,
    onAsk = { ask(regionId) },
    onDeleteModel = ::deleteModel,
    onClearApiKey = ::clearApiKey,
    onRunBenchmark = ::runBenchmark,
    onShowBenchmarkComparison = ::showBenchmarkComparison,
    onHideBenchmarkComparison = ::hideBenchmarkComparison,
)

@Composable
internal fun AiAssistantContent(uiState: AiUiState, actions: AiActions, onOpenOfficialSource: (url: String) -> Unit) {
    val isReady = when (uiState.mode) {
        AiEngineMode.ON_DEVICE -> uiState.isModelDownloaded
        AiEngineMode.ONLINE -> uiState.isApiKeyConfigured
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AssistantHeader(uiState = uiState, isReady = isReady, actions = actions)
        if (isReady) {
            Conversation(uiState = uiState, onOpenOfficialSource = onOpenOfficialSource, onClearApiKey = actions.onClearApiKey, modifier = Modifier.weight(1f))
            QuestionBar(uiState = uiState, actions = actions)
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
            ) {
                when (uiState.mode) {
                    AiEngineMode.ON_DEVICE -> {
                        if (uiState.isApiKeyConfigured) SavedKeyReminder(uiState, actions)
                        downloadedAlternative(uiState)?.let { other -> DownloadedModelChoice(uiState, other, actions) }
                        ModelList(uiState, actions)
                    }
                    AiEngineMode.ONLINE -> ApiKeySetup(uiState, actions)
                }
            }
        }
    }
    if (uiState.showBenchmarkComparison) {
        BenchmarkComparisonDialog(uiState, onDismiss = actions.onHideBenchmarkComparison)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantHeader(uiState: AiUiState, isReady: Boolean, actions: AiActions) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable { mutableStateOf(false) }
    val isOnDevice = uiState.mode == AiEngineMode.ON_DEVICE

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = if (isReady) Spacing.xs else Spacing.l, top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sotto i 4 GB di RAM l'assistente e' solo Online (vedi AiAssistantViewModel: mode e'
        // gia' forzato a ONLINE li'): niente selettore da mostrare, un solo modo esiste.
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
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(AppIcons.More, contentDescription = stringResource(R.string.ai_more_actions))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (isOnDevice) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (uiState.isBenchmarking) R.string.ai_benchmark_running else R.string.ai_benchmark_run)) },
                            enabled = !uiState.isBenchmarking,
                            onClick = { menuExpanded = false; actions.onRunBenchmark() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_benchmark_compare)) },
                            onClick = { menuExpanded = false; actions.onShowBenchmarkComparison() },
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
    if (isReady && isOnDevice && result != null && result.modelId == uiState.selectedModelId) {
        val base = stringResource(R.string.ai_benchmark_result, result.wordsPerSecond.toInt(), result.qualityScore)
        Text(
            text = if (result.loadTimeMs > 0) stringResource(R.string.ai_benchmark_load_time, base, result.loadTimeMs / 1000f) else base,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
        )
    }

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

/** Un modello gia' sul telefono e utilizzabile, diverso da quello scelto (che non e' scaricato); null se non c'e'. */
internal fun downloadedAlternative(uiState: AiUiState): LlmModelDefinition? =
    if (uiState.downloadProgress != null) null
    else uiState.availableModels.firstOrNull { it.id != uiState.selectedModelId && it.id in uiState.downloadedModelIds }

// Il modello scelto non e' scaricato ma un altro si': usare quello subito, o scaricare quello scelto.
@Composable
private fun DownloadedModelChoice(uiState: AiUiState, other: LlmModelDefinition, actions: AiActions) {
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

// La scheda IA c'e' perche' una chiave e' salvata, ma il modo scelto e' "sul telefono" senza modello:
// si ricorda la chiave, per usarla subito, e la si puo' togliere se e' scaduta.
@Composable
private fun SavedKeyReminder(uiState: AiUiState, actions: AiActions) {
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
private fun ModeSelector(uiState: AiUiState, actions: AiActions, modifier: Modifier = Modifier) {
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

/**
 * Configurazione dell'assistente per le Impostazioni: modo (solo quelli che il dispositivo regge), modelli
 * on-device con download/eliminazione, oppure servizio, chiave e modello online. Il ViewModel e' lo stesso
 * della chat: fuori da una regione non serve altro, la regione conta solo quando si fa una domanda.
 */
@Composable
fun AiSettingsSection(viewModel: AiAssistantViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AiSettingsContent(uiState = uiState, actions = viewModel.toActions(regionId = ""))
}

@Composable
internal fun AiSettingsContent(uiState: AiUiState, actions: AiActions) {
    var showRemoveKeyConfirm by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(
            stringResource(R.string.ai_settings_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Sotto i 4 GB di RAM esiste solo Online (vedi AssistantHeader): niente selettore.
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

@Composable
private fun Conversation(uiState: AiUiState, onOpenOfficialSource: (url: String) -> Unit, onClearApiKey: () -> Unit, modifier: Modifier = Modifier) {
    val asked = uiState.askedQuestion
    if (asked == null && !uiState.isThinking && uiState.errorMessage == null) {
        EmptyState(
            icon = AppIcons.AiAssistant,
            title = stringResource(R.string.ai_empty_title),
            subtitle = if (uiState.mode == AiEngineMode.ONLINE) {
                stringResource(R.string.ai_empty_subtitle_online, uiState.onlineProvider.displayName)
            } else {
                stringResource(R.string.ai_empty_subtitle)
            },
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (asked != null) {
            // Domanda dell'utente: bolla a destra, nel colore del primary container.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(asked, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Spacing.m))
                }
            }
        }
        // Risposta: bolla a sinistra su superficie tonale; liveRegion perche' TalkBack annunci la
        // risposta (o l'errore) quando arriva, senza dover cercare dove e' comparsa.
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            // animateContentSize: la bolla cresce con morbidezza da "Sto pensando…" alla risposta.
            Column(modifier = Modifier.animateContentSize().padding(Spacing.l)) {
                val answer = uiState.answer
                val error = uiState.errorMessage
                when {
                    uiState.isThinking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        PocketTravelLoadingIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(Spacing.m))
                        Text(stringResource(R.string.ai_thinking), style = MaterialTheme.typography.bodyLarge)
                    }
                    error != null -> Column {
                        Text(
                            stringResource(error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        // Chiave scaduta o revocata: si toglie da qui e se ne inserisce una nuova.
                        if (error == R.string.ai_error_key_rejected) {
                            TextButton(onClick = onClearApiKey) { Text(stringResource(R.string.ai_remove_key)) }
                        }
                    }
                    answer != null -> {
                        Text(answer.text, style = MaterialTheme.typography.bodyLarge)
                        answer.sourceCitations.forEach { citation ->
                            Text(
                                citation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.xs),
                            )
                        }
                        if (answer.showOfficialSourceBanner) {
                            val url = answer.officialSourceUrl
                            AssistChip(
                                onClick = { url?.let(onOpenOfficialSource) },
                                enabled = url != null,
                                label = { Text(stringResource(R.string.ai_verify_official)) },
                                leadingIcon = { Icon(AppIcons.VerifiedLink, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.padding(top = Spacing.s),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionBar(uiState: AiUiState, actions: AiActions) {
    val canSend = uiState.question.isNotBlank() && !uiState.isThinking
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = uiState.question,
            onValueChange = actions.onQuestionChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_question_hint)) },
            shape = MaterialTheme.shapes.extraLarge,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) actions.onAsk() }),
        )
        Spacer(modifier = Modifier.width(Spacing.s))
        FilledIconButton(onClick = actions.onAsk, enabled = canSend, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.Send, contentDescription = stringResource(R.string.ai_send))
        }
    }
}

/** Lista dei modelli disponibili per la fascia RAM del dispositivo (uiState.availableModels,
 *  gia' filtrata da AiAssistantViewModel). Un solo modello installato alla volta: selezionarne
 *  uno diverso da quello scaricato lo sostituisce (vedi LlmModelManager.selectAndDownload).
 *  Non private: riusata anche dallo step di onboarding "Scarica il modello IA" (modulo :app,
 *  quindi serve visibilita' public — un internal qui non basterebbe, e' un modulo diverso). */
@Composable
fun ModelListCard(uiState: AiUiState, viewModel: AiAssistantViewModel) {
    ModelList(uiState, viewModel.toActions(regionId = ""))
}

@Composable
private fun ModelList(uiState: AiUiState, actions: AiActions) {
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
                        ModelRow(definition = definition, isSelected = definition.id == uiState.selectedModelId, uiState = uiState, actions = actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(definition: LlmModelDefinition, isSelected: Boolean, uiState: AiUiState, actions: AiActions) {
    val context = LocalContext.current
    var showLargeDownloadWarning by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val size = Formatter.formatShortFileSize(context, definition.sizeBytes)
    val available = definition.sha256 != null

    Column {
        ListItem(
            supportingContent = {
                Text(
                    when {
                        !available -> "$size · ${stringResource(R.string.ai_model_coming_soon)}"
                        isSelected -> "$size · ${stringResource(R.string.ai_model_selected)}"
                        else -> size
                    },
                )
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
        // nulla da scaricare finche' non viene pubblicato un hash reale.
        if (isSelected && available) {
            Column(modifier = Modifier.padding(start = 56.dp, end = Spacing.l, bottom = Spacing.l)) {
                uiState.errorMessage?.let {
                    Text(text = stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = Spacing.s))
                }
                val downloadProgress = uiState.downloadProgress
                if (uiState.isModelDownloaded) {
                    // Gia' installato (la lista si vede anche dalle Impostazioni, non solo prima del download).
                    Text(stringResource(R.string.ai_model_downloaded), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.ai_delete_model))
                    }
                } else if (downloadProgress != null) {
                    Text(
                        stringResource(R.string.ai_model_downloading, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DownloadProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs))
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
                        Text(stringResource(R.string.ai_model_download))
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

@Composable
private fun ApiKeySetup(uiState: AiUiState, actions: AiActions) {
    var isKeyVisible by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val provider = uiState.onlineProvider
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.ai_api_key_intro), style = MaterialTheme.typography.bodyLarge)
        // Servizio: uno dei quattro, ognuno col suo modello (OnlineProvider).
        Text(
            stringResource(R.string.ai_provider_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Column(modifier = Modifier.selectableGroup()) {
            OnlineProvider.entries.forEach { candidate ->
                ListItem(
                    leadingContent = { RadioButton(selected = candidate == provider, onClick = null) },
                    supportingContent = {
                        Text(stringResource(R.string.ai_provider_model, uiState.onlineModels[candidate] ?: candidate.autoModel))
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(selected = candidate == provider, role = Role.RadioButton) {
                        actions.onProviderSelected(candidate)
                    },
                    content = { Text(candidate.displayName) },
                )
            }
        }
        OnlineModelChoice(provider = provider, setting = uiState.onlineModelSetting, onSettingChanged = actions.onOnlineModelChanged)
        TextButton(onClick = { uriHandler.openUri(provider.keyPageUrl) }) {
            Text(stringResource(R.string.ai_provider_get_key, provider.displayName))
            Spacer(modifier = Modifier.width(Spacing.s))
            Icon(AppIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            stringResource(R.string.ai_api_key_privacy, provider.displayName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = uiState.apiKeyInput,
            onValueChange = actions.onApiKeyInputChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_api_key_label)) },
            placeholder = provider.keyPrefix?.let { prefix -> { Text(stringResource(R.string.ai_api_key_placeholder, prefix)) } },
            visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isKeyVisible = !isKeyVisible }) {
                    Icon(
                        if (isKeyVisible) AppIcons.VisibilityOff else AppIcons.Visibility,
                        contentDescription = stringResource(
                            if (isKeyVisible) R.string.ai_api_key_hide else R.string.ai_api_key_show,
                        ),
                    )
                }
            },
            singleLine = true,
        )
        Button(onClick = actions.onSaveApiKey, enabled = uiState.apiKeyInput.isNotBlank()) {
            Text(stringResource(R.string.ai_api_key_save))
        }
    }
}

// Modello del servizio: Automatico (consigliato, vedi OnlineProvider.autoModel), il piu' capace, o un
// altro scritto a mano. Ognuno con una riga che spiega la differenza.
@Composable
private fun OnlineModelChoice(provider: OnlineProvider, setting: String, onSettingChanged: (String) -> Unit) {
    // "Altro" resta selezionato mentre si scrive, anche col campo ancora vuoto.
    var otherSelected by rememberSaveable(provider) { mutableStateOf(setting.isNotBlank() && setting != provider.capableModel) }
    val choice = when {
        otherSelected -> 2
        setting == provider.capableModel -> 1
        else -> 0
    }
    Text(
        stringResource(R.string.ai_online_model_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
    Column(modifier = Modifier.selectableGroup()) {
        ModelChoiceRow(
            selected = choice == 0,
            title = stringResource(R.string.ai_online_model_auto),
            description = stringResource(R.string.ai_online_model_auto_desc, provider.autoModel),
        ) { otherSelected = false; onSettingChanged("") }
        ModelChoiceRow(
            selected = choice == 1,
            title = stringResource(R.string.ai_online_model_capable, provider.capableModel),
            description = stringResource(R.string.ai_online_model_capable_desc),
        ) { otherSelected = false; onSettingChanged(provider.capableModel) }
        ModelChoiceRow(
            selected = choice == 2,
            title = stringResource(R.string.ai_online_model_other),
            description = stringResource(R.string.ai_online_model_other_desc),
        ) { otherSelected = true; if (setting == provider.capableModel) onSettingChanged("") }
    }
    if (choice == 2) {
        OutlinedTextField(
            value = setting,
            onValueChange = onSettingChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_online_model_label)) },
            singleLine = true,
        )
    }
}

@Composable
private fun ModelChoiceRow(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = { Text(description) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        content = { Text(title) },
    )
}

@Composable
private fun BenchmarkComparisonDialog(uiState: AiUiState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_close)) } },
        title = { Text(stringResource(R.string.ai_benchmark_dialog_title)) },
        text = {
            Column {
                // Solo i modelli con un risultato salvato (LlmModelCatalog.ALL puo' includere
                // modelli mai scaricati su questo device, es. per una fascia RAM diversa) — un
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
                                stringResource(R.string.ai_benchmark_result, result.wordsPerSecond.toInt(), result.qualityScore),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
    )
}

private val previewActions = AiActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun AiConversationPreview() {
    PocketTravelTheme(dynamicColor = false) {
        Surface {
            AiAssistantContent(
                uiState = AiUiState(
                    mode = AiEngineMode.ON_DEVICE,
                    isDeviceCapable = true,
                    isModelDownloaded = true,
                    isApiKeyConfigured = false,
                    askedQuestion = "Serve il visto per San Marino?",
                    answer = AssistantAnswer(
                        text = "No, per i cittadini italiani non serve alcun visto.",
                        sourceCitations = listOf("Fonte: Wikivoyage — San Marino"),
                        showOfficialSourceBanner = true,
                        officialSourceUrl = "https://www.viaggiaresicuri.it",
                    ),
                ),
                actions = previewActions,
                onOpenOfficialSource = {},
            )
        }
    }
}
