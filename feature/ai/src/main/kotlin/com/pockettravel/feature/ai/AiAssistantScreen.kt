package com.pockettravel.feature.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.isOnCellularNetwork

@Composable
fun AiAssistantScreen(
    regionId: String,
    viewModel: AiAssistantViewModel = hiltViewModel(),
    onOpenOfficialSource: (url: String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.AiAssistant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Assistente di viaggio", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Sotto i 4 GB di RAM l'assistente e' solo Online (vedi AiAssistantViewModel: mode e'
        // gia' forzato a ONLINE li'): niente selettore da mostrare, un solo modo esiste.
        if (uiState.isDeviceCapable) {
            ModeSelector(mode = uiState.mode, onModeChanged = viewModel::onModeChanged)
            Spacer(modifier = Modifier.height(12.dp))
        }

        when (uiState.mode) {
            AiEngineMode.ON_DEVICE -> if (!uiState.isModelDownloaded) {
                ModelListCard(uiState, viewModel)
            } else {
                AssistantConversation(regionId, uiState, viewModel, onOpenOfficialSource)
            }
            AiEngineMode.ONLINE -> if (!uiState.isApiKeyConfigured) {
                ApiKeyCard(uiState, viewModel)
            } else {
                AssistantConversation(regionId, uiState, viewModel, onOpenOfficialSource)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: AiEngineMode, onModeChanged: (AiEngineMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        AiEngineMode.entries.forEachIndexed { index, candidate ->
            val label = if (candidate == AiEngineMode.ON_DEVICE) "Sul dispositivo" else "Online"
            SegmentedButton(
                selected = candidate == mode,
                onClick = { onModeChanged(candidate) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AiEngineMode.entries.size),
            ) {
                Text(label)
            }
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Scegli un modello IA da scaricare, in base alla RAM del tuo dispositivo:", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        uiState.availableModels.forEach { definition ->
            ModelRow(definition = definition, isSelected = definition.id == uiState.selectedModelId, uiState = uiState, viewModel = viewModel)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ModelRow(definition: LlmModelDefinition, isSelected: Boolean, uiState: AiUiState, viewModel: AiAssistantViewModel) {
    val context = LocalContext.current
    var showLargeDownloadWarning by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isSelected) { viewModel.onModelSelected(definition.id) },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(definition.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${definition.sizeBytes / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    definition.sha256 == null ->
                        Text("Presto disponibile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    isSelected ->
                        Text("Selezionato", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            // sha256 == null: modello non ancora pubblicato (in attesa di fine-tuning/upload
            // proprio, vedi LlmModelCatalog) — nessun pulsante di download da mostrare, non c'e'
            // nulla da scaricare finche' non viene pubblicato un hash reale.
            if (isSelected && definition.sha256 != null) {
                uiState.errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(12.dp))
                val downloadProgress = uiState.downloadProgress
                if (downloadProgress != null) {
                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                } else {
                    Button(
                        onClick = {
                            if (definition.sizeBytes > LARGE_DOWNLOAD_WARNING_BYTES && isOnCellularNetwork(context)) {
                                showLargeDownloadWarning = true
                            } else {
                                viewModel.downloadModel()
                            }
                        },
                    ) {
                        Text("Scarica modello")
                    }
                }
            }
        }
    }

    if (showLargeDownloadWarning) {
        ConfirmationDialog(
            title = "Download di grandi dimensioni",
            message = "${definition.displayName} pesa circa ${definition.sizeBytes / (1024 * 1024)} MB. Continuare?",
            confirmLabel = "Scarica",
            onConfirm = { showLargeDownloadWarning = false; viewModel.downloadModel() },
            onDismiss = { showLargeDownloadWarning = false },
        )
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
private fun AssistantConversation(
    regionId: String,
    uiState: AiUiState,
    viewModel: AiAssistantViewModel,
    onOpenOfficialSource: (url: String) -> Unit,
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val isOnDevice = uiState.mode == AiEngineMode.ON_DEVICE

    Column {
        if (isOnDevice) {
            BenchmarkRow(uiState, viewModel)
            Spacer(modifier = Modifier.height(8.dp))
        }
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
                                Modifier.clickable { onOpenOfficialSource(url) }
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

@Composable
private fun BenchmarkRow(uiState: AiUiState, viewModel: AiAssistantViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { viewModel.runBenchmark() }, enabled = !uiState.isBenchmarking) {
                Text(if (uiState.isBenchmarking) "Benchmark in corso…" else "Esegui benchmark")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.showBenchmarkComparison() }) {
                Text("Confronta modelli")
            }
        }
        val result = uiState.benchmarkResult
        if (result != null && result.modelId == uiState.selectedModelId) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${result.wordsPerSecond.toInt()} parole/s · qualità ${result.qualityScore}/100" +
                    (if (result.loadTimeMs > 0) " · caricamento ${result.loadTimeMs / 1000f}s" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (uiState.showBenchmarkComparison) {
        BenchmarkComparisonDialog(uiState, viewModel)
    }
}

@Composable
private fun BenchmarkComparisonDialog(uiState: AiUiState, viewModel: AiAssistantViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.hideBenchmarkComparison() },
        confirmButton = {
            TextButton(onClick = { viewModel.hideBenchmarkComparison() }) { Text("Chiudi") }
        },
        title = { Text("Modelli provati") },
        text = {
            Column {
                // Solo i modelli con un risultato salvato (LlmModelCatalog.ALL puo' includere
                // modelli mai scaricati su questo device, es. per una fascia RAM diversa) — un
                // modello non ancora provato non aggiunge informazione utile al confronto.
                val results = uiState.allBenchmarkResults.sortedByDescending { it.qualityScore }
                if (results.isEmpty()) {
                    Text("Nessun modello ancora provato.")
                } else {
                    results.forEach { result ->
                        val displayName = LlmModelCatalog.ALL.firstOrNull { it.id == result.modelId }?.displayName ?: result.modelId
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${result.wordsPerSecond.toInt()} parole/s · qualità ${result.qualityScore}/100",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
    )
}
