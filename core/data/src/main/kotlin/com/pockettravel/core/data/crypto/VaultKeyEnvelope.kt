package com.pockettravel.core.data.crypto

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.inject.Inject

// Avvolge una DEK (data encryption key) di sessione, 256 bit casuali, con una KEK Keystore che
// richiede autenticazione per-operazione (authValiditySeconds = 0): e' l'unica operazione
// crittografica del vault passaporti legata a un BiometricPrompt.CryptoObject, quindi soddisfa
// la verifica CodeQL java/android/insecure-local-authentication. I singoli record restano cifrati
// con la DEK in memoria (SessionAesCipher, vedi PassportRepository), senza un nuovo prompt a ogni
// operazione: un solo unlock per apertura della schermata, come nella UX precedente.
class VaultKeyEnvelope @Inject constructor(@ApplicationContext context: Context) {
    private val kek = KeystoreCipher(keyAlias = KEK_ALIAS, requireUserAuthentication = true, authValiditySeconds = 0)
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    sealed interface UnlockCipher {
        val cipher: Cipher

        data class GenerateAndWrap(override val cipher: Cipher) : UnlockCipher
        data class Unwrap(override val cipher: Cipher) : UnlockCipher
    }

    // Da chiamare prima di aprire il BiometricPrompt: prepara il Cipher da avvolgere in un
    // CryptoObject, senza eseguire ancora nessuna operazione crittografica.
    fun prepareUnlockCipher(): UnlockCipher {
        val wrappedIv = AesGcmCodec.decodeIv(prefs.getString(KEY_WRAPPED_DEK, null) ?: "")
        return if (wrappedIv != null) {
            UnlockCipher.Unwrap(kek.newDecryptionCipher(wrappedIv))
        } else {
            UnlockCipher.GenerateAndWrap(kek.newEncryptionCipher())
        }
    }

    // Da chiamare nel callback di successo del BiometricPrompt, con il Cipher del CryptoObject
    // restituito dal risultato (l'unico che il framework garantisce autorizzato).
    fun completeUnlock(intent: UnlockCipher, authenticatedCipher: Cipher): ByteArray =
        when (intent) {
            is UnlockCipher.GenerateAndWrap -> {
                val dek = ByteArray(DEK_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
                val wrapped = authenticatedCipher.doFinal(dek)
                prefs.edit { putString(KEY_WRAPPED_DEK, AesGcmCodec.encode(authenticatedCipher.iv, wrapped)) }
                dek
            }
            is UnlockCipher.Unwrap -> {
                val ciphertext = AesGcmCodec.decodeCiphertext(prefs.getString(KEY_WRAPPED_DEK, null) ?: "")
                    ?: error("Chiave di sessione avvolta mancante nonostante Unwrap")
                authenticatedCipher.doFinal(ciphertext)
            }
        }

    private companion object {
        const val KEK_ALIAS = "pocket_travel_vault_kek_v2"
        const val PREFERENCES_NAME = "vault_key_envelope_v2"
        const val KEY_WRAPPED_DEK = "wrapped_dek"
        const val DEK_SIZE_BYTES = 32
    }
}
