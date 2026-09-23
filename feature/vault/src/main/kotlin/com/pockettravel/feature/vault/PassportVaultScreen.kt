package com.pockettravel.feature.vault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.data.Passport
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.EmptyState
import java.util.UUID
import kotlinx.coroutines.launch

// Solo BIOMETRIC_STRONG: il BiometricPrompt qui e' legato a un CryptoObject (vedi showPrompt),
// e androidx.biometric non supporta CryptoObject insieme a DEVICE_CREDENTIAL (IllegalArgumentException
// a runtime) — niente piu' fallback PIN/sequenza dentro questo prompt (il dispositivo resta comunque
// protetto dal proprio blocco schermo a monte).
private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG

private const val MAX_PHOTOS_PER_DOCUMENT = 50

private fun DocumentType.label(): String = when (this) {
    DocumentType.PASSPORT -> "Passaporto"
    DocumentType.TICKET -> "Biglietto"
    DocumentType.OTHER -> "Altro"
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

    DisposableEffect(Unit) {
        onDispose { viewModel.lock() }
    }

    // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
    // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Documenti") },
                navigationIcon = {
                    // Destinazione principale della barra di navigazione: nessuna freccia indietro.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AppIcons.Back, contentDescription = "Indietro")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (gateStatus) {
                GateStatus.NOT_ENROLLED -> NoLockScreenSetUp()
                GateStatus.CHECKING, GateStatus.LOCKED -> LockedContent(
                    viewModel = viewModel,
                    onUnlock = { gateStatus = it },
                )
                GateStatus.UNLOCKED -> PassportVaultContent(viewModel)
            }
        }
    }
}

@Composable
private fun NoLockScreenSetUp() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = AppIcons.Passport, contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Questo dispositivo non ha una biometria forte configurata (impronta o volto). " +
                "Per proteggere i documenti salvati qui, imposta prima la biometria nelle impostazioni.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) {
            Text("Apri impostazioni di sicurezza")
        }
    }
}

@Composable
private fun LockedContent(viewModel: PassportVaultViewModel, onUnlock: (GateStatus) -> Unit) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun showPrompt() {
        val activity = context as FragmentActivity
        val unlockIntent = viewModel.prepareUnlockCipher()
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sblocca i documenti")
            .setSubtitle("Usa la biometria del dispositivo")
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .setNegativeButtonText("Annulla")
            .build()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher == null) {
                        errorMessage = "Sblocco non riuscito, riprova."
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
                    errorMessage = "Autenticazione non riuscita, riprova."
                }
            },
        )
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(unlockIntent.cipher))
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = AppIcons.Passport, contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "I documenti sono protetti da biometria o blocco schermo.", style = MaterialTheme.typography.bodyMedium)
        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { showPrompt() }) {
            Text("Sblocca")
        }
    }
}

@Composable
private fun PassportVaultContent(viewModel: PassportVaultViewModel) {
    val passports by viewModel.passports.collectAsStateWithLifecycle()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
    // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(imageVector = AppIcons.Add, contentDescription = "Aggiungi documento")
            }
        },
    ) { innerPadding ->
        if (passports.isEmpty()) {
            EmptyState(
                icon = AppIcons.Passport,
                title = "Nessun documento salvato",
                subtitle = "Usa il pulsante + per aggiungerne uno.",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                items(passports, key = { it.id }) { passport ->
                    PassportCard(viewModel = viewModel, passport = passport, onDelete = { viewModel.delete(passport.id) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
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
private fun PassportCard(viewModel: PassportVaultViewModel, passport: Passport, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var viewerFileName by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = passport.documentType.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(text = passport.fullName, style = MaterialTheme.typography.titleMedium)
                    Text(text = "${passport.documentNumber} · ${passport.nationality}", style = MaterialTheme.typography.bodySmall)
                    if (passport.expiryDate.isNotBlank()) {
                        Text(text = "Scadenza: ${passport.expiryDate}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row {
                    OutlinedButton(onClick = { showEditDialog = true }) { Text("Modifica") }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("Elimina") }
                }
            }
            if (passport.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = passport.note, style = MaterialTheme.typography.bodySmall)
            }
            if (passport.photoFileNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(passport.photoFileNames, key = { it }) { fileName ->
                        PhotoThumbnail(viewModel = viewModel, fileName = fileName, onClick = { viewerFileName = fileName })
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = "Eliminare questo documento?",
            message = "${passport.fullName} verrà rimosso definitivamente dal vault.",
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
// resta vuoto invece di mostrare un placeholder, coerente con la semplicita' del resto della UI.
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
    Box(modifier = Modifier.size(64.dp)) {
        bitmap?.let { image ->
            Image(
                bitmap = image,
                contentDescription = "Foto documento",
                contentScale = ContentScale.Crop,
                modifier = if (onClick != null) {
                    Modifier.size(64.dp).clickable(onClick = onClick)
                } else {
                    Modifier.size(64.dp)
                },
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(20.dp)) {
                Icon(imageVector = AppIcons.Close, contentDescription = "Rimuovi foto", tint = Color.White)
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        text = {
            bitmap?.let { image ->
                Image(bitmap = image, contentDescription = "Foto documento", modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

// existing == null crea un nuovo documento; altrimenti modifica quello passato (stesso id,
// dateOfBirth/issueDate preservati anche se non editabili da questo form). Le foto scattate in
// questa sessione di dialogo ma mai salvate (Annulla) vengono scartate; le foto gia' presenti su
// un documento esistente e rimosse qui vengono cancellate solo alla conferma (Salva), cosi' un
// Annulla non rompe i riferimenti del documento gia' salvato su disco.
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nuovo documento" else "Modifica documento") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocumentType.entries.forEach { type ->
                        FilterChip(
                            selected = documentType == type,
                            onClick = { documentType = type },
                            label = { Text(type.label()) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text("Nome e cognome") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = documentNumber, onValueChange = { documentNumber = it }, label = { Text("Numero documento") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = nationality, onValueChange = { nationality = it }, label = { Text("Nazionalità") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = expiryDate, onValueChange = { expiryDate = it }, label = { Text("Scadenza (AAAA-MM-GG)") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                if (photoFileNames.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(photoFileNames, key = { it }) { fileName ->
                            PhotoThumbnail(
                                viewModel = viewModel,
                                fileName = fileName,
                                onRemove = { photoFileNames = photoFileNames - fileName },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = { openCamera() },
                    enabled = photoFileNames.size < MAX_PHOTOS_PER_DOCUMENT,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (photoFileNames.size < MAX_PHOTOS_PER_DOCUMENT) {
                            "Aggiungi foto"
                        } else {
                            "Limite di $MAX_PHOTOS_PER_DOCUMENT foto raggiunto"
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = fullName.isNotBlank() && documentNumber.isNotBlank(),
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
            ) { Text("Salva") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    (photoFileNames - initialPhotoFileNames.toSet()).forEach { viewModel.discardPhoto(it) }
                    onDismiss()
                },
            ) { Text("Annulla") }
        },
    )

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
