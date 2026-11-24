package com.pockettravel.feature.ai

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.DownloadProgressIndicator
import com.pockettravel.core.ui.NetworkBlockSettingsButton
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isNetworkBlockMessage
import com.pockettravel.core.ui.R as UiR

/** La coppia di traduzione italiano-inglese per le Impostazioni, sotto i modelli dell'assistente. */
@Composable
fun TranslationModelCard(viewModel: TranslationModelViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TranslationModelContent(state, viewModel::download, viewModel::cancelDownload, viewModel::delete)
}

@Composable
internal fun TranslationModelContent(
    state: TranslationModelUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            stringResource(R.string.translation_model_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Spacing.m).semantics { heading() },
        )
        Text(
            stringResource(R.string.translation_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.translation_model_size,
                Formatter.formatShortFileSize(context, BergamotModels.downloadBytes),
                Formatter.formatShortFileSize(context, BergamotModels.installedBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        state.errorMessage?.let {
            Text(
                text = stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (isNetworkBlockMessage(it)) NetworkBlockSettingsButton(it)
        }
        val progress = state.downloadProgress
        when {
            state.isInstalled -> {
                Text(stringResource(R.string.translation_model_downloaded), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showDeleteConfirm = true }) {
                    Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(stringResource(R.string.translation_model_delete))
                }
            }
            progress != null -> {
                Text(
                    stringResource(R.string.ai_model_downloading, (progress * PERCENT_SCALE).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
                DownloadProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text(stringResource(UiR.string.confirm_cancel)) }
            }
            else -> Button(onClick = onDownload) {
                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(stringResource(if (state.errorMessage != null) R.string.ai_model_retry else R.string.translation_model_download))
            }
        }
    }
    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.translation_model_delete_title),
            message = stringResource(R.string.translation_model_delete_message),
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
