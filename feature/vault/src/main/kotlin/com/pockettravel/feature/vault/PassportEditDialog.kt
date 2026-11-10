package com.pockettravel.feature.vault

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.data.Passport
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.launch
import java.util.UUID

private const val MAX_PHOTOS_PER_DOCUMENT = 50

// existing == null crea un nuovo documento; altrimenti modifica quello passato (stesso id,
// dateOfBirth/issueDate preservati anche se non editabili da questo form). Le foto scattate in
// questa sessione di dialogo ma mai salvate (Annulla) vengono scartate; le foto gia' presenti su
// un documento esistente e rimosse qui vengono cancellate solo alla conferma (Salva), cosi' un
// Annulla non rompe i riferimenti del documento gia' salvato su disco.
// Dialog a schermo intero (M3 per i moduli su telefono): chiudi a sinistra, Salva in alto a destra.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PassportEditDialog(
    viewModel: VaultViewModel,
    existing: Passport?,
    onSave: (Passport) -> Unit,
    onDismiss: () -> Unit,
) {
    // I testi stanno nella bozza del ViewModel, non in rememberSaveable: sopravvivono alla ricreazione
    // dell'activity ma i dati del documento non finiscono nello stato salvato (Bundle), che arriva prima del blocco.
    val draft = remember { viewModel.drafts.draftFor(existing) }
    var fullName by draft::fullName
    var documentNumber by draft::documentNumber
    var nationality by draft::nationality
    var expiryDate by draft::expiryDate
    var note by draft::note
    var documentType by rememberSaveable { mutableStateOf(existing?.documentType ?: DocumentType.PASSPORT) }
    val initialPhotoFileNames = remember { existing?.photoFileNames ?: emptyList() }
    var photoFileNames by rememberSaveable { mutableStateOf(initialPhotoFileNames) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showCamera by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val cameraPermissionDeniedMessage = stringResource(R.string.vault_camera_permission_denied)
    val openAppSettingsLabel = stringResource(R.string.vault_open_app_settings)
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            showCamera = true
        } else {
            // "Non chiedere piu'": shouldShowRequestPermissionRationale torna false anche prima
            // della primissima richiesta, ma qui siamo sempre dopo un rifiuto, quindi false vuol
            // dire davvero "negato per sempre" — solo allora ha senso proporre le impostazioni.
            val activity = context as? Activity
            val permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            coroutineScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = cameraPermissionDeniedMessage,
                    actionLabel = if (permanentlyDenied) openAppSettingsLabel else null,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                    )
                }
            }
        }
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showCamera = true
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // Chiusa con Annulla o Salva: il resto e' un'uscita dalla composizione (es. blocco a ON_STOP) che
    // lascerebbe orfane le foto gia' salvate in questa sessione di dialogo. Non vale per la rotazione.
    var closed by remember { mutableStateOf(false) }
    val activity = context as? Activity
    DisposableEffect(Unit) {
        onDispose {
            if (!closed && activity?.isChangingConfigurations != true) {
                viewModel.discardUnusedPhotos(initialPhotoFileNames, photoFileNames, saved = false)
            }
        }
    }
    val cancel = {
        closed = true
        viewModel.drafts.clear()
        viewModel.discardUnusedPhotos(initialPhotoFileNames, photoFileNames, saved = false)
        onDismiss()
    }
    val canSave = canSaveDocument(documentType, fullName, documentNumber)
    val fields = documentType.fields()
    // Gli elenchi di aeroporti e compagnie si caricano solo se il documento e' un biglietto.
    val travelData by produceState<TravelData?>(null, documentType) {
        if (documentType == DocumentType.TICKET) value = TravelDataLoader.load(context)
    }

    Dialog(
        onDismissRequest = cancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (existing == null) R.string.vault_new_document else R.string.vault_edit_document)) },
                    navigationIcon = {
                        IconButton(onClick = cancel) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.vault_cancel))
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = canSave,
                            onClick = {
                                closed = true
                                viewModel.drafts.clear()
                                viewModel.discardUnusedPhotos(initialPhotoFileNames, photoFileNames, saved = true)
                                onSave(
                                    Passport(
                                        id = existing?.id ?: UUID.randomUUID().toString(),
                                        fullName = fullName,
                                        documentNumber = documentNumber,
                                        nationality = nationality,
                                        dateOfBirth = existing?.dateOfBirth ?: "",
                                        issueDate = existing?.issueDate ?: "",
                                        expiryDate = expiryDate,
                                        note = note,
                                        photoFileNames = photoFileNames,
                                        documentType = documentType,
                                    ).withoutHiddenFields(existing?.documentType),
                                )
                            },
                        ) { Text(stringResource(R.string.vault_save)) }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    DocumentType.entries.forEach { type ->
                        FilterChip(
                            selected = documentType == type,
                            onClick = { documentType = type },
                            label = { Text(stringResource(type.label())) },
                        )
                    }
                }
                SuggestionField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = stringResource(fields.name),
                    index = if (documentType == DocumentType.TICKET) travelData?.airlines else null,
                )
                OutlinedTextField(
                    value = documentNumber,
                    onValueChange = { documentNumber = it },
                    label = { Text(stringResource(fields.number)) },
                    singleLine = true,
                    // Niente correzione automatica: la tastiera non deve imparare il numero del documento.
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                fields.nationality?.let { label ->
                    if (documentType == DocumentType.TICKET) {
                        RouteFields(
                            value = nationality,
                            onValueChange = { nationality = it },
                            airports = travelData?.airports,
                            italian = LocalConfiguration.current.locales[0].language == "it",
                        )
                    } else {
                        OutlinedTextField(value = nationality, onValueChange = { nationality = it }, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                fields.expiry?.let { label ->
                    OutlinedTextField(value = expiryDate, onValueChange = { expiryDate = it }, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(fields.note)) }, modifier = Modifier.fillMaxWidth())
                if (photoFileNames.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        items(photoFileNames, key = { it }) { fileName ->
                            PhotoThumbnail(
                                viewModel = viewModel,
                                fileName = fileName,
                                onRemove = { photoFileNames = photoFileNames - fileName },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { openCamera() },
                    enabled = photoFileNames.size < MAX_PHOTOS_PER_DOCUMENT,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(AppIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(
                        if (photoFileNames.size < MAX_PHOTOS_PER_DOCUMENT) {
                            stringResource(R.string.vault_add_photo)
                        } else {
                            pluralStringResource(R.plurals.vault_photo_limit, MAX_PHOTOS_PER_DOCUMENT, MAX_PHOTOS_PER_DOCUMENT)
                        },
                    )
                }
            }
        }
    }

    if (showCamera) {
        DocumentCameraCaptureScreen(
            photoCount = photoFileNames.size,
            maxPhotos = MAX_PHOTOS_PER_DOCUMENT,
            onCaptured = { jpegBytes ->
                coroutineScope.launch {
                    val savedFileName = viewModel.savePhoto(jpegBytes)
                    if (savedFileName == null) showCamera = false else photoFileNames = photoFileNames + savedFileName
                }
            },
            onClose = { showCamera = false },
        )
    }
}
