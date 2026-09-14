package com.pockettravel.app.licenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Legenda a colpo d'occhio per le due categorie di rischio licenza usate in thirdPartyLicenses.
@Composable
private fun RiskLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        LicenseRisk.entries.forEach { risk ->
            LegendChip(color = risk.color, label = risk.label)
        }
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Surface(color = color.copy(alpha = 0.12f), contentColor = color, shape = RoundedCornerShape(50)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun RiskSectionHeader(risk: LicenseRisk) {
    Surface(
        color = risk.color.copy(alpha = 0.12f),
        contentColor = risk.color,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = risk.label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LicenseCard(entry: LicenseEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = entry.risk.color.copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = entry.component, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Licenza: ${entry.license}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            entry.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// Mostra thirdPartyLicenses (LicenseData.kt): solo licenze di terze parti realmente in uso,
// compilate nell'app invece che lette da un asset a runtime.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Licenze") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(innerPadding).padding(16.dp)) {
            RiskLegend()
            Spacer(modifier = Modifier.height(12.dp))
            LicenseRisk.entries.forEach { risk ->
                val entries = thirdPartyLicenses.filter { it.risk == risk }
                if (entries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    RiskSectionHeader(risk)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entries.forEach { entry -> LicenseCard(entry) }
                    }
                }
            }
        }
    }
}
