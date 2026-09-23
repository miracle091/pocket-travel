package com.pockettravel.app.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val usedBytes = uiState.regionsSizeBytes + uiState.modelSizeBytes
    val usedMb = usedBytes.toMb()
    val availableMb = uiState.availableBytes.toMb()
    val totalBytes = usedBytes + uiState.availableBytes
    val usedFraction = if (totalBytes > 0) usedBytes / totalBytes.toFloat() else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spazio di archiviazione") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(16.dp)) {
            Text(
                text = "Occupati da Pocket Travel: $usedMb MB · Liberi sul dispositivo: $availableMb MB",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { usedFraction }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Regioni installate", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (uiState.installedRegions.isEmpty()) {
                Text(text = "Nessuna regione installata.", style = MaterialTheme.typography.bodySmall)
            } else {
                uiState.installedRegions.forEach { region ->
                    StorageRow(
                        title = region.displayName,
                        sizeBytes = region.sizeBytes,
                        onDelete = { viewModel.deleteRegion(region.regionId) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Modello IA locale", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (uiState.isModelDownloaded) {
                StorageRow(
                    title = "Modello IA locale",
                    sizeBytes = uiState.modelSizeBytes,
                    onDelete = { viewModel.deleteModel() },
                )
            } else {
                Text(text = "Nessun modello IA scaricato.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StorageRow(title: String, sizeBytes: Long, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(text = "${sizeBytes.toMb()} MB", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("Elimina") }
        }
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = "Eliminare $title?",
            message = "Questo spazio verrà liberato rimuovendo i dati salvati localmente.",
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

private fun Long.toMb(): Long = this / (1024 * 1024)
