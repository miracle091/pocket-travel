package com.pockettravel.feature.vault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.data.Passport
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR
import java.util.UUID
import kotlinx.coroutines.launch

// Solo BIOMETRIC_STRONG: il BiometricPrompt qui e' legato a un CryptoObject (vedi showPrompt),
// e androidx.biometric non supporta CryptoObject insieme a DEVICE_CREDENTIAL (IllegalArgumentException
// a runtime) — niente piu' fallback PIN/sequenza dentro questo prompt (il dispositivo resta comunque
// protetto dal proprio blocco schermo a monte).
private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG

private const val MAX_PHOTOS_PER_DOCUMENT = 50

@StringRes
private fun DocumentType.label(): Int = when (this) {
    DocumentType.PASSPORT -> R.string.vault_type_passport
    DocumentType.TICKET -> R.string.vault_type_ticket
    DocumentType.OTHER -> R.string.vault_type_other
}

private enum class GateStatus { CHECKING, NOT_ENROLLED, LOCKED, UNLOCKED }

// Passaporto proprio e di altri, cifrati con una chiave di sessione (SessionAesCipher) sbloccata
// tramite BiometricPrompt.CryptoObject: la vera operazione biometrica avviene una volta sola
// all'ingresso, per avvolgere/svolgere quella chiave (vedi VaultKeyEnvelope/PassportRepository),
// non uno sblocco per azione. La chiave di sessione vive solo in memoria e viene scartata
// (repository.lock()) quando questa schermata viene chiusa.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportVaultScreen(onBack: (() -> Unit)? = null, viewModel: PassportVaultViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var gateStatus by remember {
        val canAuthenticate = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
        mutableStateOf(
            if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) GateStatus.LOCKED else GateStatus.NOT_ENROLLED,
        )
    }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.lock() }
    }

    // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
    // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vault_title)) },
                navigationIcon = {
                    // Destinazione principale della barra di navigazione: nessuna freccia indietro.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (gateStatus == GateStatus.UNLOCKED) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(AppIcons.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.vault_add)) },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (gateStatus) {
                GateStatus.NOT_ENROLLED -> NoLockScreenSetUp()
                GateStatus.CHECKING, GateStatus.LOCKED -> LockedContent(
                    viewModel = viewModel,
                    onUnlock = { gateStatus = it },
                )
                GateStatus.UNLOCKED -> PassportList(viewModel)
            }
        }
    }

    if (showAddDialog) {
        PassportEditDialog(
            viewModel = viewModel,
            existing = null,
            onSave = { viewModel.save(it); showAddDialog = false },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun NoLockScreenSetUp() {
    val context = LocalContext.current
    EmptyState(
        icon = AppIcons.Lock,
        title = stringResource(R.string.vault_no_biometric_title),
        subtitle = stringResource(R.string.vault_no_biometric_body),
        modifier = Modifier.fillMaxSize(),
        action = {
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) {
                Text(stringResource(R.string.vault_open_security_settings))
            }
        },
    )
}

@Composable
private fun LockedContent(viewModel: PassportVaultViewModel, onUnlock: (GateStatus) -> Unit) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun showPrompt() {
        val activity = context as FragmentActivity
        val unlockIntent = viewModel.prepareUnlockCipher()
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.vault_prompt_title))
            .setSubtitle(context.getString(R.string.vault_prompt_subtitle))
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .setNegativeButtonText(context.getString(R.string.vault_prompt_cancel))
            .build()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher == null) {
                        errorMessage = context.getString(R.string.vault_unlock_failed)
                        return
                    }
                    viewModel.completeUnlock(unlockIntent, authenticatedCipher)
                    errorMessage = null
                    onUnlock(GateStatus.UNLOCKED)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    errorMessage = errString.toString()
                }

                override fun onAuthenticationFailed() {
                    errorMessage = context.getString(R.string.vault_auth_failed)
                }
            },
        )
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(unlockIntent.cipher))
    }

    EmptyState(
        icon = AppIcons.Lock,
        title = stringResource(R.string.vault_locked_title),
        subtitle = errorMessage ?: stringResource(R.string.vault_locked_body),
        modifier = Modifier.fillMaxSize(),
        action = {
            Button(onClick = { showPrompt() }) {
                Text(stringResource(R.string.vault_unlock))
            }
        },
    )
}

@Composable
private fun PassportList(viewModel: PassportVaultViewModel) {
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
private fun PassportCard(viewModel: PassportVaultViewModel, passport: Passport, onDelete: () -> Unit) {
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
                            text = stringResource(R.string.vault_expiry, passport.expiryDate),
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
private fun PhotoThumbnail(
    viewModel: PassportVaultViewModel,
    fileName: String,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    var bitmap by remember(fileName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(fileName) {
        bitmap = viewModel.loadPhoto(fileName)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
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
private fun PhotoViewerDialog(viewModel: PassportVaultViewModel, fileName: String, onDismiss: () -> Unit) {
    var bitmap by remember(fileName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(fileName) {
        bitmap = viewModel.loadPhoto(fileName)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.vault_close)) } },
        text = {
            bitmap?.let { image ->
                Image(bitmap = image, contentDescription = stringResource(R.string.vault_photo), modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

// existing == null crea un nuovo documento; altrimenti modifica quello passato (stesso id,
// dateOfBirth/issueDate preservati anche se non editabili da questo form). Le foto scattate in
// questa sessione di dialogo ma mai salvate (Annulla) vengono scartate; le foto gia' presenti su
// un documento esistente e rimosse qui vengono cancellate solo alla conferma (Salva), cosi' un
// Annulla non rompe i riferimenti del documento gia' salvato su disco.
// Dialog a schermo intero (M3 per i moduli su telefono): chiudi a sinistra, Salva in alto a destra.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassportEditDialog(
    viewModel: PassportVaultViewModel,
    existing: Passport?,
    onSave: (Passport) -> Unit,
    onDismiss: () -> Unit,
) {
    var fullName by rememberSaveable { mutableStateOf(existing?.fullName ?: "") }
    var documentNumber by rememberSaveable { mutableStateOf(existing?.documentNumber ?: "") }
    var nationality by rememberSaveable { mutableStateOf(existing?.nationality ?: "") }
    var expiryDate by rememberSaveable { mutableStateOf(existing?.expiryDate ?: "") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var documentType by rememberSaveable { mutableStateOf(existing?.documentType ?: DocumentType.PASSPORT) }
    val initialPhotoFileNames = remember { existing?.photoFileNames ?: emptyList() }
    var photoFileNames by rememberSaveable { mutableStateOf(initialPhotoFileNames) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showCamera by remember { mutableStateOf(false) }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showCamera = true
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val cancel = {
        (photoFileNames - initialPhotoFileNames.toSet()).forEach { viewModel.discardPhoto(it) }
        onDismiss()
    }
    val canSave = fullName.isNotBlank() && documentNumber.isNotBlank()

    Dialog(
        onDismissRequest = cancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
                                (initialPhotoFileNames - photoFileNames.toSet()).forEach { viewModel.discardPhoto(it) }
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
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.vault_save)) }
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
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    DocumentType.entries.forEach { type ->
                        FilterChip(
                            selected = documentType == type,
                            onClick = { documentType = type },
                            label = { Text(stringResource(type.label())) },
                        )
                    }
                }
                OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text(stringResource(R.string.vault_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = documentNumber, onValueChange = { documentNumber = it }, label = { Text(stringResource(R.string.vault_field_number)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = nationality, onValueChange = { nationality = it }, label = { Text(stringResource(R.string.vault_field_nationality)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = expiryDate, onValueChange = { expiryDate = it }, label = { Text(stringResource(R.string.vault_field_expiry)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.vault_field_note)) }, modifier = Modifier.fillMaxWidth())
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
                            stringResource(R.string.vault_photo_limit, MAX_PHOTOS_PER_DOCUMENT)
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
                    photoFileNames = photoFileNames + viewModel.savePhoto(jpegBytes)
                }
            },
            onClose = { showCamera = false },
        )
    }
}
