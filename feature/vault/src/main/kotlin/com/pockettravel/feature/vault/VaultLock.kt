package com.pockettravel.feature.vault

import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.crypto.VaultKeyEnvelope
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.EmptyState

@Composable
internal fun NoLockScreenSetUp() {
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
internal fun LockedContent(viewModel: VaultViewModel, onUnlock: () -> Unit, onNotEnrolled: () -> Unit) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Impronte aggiunte o tolte dopo aver creato la cassaforte: la chiave non c'e' piu'.
    var keyInvalidated by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val resetState by viewModel.resetState.collectAsStateWithLifecycle()
    // Il messaggio di chiave invalidata sparisce solo a reset finito: nel frattempo "Sblocca" non lo fa ricomparire.
    LaunchedEffect(resetState) { if (resetState == VaultResetState.DONE) keyInvalidated = false }
    val promptTitle = stringResource(R.string.vault_prompt_title)
    val promptSubtitle = stringResource(R.string.vault_prompt_subtitle)
    val promptCancel = stringResource(R.string.vault_prompt_cancel)
    val unlockFailed = stringResource(R.string.vault_unlock_failed)
    val authFailed = stringResource(R.string.vault_auth_failed)
    val systemUpdateNeeded = stringResource(R.string.vault_system_update_needed)

    fun showPrompt() {
        val activity = context as FragmentActivity
        // Senza biometria forte la chiave non si crea ne' si usa: si mostra la spiegazione, non un errore.
        val authCode = BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS)
        if (authCode != BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometria assente: la spiegazione. Aggiornamento di sistema richiesto: lo si dice.
            // Sensore momentaneamente non disponibile: resta bloccata, si riprova.
            when {
                isBiometricMissing(authCode) -> onNotEnrolled()
                needsSystemUpdate(authCode) -> errorMessage = systemUpdateNeeded
                else -> errorMessage = unlockFailed
            }
            return
        }
        val unlockIntent = viewModel.prepareUnlockCipher() ?: run {
            keyInvalidated = true
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(promptTitle)
            .setSubtitle(promptSubtitle)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .setNegativeButtonText(promptCancel)
            .build()
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher
                    if (authenticatedCipher == null) {
                        errorMessage = unlockFailed
                        return
                    }
                    // Chiave di sessione avvolta rovinata o KEK sparita dal Keystore (ne e' stata creata una
                    // nuova, che non apre la vecchia): doFinal lancia, meglio un messaggio che un crash.
                    if (runCatching { viewModel.completeUnlock(unlockIntent, authenticatedCipher) }.isFailure) {
                        if (unlockIntent is VaultKeyEnvelope.UnlockCipher.Unwrap) {
                            // Riprovare darebbe sempre lo stesso errore: si offre il reset.
                            errorMessage = null
                            keyInvalidated = true
                        } else {
                            errorMessage = unlockFailed
                        }
                        return
                    }
                    errorMessage = null
                    onUnlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Annullato dall'utente: resta la spiegazione, non il testo "Annulla" del sistema.
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) return
                    errorMessage = errString.toString()
                }

                override fun onAuthenticationFailed() {
                    errorMessage = authFailed
                }
            },
        )
        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(unlockIntent.cipher))
    }

    if (keyInvalidated) {
        EmptyState(
            icon = AppIcons.Lock,
            title = stringResource(R.string.vault_key_invalidated_title),
            subtitle = stringResource(
                if (resetState == VaultResetState.FAILED) R.string.vault_reset_failed else R.string.vault_key_invalidated_body,
            ),
            modifier = Modifier.fillMaxSize(),
            action = {
                Button(onClick = { confirmReset = true }, enabled = resetState != VaultResetState.RESETTING) {
                    Text(stringResource(R.string.vault_reset))
                }
            },
        )
        if (confirmReset) {
            AlertDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text(stringResource(R.string.vault_reset_title)) },
                text = { Text(stringResource(R.string.vault_reset_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.resetVault()
                        confirmReset = false
                    }) { Text(stringResource(R.string.vault_reset)) }
                },
                dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.vault_prompt_cancel)) } },
            )
        }
        return
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
