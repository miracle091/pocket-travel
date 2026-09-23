package com.pockettravel.app.storage

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val usedBytes = uiState.regionsSizeBytes + uiState.modelSizeBytes
    val totalBytes = usedBytes + uiState.availableBytes
    val usedFraction = if (totalBytes > 0) usedBytes / totalBytes.toFloat() else 0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            Text(
                text = stringResource(
                    R.string.storage_summary,
                    Formatter.formatShortFileSize(context, usedBytes),
                    Formatter.formatShortFileSize(context, uiState.availableBytes),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.m, bottom = Spacing.xl),
            )

            SectionHeader(stringResource(R.string.storage_regions))
            if (uiState.installedRegions.isEmpty()) {
                EmptyLine(stringResource(R.string.storage_no_regions))
            } else {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        uiState.installedRegions.forEachIndexed { index, region ->
                            StorageRow(
                                icon = AppIcons.WorldFilled,
                                title = region.displayName,
                                sizeBytes = region.sizeBytes,
                                onDelete = { viewModel.deleteRegion(region.regionId) },
                            )
                            if (index < uiState.installedRegions.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.storage_model), modifier = Modifier.padding(top = Spacing.xl))
            if (uiState.isModelDownloaded) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    StorageRow(
                        icon = AppIcons.AiAssistant,
                        title = stringResource(R.string.storage_model),
                        sizeBytes = uiState.modelSizeBytes,
                        onDelete = { viewModel.deleteModel() },
                    )
                }
            } else {
                EmptyLine(stringResource(R.string.storage_no_model))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Spacing.l, bottom = Spacing.s).semantics { heading() },
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.l),
    )
}

@Composable
private fun StorageRow(icon: ImageVector, title: String, sizeBytes: Long, onDelete: () -> Unit) {
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(Formatter.formatShortFileSize(LocalContext.current, sizeBytes)) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.storage_delete, title))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.storage_delete_title, title),
            message = stringResource(R.string.storage_delete_message),
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
