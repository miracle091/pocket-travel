package com.pockettravel.feature.ai

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun Conversation(
    uiState: AiUiState,
    actions: AiActions,
    readAloud: ReadAloudUiState,
    onOpenOfficialSource: (url: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val asked = uiState.askedQuestion
    if (uiState.isEmptyConversation()) {
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
    // Una domanda nuova, una conversazione ripresa o un messaggio dell'app: la vista va in fondo, all'ultimo turno (dopo
    // il layout dei turni).
    LaunchedEffect(asked, uiState.earlierTurns.size, uiState.notice) {
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
                    FinishedAnswer(turn.answer, turn.credit, onOpenOfficialSource, actions)
                    ReadAloudButton(markdownToDisplayText(turn.answer.text).text, readAloud, actions.onReadAloud)
                }
            }
        }
        if (asked != null) {
            AskedPhoto(uiState.lastQuestionPhoto)
            QuestionBubble(asked)
        }
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
                (uiState.notice as? ChatNotice.ModelSwitched)?.let { ModelSwitchedLine(it) }
                AnswerContent(uiState, streaming, onOpenOfficialSource, actions, readAloud)
            }
        }
        uiState.notice?.let { ModelNoticeBubble(it, uiState, actions.onModelChoice) }
    }
}

// Niente da mostrare: ne' domanda, ne' attesa, ne' errore, ne' messaggio dell'app.
internal fun AiUiState.isEmptyConversation(): Boolean =
    askedQuestion == null && !isThinking && errorMessage == null && notice == null

// Riga sopra la risposta, in Automatico: un altro modello risponde al posto di quello con la quota finita.
@Composable
private fun ModelSwitchedLine(notice: ChatNotice.ModelSwitched) {
    Text(
        stringResource(R.string.ai_notice_model_switched, notice.from, quotaResetTime(notice.resetAtMillis), notice.to),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.s),
    )
}

// Risposta dell'app a /model e alle sue scelte, sotto la conversazione: non e' una risposta del modello.
@Composable
private fun ModelNoticeBubble(notice: ChatNotice, uiState: AiUiState, onChoice: (ModelChoice) -> Unit) {
    val (text, choices) = when (notice) {
        is ChatNotice.ModelList -> stringResource(R.string.ai_notice_model_list, choiceName(notice.current, uiState)) to notice.choices
        is ChatNotice.ModelChosen -> stringResource(R.string.ai_notice_model_chosen, choiceName(notice.choice, uiState)) to emptyList()
        is ChatNotice.UnknownModel -> stringResource(R.string.ai_notice_model_unknown, notice.name) to notice.choices
        // Nella bolla della risposta: la quota con l'errore, il cambio sopra la risposta.
        is ChatNotice.QuotaExhausted, is ChatNotice.ModelSwitched -> return
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // Come la bolla delle risposte, con il testo attenuato: parla l'app, non il modello. I pulsanti tonali risaltano.
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModelChoiceButtons(choices, onChoice)
        }
    }
}

// Un pulsante per scelta, a capo quando non stanno in una riga.
@Composable
internal fun ModelChoiceButtons(choices: List<ModelChoice>, onChoice: (ModelChoice) -> Unit) {
    if (choices.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.s)) {
        choices.forEach { choice ->
            FilledTonalButton(onClick = { onChoice(choice) }) {
                Text(if (choice is ModelChoice.OnDevice) stringResource(R.string.ai_choice_use_on_device) else stringResource(R.string.ai_choice_use, choiceName(choice, null)))
            }
        }
    }
}

// Automatico con il modello che usa, quando lo si conosce ([uiState]); il modello sul dispositivo con il motore.
@Composable
internal fun choiceName(choice: ModelChoice, uiState: AiUiState?): String = when (choice) {
    ModelChoice.Automatic -> uiState?.onlineModels?.get(uiState.onlineProvider)
        ?.let { stringResource(R.string.ai_choice_automatic_with, it) } ?: stringResource(R.string.ai_choice_automatic)
    is ModelChoice.Online -> choice.id
    is ModelChoice.OnDevice -> stringResource(R.string.ai_choice_on_device, choice.name)
}

// L'ora locale in cui torna la quota giornaliera.
@Composable
private fun quotaResetTime(resetAtMillis: Long): String = remember(resetAtMillis) {
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(resetAtMillis).atZone(ZoneId.systemDefault()))
}

// La quota finita dice quando torna; un modello fuori dal piano gratuito non torna, e il messaggio nomina il servizio.
@Composable
internal fun quotaText(quota: ChatNotice.QuotaExhausted, provider: OnlineProvider): String = if (quota.notInFreePlan) {
    stringResource(R.string.ai_error_not_in_free_plan, quota.model, provider.displayName)
} else {
    stringResource(R.string.ai_error_daily_quota, quota.model, quotaResetTime(quota.resetAtMillis))
}

// Al posto del campo della domanda, per una conversazione ripresa la cui regione non e' piu' installata.
@Composable
internal fun ReadOnlyNotice(regionName: String, onNewConversation: () -> Unit) {
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
