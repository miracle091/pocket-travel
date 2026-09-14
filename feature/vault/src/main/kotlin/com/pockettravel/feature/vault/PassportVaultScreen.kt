package com.pockettravel.feature.vault

import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.Passport
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import java.util.UUID

private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

private enum class GateStatus { CHECKING, NOT_ENROLLED, LOCKED, UNLOCKED }

// Passaporto proprio e di altri, cifrati con Android Keystore (vedi PassportRepository) e
// sbloccati con biometria/PIN a livello di schermata: un solo prompt all'ingresso, non uno per
// azione (vedi motivazione nel piano — evita complessita' di sessione non giustificata per una v1
// e non richiede un campo etichetta non cifrato per mostrare la lista prima dello sblocco).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportVaultScreen(onBack: () -> Unit, viewModel: PassportVaultViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var gateStatus by remember {
        val canAuthenticate = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
        mutableStateOf(
            if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) GateStatus.LOCKED else GateStatus.NOT_ENROLLED,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documenti") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (gateStatus) {
                GateStatus.NOT_ENROLLED -> NoLockScreenSetUp()
                GateStatus.CHECKING, GateStatus.LOCKED -> LockedContent(
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
        Icon(imageVector = AppIcons.passport(), contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Questo dispositivo non ha un blocco schermo configurato (PIN, sequenza o biometria). " +
                "Per proteggere i documenti salvati qui, imposta prima un blocco schermo.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) {
            Text("Apri impostazioni di sicurezza")
        }
    }
}

@Composable
private fun LockedContent(onUnlock: (GateStatus) -> Unit) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun showPrompt() {
        val activity = context as FragmentActivity
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sblocca i documenti")
            .setSubtitle("Usa la biometria o il blocco schermo del dispositivo")
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .build()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
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
        prompt.authenticate(promptInfo)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = AppIcons.passport(), contentDescription = null, modifier = Modifier.size(48.dp))
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

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Aggiungi documento")
            }
        },
    ) { innerPadding ->
        if (passports.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                Text("Nessun documento salvato. Usa il pulsante + per aggiungerne uno.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                items(passports, key = { it.id }) { passport ->
                    PassportCard(passport = passport, onDelete = { viewModel.delete(passport.id) })
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        PassportEditDialog(
            onSave = { viewModel.save(it); showAddDialog = false },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun PassportCard(passport: Passport, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = passport.fullName, style = MaterialTheme.typography.titleMedium)
                Text(text = "${passport.documentNumber} · ${passport.nationality}", style = MaterialTheme.typography.bodySmall)
                if (passport.expiryDate.isNotBlank()) {
                    Text(text = "Scadenza: ${passport.expiryDate}", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = { showDeleteConfirm = true }) { Text("Elimina") }
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
}

@Composable
private fun PassportEditDialog(onSave: (Passport) -> Unit, onDismiss: () -> Unit) {
    var fullName by rememberSaveable { mutableStateOf("") }
    var documentNumber by rememberSaveable { mutableStateOf("") }
    var nationality by rememberSaveable { mutableStateOf("") }
    var expiryDate by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuovo documento") },
        text = {
            Column {
                OutlinedTextField(value = fullName, onValueChange = { fullName = it }, label = { Text("Nome e cognome") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = documentNumber, onValueChange = { documentNumber = it }, label = { Text("Numero documento") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = nationality, onValueChange = { nationality = it }, label = { Text("Nazionalità") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = expiryDate, onValueChange = { expiryDate = it }, label = { Text("Scadenza (AAAA-MM-GG)") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = fullName.isNotBlank() && documentNumber.isNotBlank(),
                onClick = {
                    onSave(
                        Passport(
                            id = UUID.randomUUID().toString(),
                            fullName = fullName,
                            documentNumber = documentNumber,
                            nationality = nationality,
                            expiryDate = expiryDate,
                            note = note,
                        ),
                    )
                },
            ) { Text("Salva") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
    )
}
