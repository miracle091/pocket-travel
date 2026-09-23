package com.pockettravel.core.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

// Conferma condivisa per azioni distruttive (elimina regione/modello, rimuovi chiave API, ecc.):
// prima di questa, ogni eliminazione nell'app scattava al primo tap, senza possibilità di
// annullare per errore. destructive = true (default): icona e azione di conferma in colore error,
// come prevede M3 per le azioni irreversibili.
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String = stringResource(R.string.confirm_delete),
    dismissLabel: String = stringResource(R.string.confirm_cancel),
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (destructive) {
            { Icon(AppIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}
