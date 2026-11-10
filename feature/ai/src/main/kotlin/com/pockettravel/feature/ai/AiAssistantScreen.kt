package com.pockettravel.feature.ai

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.GuideSourceTarget
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isDebugBuild
import java.io.File
import java.io.InputStream
import javax.crypto.Cipher

// Il ViewModel sopravvive al cambio di lingua dell'app, ma il modello in uso dipende dalla lingua: con la stessa
// istanza la schermata crederebbe ancora scaricato il modello dell'altra lingua e ogni domanda finirebbe in errore.
internal fun aiViewModelKey(): String = "ai-${currentGuidesLanguage()}"

@Composable
fun AiAssistantScreen(
    regionId: String,
    // Regione di una nazione divisa: il campo dice "regione" invece di "nazione".
    splitCountry: Boolean = false,
    viewModel: AiAssistantViewModel = hiltViewModel(key = aiViewModelKey()),
    voiceViewModel: VoiceInputViewModel = hiltViewModel(),
    readAloudViewModel: ReadAloudViewModel = hiltViewModel(),
    onOpenOfficialSource: (url: String) -> Unit = {},
    onNavigationRequest: (NavigationRequest) -> Unit = {},
    // Tocco su una fonte che risale a una sezione della guida della regione.
    onOpenGuideSource: (GuideSourceTarget) -> Unit = {},
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
        onOpenGuideSource = onOpenGuideSource,
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
    val onAccountIdInputChanged: (String) -> Unit,
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
    val onSharesPersonalDataChanged: (Boolean) -> Unit = {},
    val onVoiceStart: () -> Unit = {},
    val onVoiceStop: () -> Unit = {},
    val onVoicePermissionDenied: () -> Unit = {},
    // Ascolta e Ferma sotto una risposta, con il suo testo.
    val onReadAloud: (String) -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onRefreshForecast: () -> Unit = {},
    // Un pulsante di un messaggio dell'app (quota finita, /model): sceglie il modello.
    val onModelChoice: (ModelChoice) -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onDeleteHistory: () -> Unit = {},
    val onCancelDownload: () -> Unit = {},
    val onImportModel: (displayName: String?, sizeBytes: Long, open: () -> InputStream?) -> Unit = { _, _, _ -> },
    val sendableModelFile: (LlmModelDefinition) -> File? = { null },
    // Fonte di una sezione della guida sotto una risposta (con il testo della risposta): la sezione, se c'è, e la sua apertura.
    val resolveGuideSources: suspend (citations: List<String>, answer: String) -> Map<String, GuideSourceTarget> = { _, _ -> emptyMap() },
    val onOpenGuideSource: (GuideSourceTarget) -> Unit = {},
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

internal fun AiAssistantViewModel.toActions(regionId: String) = AiActions(
    onModeChanged = ::onModeChanged,
    onModelSelected = ::onModelSelected,
    onDownloadModel = ::downloadModel,
    onCancelDownload = ::cancelDownload,
    onApiKeyInputChanged = ::onApiKeyInputChanged,
    onAccountIdInputChanged = ::onAccountIdInputChanged,
    onSaveApiKey = ::saveApiKey,
    onProviderSelected = ::onProviderSelected,
    onOnlineModelChanged = ::onOnlineModelChanged,
    onSharesPersonalDataChanged = ::onSharesPersonalDataChanged,
    onQuestionChanged = ::onQuestionChanged,
    onAsk = { ask(regionId) },
    onDeleteModel = ::deleteModel,
    onClearApiKey = ::clearApiKey,
    onRunBenchmark = ::runBenchmark,
    onShowBenchmarkComparison = ::showBenchmarkComparison,
    onHideBenchmarkComparison = ::hideBenchmarkComparison,
    onNewConversation = ::newConversation,
    onRefreshForecast = { refreshForecast(regionId) },
    onModelChoice = { onModelChoice(it, regionId) },
    onOpenHistory = ::openHistory,
    onDeleteHistory = ::deleteHistory,
    onImportModel = ::importModel,
    sendableModelFile = ::sendableModelFile,
    resolveGuideSources = { citations, answer -> resolveGuideSources(regionId, citations, answer) },
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

private val previewActions = AiActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

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
internal fun isDebugBuild(): Boolean {
    val context = LocalContext.current
    return remember(context) { isDebugBuild(context) }
}
