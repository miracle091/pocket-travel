package com.pockettravel.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.Spacing

/**
 * Foglio "Chiedi all'assistente": il testo selezionato in un'altra app ([rawText]) sopra il campo della domanda, con le tre
 * domande pronte. Lo stesso ViewModel, campo e risposta della scheda IA; niente cronologia (AiUiState.selectedText).
 * [regionId] e' l'ultima regione aperta, null se non ce n'e'.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedTextSheet(
    rawText: CharSequence?,
    regionId: String?,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOfficialSource: (url: String) -> Unit = {},
    viewModel: AiAssistantViewModel = hiltViewModel(key = aiViewModelKey()),
    readAloudViewModel: ReadAloudViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val readAloudState by readAloudViewModel.uiState.collectAsStateWithLifecycle()
    // Alla ricreazione dell'activity (rotazione) il testo, magari modificato, e' gia' nel ViewModel.
    LaunchedEffect(viewModel) {
        if (viewModel.uiState.value.selectedText == null) prepareSelectedText(rawText)?.let(viewModel::onSelectedTextChanged) ?: onDismiss()
    }
    // Come nella scheda IA, la lettura si ferma uscendo: il foglio non sta nei Recenti e non ci si tornerebbe per fermarla.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, readAloudViewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) readAloudViewModel.stop() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            readAloudViewModel.stop()
        }
    }
    val selected = uiState.selectedText ?: return
    // Senza regione l'assistente risponde dal modello, senza il contesto di una guida.
    val region = regionId.orEmpty()
    val actions = viewModel.toActions(region).copy(
        onAsk = {
            readAloudViewModel.stop()
            viewModel.ask(region)
        },
        onReadAloud = readAloudViewModel::toggle,
        // Il foglio non apre la guida: le fonti restano testo.
        resolveGuideSources = { _, _ -> emptyMap() },
    )
    val isReady = when (uiState.mode) {
        AiEngineMode.ON_DEVICE -> uiState.isModelDownloaded
        AiEngineMode.ONLINE -> uiState.isApiKeyConfigured
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            Text(
                stringResource(R.string.ai_selected_text_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.xl).semantics { heading() },
            )
            if (isReady) {
                ReadySheetContent(uiState, selected, actions, { viewModel.onSelectedTextChanged(editedSelectedText(it)) }, onOpenOfficialSource, readAloudState)
            } else {
                Text(
                    stringResource(R.string.ai_selected_text_no_engine),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Spacing.xl),
                )
                Button(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
                    Text(stringResource(R.string.ai_selected_text_open_settings))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ReadySheetContent(
    uiState: AiUiState,
    selected: SelectedText,
    actions: AiActions,
    onSelectedTextChanged: (String) -> Unit,
    onOpenOfficialSource: (url: String) -> Unit,
    readAloud: ReadAloudUiState,
) {
    OutlinedTextField(
        value = selected.text,
        onValueChange = onSelectedTextChanged,
        label = { Text(stringResource(R.string.ai_selected_text_label)) },
        supportingText = if (selected.truncated) {
            { Text(stringResource(R.string.ai_selected_text_truncated, MAX_SELECTED_TEXT_CHARS)) }
        } else {
            null
        },
        maxLines = 5,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s),
    )
    if (uiState.mode == AiEngineMode.ONLINE) {
        Text(
            stringResource(R.string.ai_selected_text_online_notice, uiState.onlineProvider.displayName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
    ) {
        // L'etichetta corta sul pulsante, la richiesta completa al modello: con "Traduci" da sola lo 0.8B non capiva cosa tradurre.
        listOf(
            R.string.ai_selected_text_meaning to R.string.ai_selected_text_meaning_question,
            R.string.ai_selected_text_translate to R.string.ai_selected_text_translate_question,
            R.string.ai_selected_text_explain to R.string.ai_selected_text_explain_question,
        ).forEach { (label, request) ->
            val question = stringResource(request)
            AssistChip(
                onClick = {
                    actions.onQuestionChanged(question)
                    actions.onAsk()
                },
                enabled = !uiState.isThinking,
                label = { Text(stringResource(label)) },
            )
        }
    }
    if (!uiState.isEmptyConversation()) {
        Conversation(uiState, actions, readAloud, onOpenOfficialSource, modifier = Modifier.weight(1f, fill = false))
    }
    QuestionBar(uiState = uiState, actions = actions, splitCountry = false, voice = VoiceInputUiState(isAvailable = false))
}
