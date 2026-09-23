package com.pockettravel.feature.guide

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.EmptyState

@Composable
fun GuideScreen(
    regionId: String,
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    viewModel: GuideViewModel = hiltViewModel(),
) {
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        uiState.isLoading -> Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }

        uiState.errorMessage != null -> EmptyState(
            icon = AppIcons.Info,
            title = uiState.errorMessage!!,
            modifier = Modifier.fillMaxSize(),
        )

        uiState.sections.isEmpty() && uiState.emergencyNumbers == null -> EmptyState(
            icon = AppIcons.Compass,
            title = "Nessun contenuto guida disponibile",
            subtitle = "Non ci sono ancora sezioni guida pubblicate per questa regione.",
            modifier = Modifier.fillMaxSize(),
        )

        else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            uiState.emergencyNumbers?.let { numbers ->
                item(key = "emergency_numbers") {
                    EmergencyNumbersCard(numbers)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            items(uiState.sections, key = { "${it.category}_${it.title}" }) { section ->
                GuideSectionCard(section, onOpenSource)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun EmergencyNumbersCard(numbers: EmergencyNumbers) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.Emergency,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Numeri di emergenza",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            numbers.entries().forEach { (label, number) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = number,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Icon(
                        imageVector = AppIcons.Call,
                        contentDescription = "Chiama $label",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

// Se il numero generale coincide con tutti gli altri (es. 911 negli Stati Uniti, 112 in
// Andorra) mostra una sola riga invece di quattro identiche; altrimenti raggruppa per numero
// (es. Giappone: ambulanza e vigili del fuoco condividono il 119) cosi' un numero condiviso
// non compare due volte.
private fun EmergencyNumbers.entries(): List<Pair<String, String>> {
    val generalNumber = general
    if (generalNumber != null && generalNumber == police && generalNumber == ambulance && generalNumber == fire) {
        return listOf("Emergenza" to generalNumber)
    }
    val labeled = buildList {
        general?.let { add("Generale" to it) }
        add("Polizia" to police)
        add("Ambulanza" to ambulance)
        add("Vigili del fuoco" to fire)
    }
    return labeled.groupBy({ it.second }, { it.first }).map { (number, labels) -> labels.joinToString(" / ") to number }
}

@Composable
private fun GuideSectionCard(section: GuideSection, onOpenSource: (url: String, title: String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = section.category.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(6.dp).size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = section.category.displayName(), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = section.title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            GuideBody(section.body)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Fonte: Wikivoyage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpenSource(section.sourceUrl, "Wikivoyage") },
            )
        }
    }
}

// Le sottosezioni arrivano nel body come righe "▸ Titolo" (===Titolo=== di Wikivoyage, vedi
// cleanBody in GenerateGuideContent) oppure, nei pacchetti generati prima del fix, come ";Titolo"
// (lista di definizione wiki): entrambe mostrate come titolo, senza il simbolo davanti.
private val subheadingLineRegex = Regex("""^(?:▸|;)\s*(.+)$""")

internal data class GuideBodyBlock(val text: String, val isSubheading: Boolean)

internal fun guideBodyBlocks(body: String): List<GuideBodyBlock> {
    val blocks = mutableListOf<GuideBodyBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        val text = paragraph.joinToString("\n").trim('\n')
        if (text.isNotBlank()) blocks += GuideBodyBlock(text, isSubheading = false)
        paragraph.clear()
    }
    body.lineSequence().forEach { line ->
        val subheading = subheadingLineRegex.find(line.trim())?.groupValues?.get(1)
        if (subheading != null) {
            flushParagraph()
            blocks += GuideBodyBlock(subheading, isSubheading = true)
        } else {
            paragraph += line
        }
    }
    flushParagraph()
    return blocks
}

@Composable
private fun GuideBody(body: String) {
    guideBodyBlocks(body).forEach { block ->
        if (block.isSubheading) {
            Text(
                text = block.text,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
            )
        } else {
            Text(text = block.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun GuideCategory.displayName(): String = when (this) {
    GuideCategory.USI_COSTUMI -> "Usi e costumi"
    GuideCategory.DOGANE -> "Dogane"
    GuideCategory.SALUTE -> "Salute"
    GuideCategory.SICUREZZA -> "Sicurezza"
    GuideCategory.TRASPORTI -> "Trasporti"
    GuideCategory.FRASI_UTILI -> "Frasi utili"
    GuideCategory.ALLOGGIO -> "Alloggio"
    GuideCategory.CIBO_BEVANDE -> "Cibo e bevande"
    GuideCategory.ACQUISTI -> "Acquisti"
    GuideCategory.CONNETTIVITA -> "Connettività"
    GuideCategory.VITA_QUOTIDIANA -> "Vita quotidiana"
}

// Nessuna icona del set copre esattamente "sicurezza"/"trasporti": usate le piu' vicine per
// significato (autorita' ufficiale per gli avvisi di sicurezza, voli come forma di trasporto).
@Composable
private fun GuideCategory.icon(): ImageVector = when (this) {
    GuideCategory.USI_COSTUMI -> AppIcons.Checklist
    GuideCategory.DOGANE -> AppIcons.Customs
    GuideCategory.SALUTE -> AppIcons.HealthGuidance
    GuideCategory.SICUREZZA -> AppIcons.OfficialAuthority
    GuideCategory.TRASPORTI -> AppIcons.Flights
    GuideCategory.FRASI_UTILI -> AppIcons.Translation
    GuideCategory.ALLOGGIO -> AppIcons.Accommodation
    GuideCategory.CIBO_BEVANDE -> AppIcons.FoodDrink
    GuideCategory.ACQUISTI -> AppIcons.Shopping
    GuideCategory.CONNETTIVITA -> AppIcons.Connectivity
    GuideCategory.VITA_QUOTIDIANA -> AppIcons.DailyLife
}
