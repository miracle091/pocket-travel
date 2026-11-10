package com.pockettravel.feature.vault

import android.app.Activity
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Solo BIOMETRIC_STRONG: il BiometricPrompt qui e' legato a un CryptoObject (vedi showPrompt),
// e androidx.biometric non supporta CryptoObject insieme a DEVICE_CREDENTIAL (IllegalArgumentException
// a runtime): nessun fallback PIN/sequenza dentro questo prompt (il dispositivo resta comunque
// protetto dal proprio blocco schermo a monte).
internal const val ALLOWED_AUTHENTICATORS = BIOMETRIC_STRONG

@StringRes
internal fun DocumentType.label(): Int = when (this) {
    DocumentType.PASSPORT -> R.string.vault_type_passport
    DocumentType.TICKET -> R.string.vault_type_ticket
    DocumentType.OTHER -> R.string.vault_type_other
}

// Passaporto proprio e di altri, cifrati con una chiave di sessione (SessionAesCipher) sbloccata
// tramite BiometricPrompt.CryptoObject: la vera operazione biometrica avviene una volta sola
// all'ingresso, per avvolgere/svolgere quella chiave (vedi VaultKeyEnvelope/PassportRepository),
// non uno sblocco per azione. La chiave di sessione vive solo in memoria e viene scartata
// (repository.lock()) quando questa schermata viene chiusa.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var gateStatus by remember {
        val authCode = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
        mutableStateOf(initialGateStatus(authCode, viewModel.isUnlocked))
    }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    // L'utente puo' uscire da qui per impostare il blocco schermo nelle Impostazioni: al ritorno
    // (ON_RESUME) si ricontrolla la biometria, cosi' NOT_ENROLLED non resta bloccato per sempre.
    // Da UNLOCKED non cambia nulla; da LOCKED passa a NOT_ENROLLED se la biometria e' sparita.
    // App in background (ON_STOP, non una rotazione): la cassaforte si chiude, al ritorno serve di nuovo
    // lo sblocco. Il prompt biometrico e il dialogo dei permessi non fermano l'activity.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val authCode = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
                gateStatus = gateStatusOnResume(gateStatus, authCode)
            }
            if (event == Lifecycle.Event.ON_STOP && gateStatus == GateStatus.UNLOCKED && (context as? Activity)?.isChangingConfigurations != true) {
                viewModel.lock()
                gateStatus = GateStatus.LOCKED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Il blocco (scarto della chiave di sessione) deve avvenire solo lasciando davvero la
    // schermata, non a una rotazione: isChangingConfigurations distingue i due casi.
    val activity = context as? Activity
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.lock() }
    }

    // Niente dati del passaporto nella miniatura delle app recenti (scattata a onPause, prima del blocco a ON_STOP).
    SecureWindow()

    // Scatti temporanei rimasti in chiaro se il processo e' morto durante uno scatto.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { wipeCameraTmp(context) } }

    // Senza barra in alto: la ospita DocumentsScreen, sotto il selettore Documenti | Note.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Blocco immediato, con lo stesso effetto di quello automatico (chiave scartata, lista e miniature svuotate).
            if (gateStatus == GateStatus.UNLOCKED) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { viewModel.lock(); gateStatus = GateStatus.LOCKED }) {
                        Icon(AppIcons.Lock, contentDescription = stringResource(R.string.vault_lock))
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (gateStatus) {
                    GateStatus.NOT_ENROLLED -> NoLockScreenSetUp()
                    GateStatus.LOCKED -> LockedContent(
                        viewModel = viewModel,
                        onUnlock = { gateStatus = GateStatus.UNLOCKED },
                        onNotEnrolled = { gateStatus = GateStatus.NOT_ENROLLED },
                    )
                    GateStatus.UNLOCKED -> PassportList(viewModel)
                }
            }
        }
    }

    // Solo da sbloccata: il flag sopravvive alla morte del processo, lo sblocco no (save lancerebbe).
    if (showAddDialog && gateStatus == GateStatus.UNLOCKED) {
        PassportEditDialog(
            viewModel = viewModel,
            existing = null,
            onSave = { viewModel.save(it); showAddDialog = false },
            onDismiss = { showAddDialog = false },
        )
    }
}
