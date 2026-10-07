package com.pockettravel.feature.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.Spacing

// Campo di testo libero con il completamento sotto, nel flusso della colonna (un menu a comparsa finirebbe
// sotto la tastiera nel dialogo a schermo intero). Senza indice si comporta come un campo normale.
@Composable
internal fun SuggestionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    index: SuggestionIndex?,
    italian: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val suggestions = remember(value, open, index) {
        if (open && index != null) index.search(value) else emptyList()
    }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                open = true
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) open = false },
        )
        if (suggestions.isNotEmpty()) {
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column {
                    suggestions.forEach { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChange(entry.text(italian).value)
                                    open = false
                                }
                                .padding(horizontal = Spacing.m, vertical = Spacing.s),
                        ) {
                            Text(
                                text = "${entry.code} · ${entry.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail = entry.text(italian).detail
                            if (detail.isNotEmpty()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Partenza e arrivo di un biglietto: due campi con il completamento degli aeroporti, salvati in un
// unico testo "partenza – arrivo" (vedi splitRoute e joinRoute).
@Composable
internal fun RouteFields(value: String, onValueChange: (String) -> Unit, airports: SuggestionIndex?, italian: Boolean) {
    val (from, to) = splitRoute(value)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SuggestionField(from, { onValueChange(joinRoute(it, to)) }, stringResource(R.string.vault_field_ticket_from), airports, italian)
        SuggestionField(to, { onValueChange(joinRoute(from, it)) }, stringResource(R.string.vault_field_ticket_to), airports, italian)
    }
}
