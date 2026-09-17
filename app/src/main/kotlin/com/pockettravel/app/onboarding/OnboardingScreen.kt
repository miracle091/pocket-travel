package com.pockettravel.app.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.app.regions.RegionRow
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.feature.ai.AiAssistantViewModel
import com.pockettravel.feature.ai.ModelListCard

private sealed interface OnboardingStep {
    data class Info(val title: String, val body: String, val icon: ImageVector) : OnboardingStep
    data object RegionDownload : OnboardingStep
    data object AiModelDownload : OnboardingStep
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val vaultIcon = AppIcons.passport()
    val isOnDeviceAiSupported = viewModel.isOnDeviceAiSupported
    val steps = remember(vaultIcon, isOnDeviceAiSupported) {
        buildList {
            add(
                OnboardingStep.Info(
                    title = "Benvenuto in Pocket Travel",
                    body = "Una guida di viaggio che funziona anche senza connessione: usi e costumi, " +
                        "dogane, vaccinazioni, mappe e un assistente IA a bordo. Nessun account, " +
                        "nessuna raccolta di dati personali.",
                    icon = AppIcons.Compass,
                ),
            )
            add(OnboardingStep.RegionDownload)
            add(
                if (isOnDeviceAiSupported) {
                    OnboardingStep.Info(
                        title = "L'assistente IA ha due modalità",
                        body = "\"Sul dispositivo\" funziona anche offline, ma richiede almeno 4 GB di RAM e il " +
                            "download di un modello. \"Online\" usa una tua chiave API personale, mai condivisa " +
                            "con un server dell'app.",
                        icon = AppIcons.AiAssistant,
                    )
                } else {
                    OnboardingStep.Info(
                        title = "L'assistente IA è disponibile Online",
                        body = "Il tuo dispositivo ha meno di 4 GB di RAM: la modalità \"Sul dispositivo\" non è " +
                            "disponibile. L'assistente funziona comunque in modalità Online, con una tua chiave " +
                            "API personale, mai condivisa con un server dell'app.",
                        icon = AppIcons.AiAssistant,
                    )
                },
            )
            if (isOnDeviceAiSupported) add(OnboardingStep.AiModelDownload)
            add(
                OnboardingStep.Info(
                    title = "I documenti restano al sicuro",
                    body = "Passaporto e certificati di vaccinazione si trovano in \"Documenti\", protetti " +
                        "da biometria o blocco schermo e mai inviati fuori dal dispositivo.",
                    icon = vaultIcon,
                ),
            )
            add(
                OnboardingStep.Info(
                    title = "Le fonti ufficiali restano esterne",
                    body = "Ambasciate, ministeri e OMS si aprono in una scheda del browser e richiedono una " +
                        "connessione: l'app non li salva mai offline, per restare sempre aggiornati.",
                    icon = AppIcons.OfficialAuthority,
                ),
            )
        }
    }

    var stepIndex by remember { mutableStateOf(0) }
    val step = steps[stepIndex]
    val isLastStep = stepIndex == steps.lastIndex

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            when (val currentStep = step) {
                is OnboardingStep.Info -> InfoStepContent(currentStep)
                OnboardingStep.RegionDownload -> RegionDownloadStepContent()
                OnboardingStep.AiModelDownload -> AiModelDownloadStepContent()
            }
        }

        StepDots(count = steps.size, current = stepIndex)
        Spacer(modifier = Modifier.height(16.dp))

        // "Salta"/"Avanti" restano gli unici controlli di navigazione, anche per i due step
        // interattivi: scaricare una regione o il modello IA qui e' facoltativo, "Avanti" non
        // resta mai bloccato in attesa di un download.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.complete(); onComplete() }) {
                Text("Salta")
            }
            Spacer(modifier = Modifier.weight(1f))
            if (stepIndex > 0) {
                TextButton(onClick = { stepIndex -= 1 }) {
                    Text("Indietro")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Button(
                onClick = {
                    if (isLastStep) {
                        viewModel.complete()
                        onComplete()
                    } else {
                        stepIndex += 1
                    }
                },
            ) {
                Text(if (isLastStep) "Inizia" else "Avanti")
            }
        }
    }
}

@Composable
private fun InfoStepContent(step: OnboardingStep.Info) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = step.title, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = step.body, style = MaterialTheme.typography.bodyLarge)
    }
}

// Riusa RegionListViewModel/RegionRow del modulo :regions (stesso modulo :app, package diverso):
// stessa logica di download/eliminazione della schermata "Regioni" vera, non una copia.
@Composable
private fun RegionDownloadStepContent(viewModel: RegionListViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = "Scarica una regione per iniziare", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Facoltativo: guida, mappa e punti di interesse sono organizzati in pacchetti regionali. " +
                "Puoi scaricarne una qui o farlo in qualsiasi momento da Spazio di archiviazione.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            uiState.isLoading && uiState.items.isEmpty() -> CircularProgressIndicator()
            uiState.errorMessage != null -> Text(text = uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(uiState.items, key = { it.regionId }) { item ->
                    // onClick e' no-op qui: l'onboarding non naviga al dettaglio di una regione,
                    // la riga serve solo per vedere lo stato e scaricare/eliminare.
                    RegionRow(item = item, viewModel = viewModel, onClick = {})
                }
            }
        }
    }
}

// Riusa AiAssistantViewModel/ModelListCard di :feature:ai: stessa logica di selezione/download/
// token gated della schermata "Assistente IA" vera. Mostrato solo quando isOnDeviceAiSupported
// (vedi OnboardingScreen), quindi il catalogo qui non e' mai vuoto.
@Composable
private fun AiModelDownloadStepContent(viewModel: AiAssistantViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(text = "Scarica il modello IA", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Facoltativo: puoi scaricarlo qui o in qualsiasi momento dopo, dalla schermata Assistente IA.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        ModelListCard(uiState, viewModel)
    }
}

@Composable
private fun StepDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            val color = if (index == current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
            Box(modifier = Modifier.size(8.dp).background(color = color, shape = CircleShape))
        }
    }
}
