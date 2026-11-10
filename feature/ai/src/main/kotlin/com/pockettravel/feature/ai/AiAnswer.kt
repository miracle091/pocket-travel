package com.pockettravel.feature.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.GuideSourceTarget
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.WeatherCard

// Il contenuto della bolla della risposta: testo in arrivo, attesa, errore o risposta con fonti.
// Fonti in una riga sola, "Fonti (2)" con il numero di quelle citate, che si apre a richiesta: con la sezione e
// l'indirizzo di ogni pagina, l'elenco completo occuperebbe spesso piu' spazio della risposta.
@Composable
private fun SourceCitations(
    citations: List<String>,
    answerText: String,
    resolveGuideSources: suspend (citations: List<String>, answer: String) -> Map<String, GuideSourceTarget>,
    onOpenGuideSource: (GuideSourceTarget) -> Unit,
) {
    var expanded by rememberSaveable(citations) { mutableStateOf(false) }
    // Le fonti che risalgono a una sezione della guida installata si toccano; si cercano solo quando l'elenco è aperto.
    val guideTargets by produceState(emptyMap<String, GuideSourceTarget>(), citations, answerText, expanded) {
        value = if (expanded) resolveGuideSources(citations, answerText) else emptyMap()
    }
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
                val target = guideTargets[citation]
                if (target == null) {
                    Text(
                        citation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                } else {
                    Text(
                        citation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .padding(top = Spacing.xs)
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.ai_source_open_guide)) { onOpenGuideSource(target) },
                    )
                }
            }
        }
    }
}

// Freccia delle fonti girata quando sono aperte.
private const val HALF_TURN_DEGREES = 180f
internal const val PERCENT_SCALE = 100

@Composable
internal fun AnswerContent(
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
            // Come la risposta finita: le risposte online arrivano in markdown, che non deve vedersi durante lo streaming.
            Text(remember(uiState.streamingText) { markdownToDisplayText(uiState.streamingText) }, style = MaterialTheme.typography.bodyLarge)
            AiGeneratedLabel(uiState.answerCredit, onOpenOfficialSource)
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
            val quota = uiState.quota?.takeIf { error == R.string.ai_error_daily_quota }
            Text(
                // Il credito esaurito nomina il servizio da ricaricare, la quota il modello e l'ora in cui torna.
                when {
                    quota != null -> quotaText(quota, uiState.onlineProvider)
                    error == R.string.ai_error_credit -> stringResource(error, uiState.onlineProvider.displayName)
                    else -> stringResource(error)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            quota?.let { ModelChoiceButtons(it.choices, actions.onModelChoice) }
            // Chiave scaduta o revocata: si toglie da qui e se ne inserisce una nuova.
            if (error == R.string.ai_error_key_rejected) {
                TextButton(onClick = actions.onClearApiKey) { Text(stringResource(R.string.ai_remove_key)) }
            }
        }
        answer != null -> Column {
            FinishedAnswer(answer, uiState.answerCredit, onOpenOfficialSource, actions)
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
internal fun ReadAloudButton(text: String, state: ReadAloudUiState, onReadAloud: (String) -> Unit) {
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

// AI Act art. 50(1) e (2): ogni risposta, anche mentre arriva, dice che l'ha scritta un modello IA, e quale: il nome
// apre la pagina del modello.
@Composable
private fun AiGeneratedLabel(credit: ModelCredit?, onOpenOfficialSource: (url: String) -> Unit) {
    val label = stringResource(R.string.ai_answer_generated)
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(label, credit, linkColor) {
        buildAnnotatedString {
            append(label)
            if (credit != null) {
                append(": ")
                val link = LinkAnnotation.Clickable(
                    tag = credit.url,
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ) { onOpenOfficialSource(credit.url) }
                withLink(link) { append(credit.name) }
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

// Risposta completa: testo, riquadro meteo delle previsioni usate, fonti e, sui temi regolati, il link alla fonte ufficiale.
@Composable
internal fun FinishedAnswer(answer: AssistantAnswer, credit: ModelCredit?, onOpenOfficialSource: (url: String) -> Unit, actions: AiActions) {
    Text(remember(answer.text) { markdownToDisplayText(answer.text) }, style = MaterialTheme.typography.bodyLarge)
    AiGeneratedLabel(credit, onOpenOfficialSource)
    // I numeri delle previsioni si leggono nel riquadro, non nel testo del modello.
    answer.forecast?.let { forecast ->
        WeatherCard(forecast, onOpenSource = { url, _ -> onOpenOfficialSource(url) }, modifier = Modifier.padding(top = Spacing.m))
    }
    if (answer.sourceCitations.isNotEmpty()) SourceCitations(answer.sourceCitations, answer.text, actions.resolveGuideSources, actions.onOpenGuideSource)
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
