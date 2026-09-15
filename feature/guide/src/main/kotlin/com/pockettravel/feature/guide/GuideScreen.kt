package com.pockettravel.feature.guide

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.CustomTabsLauncher
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.ui.AppIcons

@Composable
fun GuideScreen(regionId: String, viewModel: GuideViewModel = hiltViewModel()) {
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        uiState.isLoading -> Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }

        uiState.errorMessage != null -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(text = uiState.errorMessage!!, color = MaterialTheme.colorScheme.error)
        }

        uiState.sections.isEmpty() -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Nessun contenuto guida disponibile per questa regione.")
        }

        else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(uiState.sections, key = { "${it.category}_${it.title}" }) { section ->
                GuideSectionCard(section)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun GuideSectionCard(section: GuideSection) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = section.category.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = section.category.displayName(), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = section.title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = section.body, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Fonte: Wikivoyage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { CustomTabsLauncher.open(context, section.sourceUrl) },
            )
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
    GuideCategory.DOGANE -> AppIcons.customs()
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
