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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.CustomTabsLauncher
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES

@Composable
fun AiAssistantScreen(regionId: String, viewModel: AiAssistantViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "Assistente di viaggio", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        ModeSelector(mode = uiState.mode, onModeChanged = viewModel::onModeChanged)
        Spacer(modifier = Modifier.height(12.dp))

        when (uiState.mode) {
            AiEngineMode.ON_DEVICE -> when {
                !uiState.isDeviceCapable -> Text(
                    "Il tuo dispositivo ha meno di 4 GB di RAM: l'assistente sul dispositivo non è disponibile. Puoi usare la modalità Online.",
                )
                !uiState.isModelDownloaded -> ModelDownloadCard(uiState, viewModel)
                else -> AssistantConversation(regionId, uiState, viewModel)
            }
            AiEngineMode.ONLINE -> if (!uiState.isApiKeyConfigured) {
                ApiKeyCard(uiState, viewModel)
            } else {
                AssistantConversation(regionId, uiState, viewModel)
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: AiEngineMode, onModeChanged: (AiEngineMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        AiEngineMode.entries.forEach { candidate ->
            val label = if (candidate == AiEngineMode.ON_DEVICE) "Sul dispositivo" else "Online"
            if (candidate == mode) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(label) }
            } else {
                OutlinedButton(onClick = { onModeChanged(candidate) }, modifier = Modifier.weight(1f)) { Text(label) }
            }
            if (candidate != AiEngineMode.entries.last()) Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun ModelDownloadCard(uiState: AiUiState, viewModel: AiAssistantViewModel) {
    var showLargeDownloadWarning by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Modello IA non ancora scaricato.")
            if (!uiState.hasHfToken) {
                HfTokenForm(uiState, viewModel)
            } else {
                uiState.errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(12.dp))
                val downloadProgress = uiState.downloadProgress
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row {
                        Button(
                            onClick = {
                                if (AiModelConfig.MODEL_SIZE_BYTES > LARGE_DOWNLOAD_WARNING_BYTES) {
                                    showLargeDownloadWarning = true
                                } else {
                                    viewModel.downloadModel()
                                }
                            },
                        ) {
                            Text("Scarica modello")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.clearHfToken() }) {
                            Text("Cambia token")
                        }
                    }
                }
            }
        }
    }

    if (showLargeDownloadWarning) {
        ConfirmationDialog(
            title = "Download di grandi dimensioni",
            message = "Il modello IA pesa circa ${AiModelConfig.MODEL_SIZE_BYTES / (1024 * 1024)} MB. Continuare?",
            confirmLabel = "Scarica",
            onConfirm = { showLargeDownloadWarning = false; viewModel.downloadModel() },
            onDismiss = { showLargeDownloadWarning = false },
        )
    }
}

@Composable
private fun HfTokenForm(uiState: AiUiState, viewModel: AiAssistantViewModel) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Il modello è ospitato su HuggingFace con licenza Gemma: accettala su huggingface.co e incolla qui il tuo token di accesso personale.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = uiState.hfTokenInput,
        onValueChange = viewModel::onHfTokenInputChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Token HuggingFace") },
        visualTransformation = PasswordVisualTransformation(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = { viewModel.saveHfToken() }, enabled = uiState.hfTokenInput.isNotBlank()) {
        Text("Salva token")
    }
}

@Composable
private fun ApiKeyCard(uiState: AiUiState, viewModel: AiAssistantViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Inserisci la tua chiave API personale per usare l'assistente online.")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "La chiave resta solo su questo dispositivo, cifrata in Android Keystore: le domande vengono inviate direttamente al servizio scelto, mai a un server dell'app.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.apiKeyInput,
                onValueChange = viewModel::onApiKeyInputChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Chiave API") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { viewModel.saveApiKey() }, enabled = uiState.apiKeyInput.isNotBlank()) {
                Text("Salva chiave")
            }
        }
    }
}

@Composable
private fun AssistantConversation(regionId: String, uiState: AiUiState, viewModel: AiAssistantViewModel) {
    val context = LocalContext.current
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val isOnDevice = uiState.mode == AiEngineMode.ON_DEVICE

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = uiState.question,
                onValueChange = viewModel::onQuestionChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Fai una domanda sulla regione") },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(onClick = { viewModel.ask(regionId) }, enabled = !uiState.isThinking) {
                Text(if (uiState.isThinking) "Sto pensando…" else "Chiedi")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { showRemoveConfirm = true }) {
                Text(if (isOnDevice) "Elimina modello" else "Rimuovi chiave")
            }
        }

        if (showRemoveConfirm) {
            ConfirmationDialog(
                title = if (isOnDevice) "Eliminare il modello IA?" else "Rimuovere la chiave API?",
                message = if (isOnDevice) {
                    "Dovrai riscaricarlo per tornare a usare l'assistente sul dispositivo."
                } else {
                    "Dovrai reinserirla per tornare a usare l'assistente online."
                },
                onConfirm = {
                    showRemoveConfirm = false
                    if (isOnDevice) viewModel.deleteModel() else viewModel.clearApiKey()
                },
                onDismiss = { showRemoveConfirm = false },
            )
        }

        uiState.errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        uiState.answer?.let { answer ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(answer.text)
                    answer.sourceCitations.forEach { citation ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(citation, style = MaterialTheme.typography.bodySmall)
                    }
                    if (answer.showOfficialSourceBanner) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = answer.officialSourceUrl?.let { url ->
                                Modifier.clickable { CustomTabsLauncher.open(context, url) }
                            } ?: Modifier,
                        ) {
                            Icon(
                                imageVector = AppIcons.VerifiedLink,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Verifica sempre sulla fonte ufficiale.",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
