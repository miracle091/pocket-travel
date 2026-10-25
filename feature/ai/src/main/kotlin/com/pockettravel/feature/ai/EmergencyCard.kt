package com.pockettravel.feature.ai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Nome di un numero nel riquadro di emergenza. */
enum class EmergencyLabel { GENERAL, SINGLE, POLICE, AMBULANCE, FIRE }

/** Un numero da chiamare; [labels] sono i servizi che lo condividono (es. Giappone: ambulanza e vigili del fuoco). */
data class EmergencyCallEntry(val labels: List<EmergencyLabel>, val number: String)

/** Il riquadro di emergenza di una domanda; [entries] vuoto = la regione non ha un numero di emergenza centralizzato. */
data class EmergencyCardState(val entries: List<EmergencyCallEntry>)

/**
 * Numeri del riquadro di emergenza. Con un numero generale uguale a tutti gli altri (112 in Andorra, 911 negli Stati
 * Uniti) una riga sola; altrimenti raggruppati per numero, cosi' un numero condiviso non compare due volte.
 */
internal fun EmergencyNumbers.callEntries(): List<EmergencyCallEntry> {
    val generalNumber = general
    if (generalNumber != null && listOf(police, ambulance, fire).all { it == generalNumber }) {
        return listOf(EmergencyCallEntry(listOf(EmergencyLabel.SINGLE), generalNumber))
    }
    val labeled = buildList {
        general?.let { add(EmergencyLabel.GENERAL to it) }
        add(EmergencyLabel.POLICE to police)
        add(EmergencyLabel.AMBULANCE to ambulance)
        add(EmergencyLabel.FIRE to fire)
    }
    return labeled.groupBy({ it.second }, { it.first }).map { (number, labels) -> EmergencyCallEntry(labels, number) }
}

/**
 * Decide se una domanda fa comparire il riquadro di emergenza e con quali numeri: prima della ricerca e del modello,
 * quindi indipendente dal motore e dal modello scaricato.
 */
class EmergencyCardProvider @Inject constructor(
    private val emergencyNumbersRepository: EmergencyNumbersRepository,
) {
    /** Il riquadro per [question] nella regione [regionId]; null se la domanda non parla di un pericolo per la vita. */
    suspend fun forQuestion(regionId: String, question: String): EmergencyCardState? {
        if (!isLifeThreatening(question)) return null
        val numbers = try {
            emergencyNumbersRepository.forRegion(regionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        return EmergencyCardState(numbers?.callEntries().orEmpty())
    }
}

@Composable
internal fun EmergencyCard(state: EmergencyCardState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                text = stringResource(R.string.ai_emergency_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (state.entries.isEmpty()) {
                Text(stringResource(R.string.ai_emergency_none), style = MaterialTheme.typography.bodyMedium)
            }
            state.entries.forEach { entry ->
                val label = entry.labels.map { stringResource(it.labelRes()) }.joinToString(" / ")
                Button(
                    onClick = {
                        // Senza app telefono (tablet solo Wi-Fi) l'intent non ha destinatari: niente crash.
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", entry.number, null))) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(stringResource(R.string.ai_emergency_call, label, entry.number))
                }
            }
        }
    }
}

private fun EmergencyLabel.labelRes(): Int = when (this) {
    EmergencyLabel.GENERAL -> R.string.ai_emergency_general
    EmergencyLabel.SINGLE -> R.string.ai_emergency_single
    EmergencyLabel.POLICE -> R.string.ai_emergency_police
    EmergencyLabel.AMBULANCE -> R.string.ai_emergency_ambulance
    EmergencyLabel.FIRE -> R.string.ai_emergency_fire
}
