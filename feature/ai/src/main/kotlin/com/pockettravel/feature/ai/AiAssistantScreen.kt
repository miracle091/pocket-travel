package com.pockettravel.feature.ai

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import android.text.format.Formatter
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.sync.ONLINE_MODEL_ROLE_AUTO
import com.pockettravel.core.sync.OnlineModelEntry
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.WeatherCard
import com.pockettravel.core.ui.isOnCellularNetwork
import com.pockettravel.core.ui.R as UiR
import java.io.File
import java.io.InputStream
import java.text.NumberFormat
import java.util.Currency
import javax.crypto.Cipher

@Composable
fun AiAssistantScreen(
    regionId: String,
    // Regione di una nazione divisa: il campo dice "regione" invece di "nazione".
    splitCountry: Boolean = false,
    viewModel: AiAssistantViewModel = hiltViewModel(),
    voiceViewModel: VoiceInputViewModel = hiltViewModel(),
    readAloudViewModel: ReadAloudViewModel = hiltViewModel(),
    onOpenOfficialSource: (url: String) -> Unit = {},
    onNavigationRequest: (NavigationRequest) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val voiceState by voiceViewModel.uiState.collectAsStateWithLifecycle()
    val readAloudState by readAloudViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.navigationRequests.collect(onNavigationRequest) }
    LaunchedEffect(voiceViewModel) { voiceViewModel.text.collect(viewModel::onQuestionChanged) }
    // Il microfono e la voce si fermano quando la schermata non si vede piu' (altra scheda, app in secondo piano).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceViewModel, readAloudViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                voiceViewModel.stop()
                readAloudViewModel.stop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voiceViewModel.stop()
            readAloudViewModel.stop()
        }
    }
    // La cronologia sbloccata si richiude come la cassaforte: app in secondo piano o schermata lasciata, non a una
    // rotazione. Il prompt biometrico non ferma l'activity.
    val activity = LocalActivity.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) viewModel.closeHistory()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) viewModel.closeHistory()
        }
    }
    SecureWindow(enabled = uiState.showsSavedContent)
    val actions = viewModel.toActions(regionId).copy(
        onQuestionChanged = { text ->
            voiceViewModel.clearMessage()
            viewModel.onQuestionChanged(text)
        },
        onAsk = {
            voiceViewModel.stop()
            readAloudViewModel.stop()
            viewModel.ask(regionId)
        },
        onVoiceStart = {
            readAloudViewModel.stop()
            voiceViewModel.start(uiState.question)
        },
        onVoiceStop = voiceViewModel::stop,
        onVoicePermissionDenied = voiceViewModel::onPermissionDenied,
        onReadAloud = readAloudViewModel::toggle,
    )
    AiAssistantContent(
        uiState = uiState,
        actions = actions,
        onOpenOfficialSource = onOpenOfficialSource,
        splitCountry = splitCountry,
        voice = voiceState,
        readAloud = readAloudState,
    )
    uiState.history?.let { history ->
        ConversationHistoryDialog(
            history = history,
            deleteFailed = uiState.historyDeleteFailed,
            actions = HistoryActions(
                onUnlocked = viewModel::onHistoryUnlocked,
                onRetry = viewModel::openHistory,
                onClose = viewModel::closeHistory,
                onResume = viewModel::resumeConversation,
                onDelete = viewModel::deleteConversation,
                onDeleteAll = viewModel::deleteHistory,
            ),
        )
    }
}

/**
 * FLAG_SECURE sulla finestra finche' [enabled] e la funzione e' in composizione: niente screenshot e niente contenuto nella
 * miniatura delle app recenti. Si toglie solo se non c'era gia' prima.
 */
@Composable
internal fun SecureWindow(enabled: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        val alreadySecure = window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (enabled && !alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
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
    val onVoiceStart: () -> Unit = {},
    val onVoiceStop: () -> Unit = {},
    val onVoicePermissionDenied: () -> Unit = {},
    // Ascolta e Ferma sotto una risposta, con il suo testo.
    val onReadAloud: (String) -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onRefreshForecast: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onDeleteHistory: () -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onImportModel: (displayName: String?, sizeBytes: Long, open: () -> InputStream?) -> Unit = { _, _, _ -> },
    val sendableModelFile: (LlmModelDefinition) -> File? = { null },
)

/** Le azioni della cronologia, dal ViewModel. */
internal class HistoryActions(
    val onUnlocked: (Cipher) -> Unit,
    val onRetry: () -> Unit,
    val onClose: () -> Unit,
    val onResume: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onDeleteAll: () -> Unit,
)

private fun AiAssistantViewModel.toActions(regionId: String) = AiActions(
    onModeChanged = ::onModeChanged,
    onModelSelected = ::onModelSelected,
    onDownloadModel = ::downloadModel,
    onCancelDownload = ::cancelDownload,
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
    onNewConversation = ::newConversation,
    onRefreshForecast = { refreshForecast(regionId) },
    onOpenHistory = ::openHistory,
    onDeleteHistory = ::deleteHistory,
    onImportModel = ::importModel,
    sendableModelFile = ::sendableModelFile,
)

@Composable
internal fun AiAssistantContent(
    uiState: AiUiState,
    actions: AiActions,
    onOpenOfficialSource: (url: String) -> Unit,
    splitCountry: Boolean = false,
    voice: VoiceInputUiState = VoiceInputUiState(isAvailable = false),
    readAloud: ReadAloudUiState = ReadAloudUiState(),
) {
    val isReady = when (uiState.mode) {
        AiEngineMode.ON_DEVICE -> uiState.isModelDownloaded
        AiEngineMode.ONLINE -> uiState.isApiKeyConfigured
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AssistantHeader(uiState = uiState, isReady = isReady, actions = actions)
        if (isReady) {
            Conversation(uiState, actions, readAloud, onOpenOfficialSource = onOpenOfficialSource, modifier = Modifier.weight(1f))
            val readOnlyRegion = uiState.readOnlyRegion
            if (readOnlyRegion != null) {
                ReadOnlyNotice(readOnlyRegion, onNewConversation = actions.onNewConversation)
            } else {
                QuestionBar(uiState = uiState, actions = actions, splitCountry = splitCountry, voice = voice)
            }
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
        val base = stringResource(R.string.ai_benchmark_result, result.wordsPerSecond.toInt(), result.qualityScore)
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

// La scheda IA c'e' perche' una chiave e' salvata, ma il motore scelto e' "Sul dispositivo" senza modello:
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
 * Configurazione dell'assistente per le Impostazioni: motore (solo quelli che il dispositivo regge), modelli
 * sul dispositivo con download/eliminazione, oppure servizio, chiave e modello online. Il ViewModel e' lo stesso
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
            }
        }
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

@Composable
private fun Conversation(
    uiState: AiUiState,
    actions: AiActions,
    readAloud: ReadAloudUiState,
    onOpenOfficialSource: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val scrollState = rememberScrollState()
    val streaming = uiState.isThinking && uiState.streamingText.isNotEmpty()
    // Mentre il testo cresce la vista ne segue la fine; a risposta completa l'utente scorre da solo.
    LaunchedEffect(uiState.streamingText) {
        if (streaming) scrollState.scrollTo(scrollState.maxValue)
    }
    // Una domanda nuova o una conversazione ripresa: la vista va in fondo, all'ultimo turno (dopo il layout dei turni).
    LaunchedEffect(asked, uiState.earlierTurns.size) {
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        uiState.earlierTurns.forEach { turn ->
            QuestionBubble(turn.askedQuestion)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Spacing.l)) {
                    FinishedAnswer(turn.answer, onOpenOfficialSource)
                    ReadAloudButton(markdownToDisplayText(turn.answer.text).text, readAloud, actions.onReadAloud)
                }
            }
        }
        if (asked != null) QuestionBubble(asked)
        uiState.emergencyCard?.let { EmergencyCard(it, onOpenOfficialSource) }
        // Risposta: bolla a sinistra su superficie tonale; liveRegion perche' TalkBack annunci la
        // risposta (o l'errore) quando arriva, senza dover cercare dove e' comparsa. Non durante lo
        // streaming: ogni aggiornamento del testo parziale verrebbe riletto, la risposta si annuncia una
        // volta sola quando e' completa. Niente bolla vuota sotto il riquadro di emergenza, quando il modello non risponde.
        if (uiState.isThinking || uiState.errorMessage != null || uiState.answer != null) Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().semantics { if (!streaming) liveRegion = LiveRegionMode.Polite },
        ) {
            // animateContentSize: la bolla cresce con morbidezza da "Sto pensando…" alla risposta.
            Column(modifier = Modifier.animateContentSize().padding(Spacing.l)) {
                AnswerContent(uiState, streaming, onOpenOfficialSource, actions, readAloud)
            }
        }
    }
}

// Al posto del campo della domanda, per una conversazione ripresa la cui regione non e' piu' installata.
@Composable
private fun ReadOnlyNotice(regionName: String, onNewConversation: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(Spacing.l),
    ) {
        Row(modifier = Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(
                stringResource(R.string.ai_history_read_only, regionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onNewConversation) { Text(stringResource(R.string.ai_new_conversation)) }
        }
    }
}

// Domanda dell'utente: bolla a destra, nel colore del primary container.
@Composable
private fun QuestionBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(Spacing.m))
        }
    }
}

// Il contenuto della bolla della risposta: testo in arrivo, attesa, errore o risposta con fonti.
// Fonti in una riga sola, "Fonti (2)" con il numero di quelle citate, che si apre a richiesta: con la sezione e
// l'indirizzo di ogni pagina, l'elenco completo occuperebbe spesso piu' spazio della risposta.
@Composable
private fun SourceCitations(citations: List<String>) {
    var expanded by rememberSaveable(citations) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = Spacing.xs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.ai_sources_hide else R.string.ai_sources_show)) {
                    expanded = !expanded
                }
                .padding(vertical = Spacing.xs),
        ) {
            Text(
                pluralStringResource(R.plurals.ai_sources_count, citations.size, citations.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                AppIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).rotate(if (expanded) HALF_TURN_DEGREES else 0f),
            )
        }
        if (expanded) {
            citations.forEach { citation ->
                Text(
                    citation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

// Freccia delle fonti girata quando sono aperte.
private const val HALF_TURN_DEGREES = 180f
private const val PERCENT_SCALE = 100

@Composable
private fun AnswerContent(
    uiState: AiUiState,
    streaming: Boolean,
    onOpenOfficialSource: (url: String) -> Unit,
    actions: AiActions,
    readAloud: ReadAloudUiState,
) {
    val answer = uiState.answer
    val error = uiState.errorMessage
    when {
        streaming -> Column {
            Text(uiState.streamingText, style = MaterialTheme.typography.bodyLarge)
            AiGeneratedLabel()
        }
        // Alla prima domanda il modello si carica in memoria (fino a qualche decina di secondi per il 4B): una barra
        // dice che l'app sta lavorando e che la risposta arriva dopo.
        uiState.isThinking && uiState.isModelLoading -> Column {
            Text(stringResource(R.string.ai_loading_model), style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(Spacing.m))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
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
                TextButton(onClick = actions.onClearApiKey) { Text(stringResource(R.string.ai_remove_key)) }
            }
        }
        answer != null -> Column {
            FinishedAnswer(answer, onOpenOfficialSource)
            ReadAloudButton(markdownToDisplayText(answer.text).text, readAloud, actions.onReadAloud)
            // Solo la risposta in fondo, con le previsioni nel contesto e piu' vecchie del limite per luogo.
            if (uiState.canRefreshForecast && answer.forecast != null) {
                TextButton(onClick = actions.onRefreshForecast, modifier = Modifier.padding(top = Spacing.s)) {
                    Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(stringResource(R.string.ai_refresh_forecast))
                }
            }
        }
    }
}

// Ascolta sotto ogni risposta, Ferma su quella in lettura; il motivo sotto il pulsante se la lettura non e' riuscita.
@Composable
private fun ReadAloudButton(text: String, state: ReadAloudUiState, onReadAloud: (String) -> Unit) {
    val speaking = state.speakingText == text
    TextButton(onClick = { onReadAloud(text) }, modifier = Modifier.padding(top = Spacing.s)) {
        Icon(if (speaking) AppIcons.Stop else AppIcons.Listen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(Spacing.s))
        Text(stringResource(if (speaking) R.string.ai_read_aloud_stop else R.string.ai_read_aloud_listen))
    }
    val message = state.message
    if (state.failedText == text && message != null) {
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// AI Act art. 50(1) e (2): ogni risposta, anche mentre arriva, dice che l'ha scritta un modello IA.
@Composable
private fun AiGeneratedLabel() {
    Text(
        stringResource(R.string.ai_answer_generated),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

// Risposta completa: testo, riquadro meteo delle previsioni usate, fonti e, sui temi regolati, il link alla fonte ufficiale.
@Composable
private fun FinishedAnswer(answer: AssistantAnswer, onOpenOfficialSource: (url: String) -> Unit) {
    Text(remember(answer.text) { markdownToDisplayText(answer.text) }, style = MaterialTheme.typography.bodyLarge)
    AiGeneratedLabel()
    // I numeri delle previsioni si leggono nel riquadro, non nel testo del modello.
    answer.forecast?.let { forecast ->
        WeatherCard(forecast, onOpenSource = { url, _ -> onOpenOfficialSource(url) }, modifier = Modifier.padding(top = Spacing.m))
    }
    if (answer.sourceCitations.isNotEmpty()) SourceCitations(answer.sourceCitations)
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

@Composable
private fun QuestionBar(uiState: AiUiState, actions: AiActions, splitCountry: Boolean, voice: VoiceInputUiState) {
    val canSend = uiState.question.isNotBlank() && !uiState.isThinking
    val context = LocalContext.current
    // Il permesso del microfono si chiede al primo tocco, non all'apertura della schermata.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) actions.onVoiceStart() else actions.onVoicePermissionDenied()
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = uiState.question,
            onValueChange = actions.onQuestionChanged,
            modifier = Modifier.weight(1f),
            placeholder = {
                val hint = when {
                    voice.isListening -> R.string.ai_voice_listening
                    splitCountry -> R.string.ai_question_hint
                    else -> R.string.ai_question_hint_country
                }
                Text(stringResource(hint))
            },
            trailingIcon = if (voice.isAvailable) {
                {
                    if (voice.isListening) {
                        IconButton(onClick = actions.onVoiceStop) {
                            Icon(AppIcons.Stop, contentDescription = stringResource(R.string.ai_voice_stop), tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        IconButton(onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (granted) actions.onVoiceStart() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }) {
                            Icon(AppIcons.Mic, contentDescription = stringResource(R.string.ai_voice_start))
                        }
                    }
                }
            } else {
                null
            },
            supportingText = voice.message?.let { message -> { Text(stringResource(message)) } }
                ?: uiState.question.length.takeIf { it >= QUESTION_COUNTER_FROM }?.let { length -> { Text("$length/$MAX_QUESTION_CHARS") } },
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
    val size = Formatter.formatShortFileSize(context, definition.sizeBytes)
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
                    // Modello della fascia di RAM sopra quella del dispositivo (LlmModelCatalog.needsMoreRam).
                    if (definition.id in uiState.modelsNeedingMoreRam) {
                        Text(
                            stringResource(R.string.ai_model_needs_more_ram, definition.minRamTier.gigabytes),
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
                if (uiState.isModelDownloaded) {
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

@Composable
private fun ApiKeySetup(uiState: AiUiState, actions: AiActions) {
    var isKeyVisible by rememberSaveable { mutableStateOf(false) }
    // Con la chiave in chiaro niente screenshot, condivisione dello schermo ne' miniatura delle app recenti.
    SecureWindow(enabled = isKeyVisible)
    val uriHandler = LocalUriHandler.current
    val provider = uiState.onlineProvider
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Text(stringResource(R.string.ai_api_key_intro), style = MaterialTheme.typography.bodyLarge)
        // Servizio: uno dei quattro (OnlineProvider), con il modello in uso (OnlineModelCatalog).
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
                        uiState.onlineModels[candidate]?.let { Text(stringResource(R.string.ai_provider_model, it)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(selected = candidate == provider, role = Role.RadioButton) {
                        actions.onProviderSelected(candidate)
                    },
                    content = { Text(candidate.displayName) },
                )
            }
        }
        OnlineModelChoice(
            provider = provider,
            options = uiState.onlineModelOptions,
            setting = uiState.onlineModelSetting,
            onSettingChanged = actions.onOnlineModelChanged,
        )
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
            // Password e senza correzione: la tastiera non deve memorizzare ne' suggerire la chiave.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
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

// Modello del servizio: Automatico (consigliato), gli altri dell'elenco (OnlineModelCatalog) o uno scritto a
// mano. Ognuno con una riga che spiega la differenza e, per quelli dell'elenco, il costo stimato.
@Composable
private fun OnlineModelChoice(
    provider: OnlineProvider,
    options: List<OnlineModelEntry>,
    setting: String,
    onSettingChanged: (String) -> Unit,
) {
    val listed = options.filter { it.role != ONLINE_MODEL_ROLE_AUTO }
    // "Altro" resta selezionato mentre si scrive, anche col campo ancora vuoto; vale anche per una scelta salvata
    // che non e' (o non e' piu') tra i modelli mostrati, tolta dall'elenco o nascosta dopo la lettura di /models.
    var otherSelected by rememberSaveable(provider) { mutableStateOf(false) }
    val showOther = otherSelected || (setting.isNotBlank() && listed.none { it.id == setting.trim() })
    Text(
        stringResource(R.string.ai_online_model_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
    Column(modifier = Modifier.selectableGroup()) {
        options.firstOrNull { it.role == ONLINE_MODEL_ROLE_AUTO }?.let { auto ->
            ModelChoiceRow(
                selected = !showOther && setting.isBlank(),
                title = stringResource(R.string.ai_online_model_auto),
                description = stringResource(R.string.ai_online_model_auto_desc, auto.id),
                cost = costText(auto),
            ) { otherSelected = false; onSettingChanged("") }
        }
        listed.forEach { model ->
            ModelChoiceRow(
                selected = !showOther && setting.trim() == model.id,
                title = stringResource(R.string.ai_online_model_capable, model.id),
                description = stringResource(R.string.ai_online_model_capable_desc),
                cost = costText(model),
            ) { otherSelected = false; onSettingChanged(model.id) }
        }
        ModelChoiceRow(
            selected = showOther,
            title = stringResource(R.string.ai_online_model_other),
            description = stringResource(R.string.ai_online_model_other_desc),
        ) { otherSelected = true; if (listed.any { it.id == setting }) onSettingChanged("") }
    }
    if (showOther) {
        OutlinedTextField(
            value = setting,
            onValueChange = onSettingChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_online_model_label)) },
            singleLine = true,
        )
    }
}

// Costo stimato di 100 domande in dollari, nel formato della lingua dell'app.
@Composable
private fun costText(model: OnlineModelEntry): String {
    val usd = model.usdPer100Questions()
    val format = NumberFormat.getCurrencyInstance(LocalConfiguration.current.locales[0]).apply {
        currency = Currency.getInstance("USD")
        maximumFractionDigits = 2
    }
    return if (usd < MIN_SHOWN_COST) {
        stringResource(R.string.ai_online_model_cost_below, format.format(MIN_SHOWN_COST))
    } else {
        stringResource(R.string.ai_online_model_cost, format.format(usd))
    }
}

private const val MIN_SHOWN_COST = 0.01

@Composable
private fun ModelChoiceRow(selected: Boolean, title: String, description: String, cost: String? = null, onClick: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = {
            Column {
                Text(description)
                if (cost != null) Text(cost, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
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

// Build di debug (debuggable): il benchmark dei modelli si mostra solo li', non nelle versioni stabili.
@Composable
private fun isDebugBuild(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
}
