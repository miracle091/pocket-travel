package com.pockettravel.app.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
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
import com.pockettravel.core.ui.AppIcons

private data class OnboardingStep(val title: String, val body: String, val icon: ImageVector)

private val steps = listOf(
    OnboardingStep(
        title = "Benvenuto in Pocket Travel",
        body = "Una guida di viaggio che funziona anche senza connessione: usi e costumi, " +
            "dogane, vaccinazioni, mappe e un assistente IA a bordo. Nessun account, " +
            "nessuna raccolta di dati personali.",
        icon = AppIcons.Compass,
    ),
    OnboardingStep(
        title = "Scarica una regione per iniziare",
        body = "Guida, mappa e punti di interesse sono organizzati in pacchetti regionali da " +
            "scaricare quando vuoi. Potrai gestirli in qualsiasi momento da Spazio di archiviazione.",
        icon = AppIcons.Download,
    ),
    OnboardingStep(
        title = "L'assistente IA ha due modalità",
        body = "\"Sul dispositivo\" funziona anche offline, ma richiede almeno 4 GB di RAM e il " +
            "download di un modello. \"Online\" usa una tua chiave API personale, mai condivisa " +
            "con un server dell'app.",
        icon = AppIcons.AiAssistant,
    ),
    OnboardingStep(
        title = "Le fonti ufficiali restano esterne",
        body = "Ambasciate, ministeri e OMS si aprono in una scheda del browser e richiedono una " +
            "connessione: l'app non li salva mai offline, per restare sempre aggiornati.",
        icon = AppIcons.OfficialAuthority,
    ),
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    var stepIndex by remember { mutableStateOf(0) }
    val step = steps[stepIndex]
    val isLastStep = stepIndex == steps.lastIndex

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Icon(
                imageVector = step.icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = step.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = step.body, style = MaterialTheme.typography.bodyLarge)
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.complete(); onComplete() }) {
                Text("Salta")
            }
            Spacer(modifier = Modifier.weight(1f))
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
