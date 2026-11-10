package com.pockettravel.feature.vault

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.data.Passport
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.Spacing

@Composable
internal fun PassportList(viewModel: VaultViewModel) {
    val passports by viewModel.passports.collectAsStateWithLifecycle()
    if (passports.isEmpty()) {
        EmptyState(
            icon = AppIcons.Passport,
            title = stringResource(R.string.vault_empty_title),
            subtitle = stringResource(R.string.vault_empty_subtitle),
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Spazio in fondo per non coprire l'ultimo documento con il FAB esteso.
            contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            items(passports, key = { it.id }) { passport ->
                PassportCard(viewModel = viewModel, passport = passport, onDelete = { viewModel.delete(passport.id) })
            }
        }
    }
}

@Composable
private fun PassportCard(viewModel: VaultViewModel, passport: Passport, onDelete: () -> Unit) {
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var viewerFileName by rememberSaveable { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.l, end = Spacing.xs)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        AppIcons.Passport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(Spacing.s),
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.m))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(passport.documentType.label()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = passport.fullName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = listOf(passport.documentNumber, passport.nationality).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (passport.expiryDate.isNotBlank()) {
                        Text(
                            text = stringResource(
                                if (passport.documentType == DocumentType.TICKET) R.string.vault_ticket_date else R.string.vault_expiry,
                                passport.expiryDate,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { showEditDialog = true }) {
                    Icon(AppIcons.Edit, contentDescription = stringResource(R.string.vault_edit, passport.fullName))
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(AppIcons.Delete, contentDescription = stringResource(R.string.vault_delete, passport.fullName))
                }
            }
            if (passport.note.isNotBlank()) {
                Text(
                    text = passport.note,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.m, end = Spacing.m),
                )
            }
            if (passport.photoFileNames.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.padding(top = Spacing.m),
                ) {
                    items(passport.photoFileNames, key = { it }) { fileName ->
                        PhotoThumbnail(viewModel = viewModel, fileName = fileName, onClick = { viewerFileName = fileName })
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.vault_delete_title),
            message = stringResource(R.string.vault_delete_message, passport.fullName),
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showEditDialog) {
        PassportEditDialog(
            viewModel = viewModel,
            existing = passport,
            onSave = { viewModel.save(it); showEditDialog = false },
            onDismiss = { showEditDialog = false },
        )
    }

    viewerFileName?.let { fileName ->
        PhotoViewerDialog(viewModel = viewModel, fileName = fileName, onDismiss = { viewerFileName = null })
    }
}

// bitmap e' null finche' il caricamento/decifratura non e' completo (o se fallisce): il thumbnail
// resta una superficie vuota invece di mostrare un placeholder.
@Composable
internal fun PhotoThumbnail(
    viewModel: VaultViewModel,
    fileName: String,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    var bitmap by remember(fileName) { mutableStateOf(viewModel.cachedThumbnail(fileName)) }
    val thumbnailPx = with(LocalDensity.current) { 72.dp.roundToPx() }
    LaunchedEffect(fileName) {
        if (bitmap == null) bitmap = viewModel.thumbnail(fileName, thumbnailPx)
    }
    Box(modifier = Modifier.size(72.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(72.dp),
        ) {
            bitmap?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.vault_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .let { if (onClick != null) it.clickable(onClick = onClick) else it },
                )
            }
        }
        if (onRemove != null) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs).size(28.dp),
                onClick = onRemove,
            ) {
                Icon(
                    imageVector = AppIcons.Close,
                    contentDescription = stringResource(R.string.vault_photo_remove),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun PhotoViewerDialog(viewModel: VaultViewModel, fileName: String, onDismiss: () -> Unit) {
    var bitmap by remember(fileName) { mutableStateOf<ImageBitmap?>(null) }
    val screenWidthPx = LocalWindowInfo.current.containerSize.width
    LaunchedEffect(fileName) {
        bitmap = viewModel.photo(fileName, screenWidthPx)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_close)) } },
        text = {
            bitmap?.let { image ->
                Image(bitmap = image, contentDescription = stringResource(R.string.vault_photo), modifier = Modifier.fillMaxWidth())
            }
        },
    )
}
