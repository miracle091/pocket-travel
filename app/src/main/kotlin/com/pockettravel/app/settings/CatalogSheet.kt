package com.pockettravel.app.settings

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pockettravel.app.R
import com.pockettravel.core.sync.CatalogCheck
import com.pockettravel.core.sync.CustomCatalog
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing

/**
 * Catalogo da usare al posto di quello ufficiale: indirizzo di manifest.json e chiave pubblica delle sue firme,
 * per chi pubblica i dati con la pipeline del progetto. Il catalogo si prova prima di salvarlo; poi l'app si riavvia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogSheet(
    current: CustomCatalog?,
    state: CatalogState,
    onUse: (manifestUrl: String, publicKey: String) -> Unit,
    onUseOfficial: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(current?.manifestUrl.orEmpty()) }
    var key by rememberSaveable { mutableStateOf(current?.publicKey.orEmpty()) }
    val checking = state == CatalogState.Checking
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding()
                .padding(horizontal = Spacing.xl).padding(bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                text = stringResource(R.string.settings_catalog),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.settings_catalog_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_catalog_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; onEdit() },
                label = { Text(stringResource(R.string.settings_catalog_url)) },
                singleLine = true,
                enabled = !checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; onEdit() },
                label = { Text(stringResource(R.string.settings_catalog_key)) },
                minLines = 2,
                enabled = !checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state is CatalogState.Failed) {
                Text(
                    text = stringResource(errorText(state.check)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Button(onClick = { onUse(url, key) }, enabled = !checking && url.isNotBlank() && key.isNotBlank()) {
                    Text(stringResource(R.string.settings_catalog_use))
                }
                if (checking) PocketTravelLoadingIndicator(modifier = Modifier.size(24.dp))
            }
            if (current != null) {
                TextButton(onClick = onUseOfficial, enabled = !checking) { Text(stringResource(R.string.settings_catalog_reset)) }
            }
        }
    }
}

@StringRes
private fun errorText(check: CatalogCheck): Int = when (check) {
    CatalogCheck.INVALID_URL -> R.string.settings_catalog_error_url
    CatalogCheck.INVALID_KEY -> R.string.settings_catalog_error_key
    CatalogCheck.UNREACHABLE -> R.string.settings_catalog_error_unreachable
    CatalogCheck.BAD_SIGNATURE -> R.string.settings_catalog_error_signature
    CatalogCheck.NOT_A_CATALOG -> R.string.settings_catalog_error_format
    // Non succede: con OK lo stato e' Restart, non Failed.
    CatalogCheck.OK -> R.string.settings_catalog_error_format
}

/**
 * Riavvia l'app dalla schermata iniziale: il catalogo si legge solo all'avvio del processo (PocketTravelApp),
 * cosi' nessun componente resta con quello precedente. I download in corso riprendono con WorkManager.
 */
internal fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}
