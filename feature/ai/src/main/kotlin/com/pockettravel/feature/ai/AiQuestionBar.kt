package com.pockettravel.feature.ai

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing

@Composable
internal fun QuestionBar(uiState: AiUiState, actions: AiActions, splitCountry: Boolean, voice: VoiceInputUiState) {
    val canSend = (uiState.question.isNotBlank() || uiState.hasPhotoToSend) && !uiState.isThinking
    val send = rememberSend(uiState, actions)
    val context = LocalContext.current
    // Il permesso del microfono si chiede al primo tocco, non all'apertura della schermata.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) actions.onVoiceStart() else actions.onVoicePermissionDenied()
    }
    // Il testo resta quello dello stato, come nel campo con una String; il cursore e' qui, per metterlo in fondo dopo
    // un tocco nel menu dei comandi.
    var field by remember { mutableStateOf(TextFieldValue(uiState.question)) }
    CommandMenu(uiState, actions) { text ->
        field = TextFieldValue(text, TextRange(text.length))
        actions.onQuestionChanged(text)
    }
    AttachedPhotoPreview(uiState, actions.onPhotoRemoved, modifier = Modifier.padding(start = Spacing.l, top = Spacing.s))
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = field.copy(text = uiState.question),
            onValueChange = {
                field = it
                actions.onQuestionChanged(it.text)
            },
            modifier = Modifier.weight(1f),
            leadingIcon = photoAttachIcon(uiState, actions),
            placeholder = { Text(stringResource(questionHint(uiState, splitCountry, voice))) },
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
            keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
        )
        Spacer(modifier = Modifier.width(Spacing.s))
        FilledIconButton(onClick = send, enabled = canSend, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.Send, contentDescription = stringResource(R.string.ai_send))
        }
    }
}

// Una foto senza testo parte con una domanda fissa, la stessa che il campo vuoto mostra (questionHint).
@Composable
private fun rememberSend(uiState: AiUiState, actions: AiActions): () -> Unit {
    val photoQuestion = stringResource(R.string.ai_photo_question)
    val photoOnly = uiState.question.isBlank() && uiState.hasPhotoToSend
    return {
        if (photoOnly) actions.onQuestionChanged(photoQuestion)
        actions.onAsk()
    }
}

private fun questionHint(uiState: AiUiState, splitCountry: Boolean, voice: VoiceInputUiState): Int = when {
    voice.isListening -> R.string.ai_voice_listening
    uiState.hasPhotoToSend -> R.string.ai_photo_question
    splitCountry -> R.string.ai_question_hint
    else -> R.string.ai_question_hint_country
}

// Sopra il campo, quando la domanda comincia con "/": i comandi e, dopo "/model", i modelli da scegliere con un tocco.
@Composable
private fun CommandMenu(uiState: AiUiState, actions: AiActions, onFill: (String) -> Unit) {
    val suggestions = remember(uiState) { uiState.commandSuggestions() }
    if (suggestions.isEmpty()) return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
            suggestions.forEach { suggestion ->
                val (title, subtitle) = when (suggestion) {
                    is CommandSuggestion.Command -> suggestion.command to stringResource(R.string.ai_command_model_desc)
                    is CommandSuggestion.Choice -> choiceName(suggestion.choice, uiState) to null
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            when (suggestion) {
                                is CommandSuggestion.Command -> onFill("${suggestion.command} ")
                                is CommandSuggestion.Choice -> {
                                    actions.onQuestionChanged("")
                                    actions.onModelChoice(suggestion.choice)
                                }
                            }
                        }
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                ) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}
