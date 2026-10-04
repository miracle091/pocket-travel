package com.pockettravel.core.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Cifratura AES-256-GCM con chiave Android Keystore non esportabile, condivisa dai moduli che
// cifrano dati locali. Una chiave cifra N record indipendenti: ogni encrypt() genera un IV nuovo
// (requisito GCM gia' soddisfatto da Cipher.init), quindi non serve una chiave per record.
//
// requireUserAuthentication=true lega l'usabilita' della chiave allo stato di autenticazione del
// dispositivo a livello di crittografia, non solo a livello di UI: la chiave diventa inutilizzabile
// se la biometria viene disabilitata o ri-registrata (setInvalidatedByBiometricEnrollment).
//
// authValiditySeconds controlla la finestra: > 0 e' una chiave "time-bound" (utilizzabile per N
// secondi dopo un qualunque sblocco biometrico/PIN del dispositivo, senza legare l'operazione a un
// preciso BiometricPrompt.CryptoObject); 0 (o meno) richiede invece l'autenticazione per-operazione, quindi
// la chiave e' utilizzabile solo passando il Cipher qui creato dentro un BiometricPrompt.CryptoObject
// (vedi VaultKeyEnvelope, che usa questa modalita' per la KEK che avvolge la chiave di sessione).
class KeystoreCipher(
    private val keyAlias: String,
    private val requireUserAuthentication: Boolean = false,
    private val authValiditySeconds: Int = AUTH_VALIDITY_SECONDS,
) {
    fun encrypt(plainText: String): String {
        try {
            val cipher = newEncryptionCipher()
            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return AesGcmCodec.encode(cipher.iv, ciphertext)
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Impossibile cifrare il valore per l'alias $keyAlias", error)
        }
    }

    fun decrypt(payload: String): String? {
        return try {
            val iv = AesGcmCodec.decodeIv(payload) ?: return null
            val ciphertext = AesGcmCodec.decodeCiphertext(payload) ?: return null
            val cipher = newDecryptionCipher(iv)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // Cipher pronto per cifrare, con IV generato automaticamente da Cipher.init (leggibile poi
    // da cipher.iv). Esposto per poter essere avvolto in un BiometricPrompt.CryptoObject quando
    // la chiave richiede autenticazione per-operazione (authValiditySeconds == 0).
    fun newEncryptionCipher(): Cipher {
        val cipher = Cipher.getInstance(AesGcmCodec.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher
    }

    // Come sopra ma per decifrare: l'IV va fornito (e' stato salvato insieme al ciphertext).
    fun newDecryptionCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(AesGcmCodec.TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(AesGcmCodec.TAG_LENGTH_BITS, iv))
        return cipher
    }

    /** Toglie la chiave dal Keystore: la prossima operazione ne genera una nuova. */
    fun deleteKey() {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
    }

    // Autenticazione per-operazione (authValiditySeconds == 0), tramite CryptoObject: dall'API 30
    // setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG), che la lega alla sola biometria forte.
    // Sotto l'API 30 (minSdk=26) esiste solo setUserAuthenticationValidityDurationSeconds, dove la
    // per-operazione si chiede con -1: 0 non e' documentato come tale. I parametri sono fissati alla
    // creazione: una chiave gia' presente nel Keystore resta com'e', non viene rigenerata.
    @Suppress("DEPRECATION")
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .apply {
                            if (requireUserAuthentication) {
                                setUserAuthenticationRequired(true)
                                if (authValiditySeconds > 0) {
                                    setUserAuthenticationValidityDurationSeconds(authValiditySeconds)
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                                } else {
                                    setUserAuthenticationValidityDurationSeconds(-1)
                                }
                                setInvalidatedByBiometricEnrollment(true)
                            }
                        }
                        .build(),
                )
                generateKey()
            }
        }
        return (keyStore.getKey(keyAlias, null) as? SecretKey)
            ?: error("Chiave Keystore non disponibile per l'alias $keyAlias")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val AUTH_VALIDITY_SECONDS = 300
    }
}
