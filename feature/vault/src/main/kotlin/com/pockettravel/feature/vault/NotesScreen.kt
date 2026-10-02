package com.pockettravel.feature.vault

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.Note
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

// Note personali (prenotazioni, indirizzi, itinerario), cifrate sul telefono ma senza il gate
// biometrico del vault documenti (PassportVaultScreen): l'assistente IA le legge per rispondere
// senza chiedere l'impronta, come da contratto in NoteRepository.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: NotesViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    // Senza barra in alto: la ospita DocumentsScreen, sotto il selettore Documenti | Note.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(AppIcons.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.notes_add)) },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (notes.isEmpty()) {
                EmptyState(
                    icon = AppIcons.Notes,
                    title = stringResource(R.string.notes_empty_title),
                    subtitle = stringResource(R.string.notes_empty_subtitle),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Spazio in fondo per non coprire l'ultima nota con il FAB esteso.
                    contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    item(key = "privacy_notice") {
                        Text(
                            text = stringResource(R.string.notes_privacy_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(notes, key = { it.id }) { note ->
                        NoteCard(viewModel = viewModel, note = note)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        NoteEditDialog(
            existing = null,
            onSave = { viewModel.save(it); showAddDialog = false },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun NoteCard(viewModel: NotesViewModel, note: Note) {
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.l, end = Spacing.xs),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    AppIcons.Notes,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(Spacing.s),
                )
            }
            Spacer(modifier = Modifier.width(Spacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = note.title, style = MaterialTheme.typography.titleMedium)
                if (note.body.isNotBlank()) {
                    Text(
                        text = note.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
            IconButton(onClick = { showEditDialog = true }) {
                Icon(AppIcons.Edit, contentDescription = stringResource(R.string.notes_edit, note.title))
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.notes_delete, note.title))
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.notes_delete_title),
            message = stringResource(R.string.notes_delete_message, note.title),
            onConfirm = { showDeleteConfirm = false; viewModel.delete(note.id) },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showEditDialog) {
        NoteEditDialog(
            existing = note,
            onSave = { viewModel.save(it); showEditDialog = false },
            onDismiss = { showEditDialog = false },
        )
    }
}

// existing == null crea una nuova nota; altrimenti modifica quella passata (stesso id). Dialogo a
// schermo intero come PassportEditDialog: chiudi a sinistra, Salva in alto a destra.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditDialog(existing: Note?, onSave: (Note) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var body by rememberSaveable { mutableStateOf(existing?.body ?: "") }
    val canSave = title.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (existing == null) R.string.notes_new else R.string.notes_edit_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.notes_cancel))
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                onSave(
                                    Note(
                                        id = existing?.id ?: 0,
                                        title = title,
                                        body = body,
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.notes_save)) }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.notes_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.notes_field_body)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
            }
        }
    }
}
