package com.pockettravel.core.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

// Conferma condivisa per azioni distruttive (elimina regione/modello, rimuovi chiave API, ecc.):
// prima di questa, ogni eliminazione nell'app scattava al primo tap, senza possibilità di
// annullare per errore.
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String = "Elimina",
    dismissLabel: String = "Annulla",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}
