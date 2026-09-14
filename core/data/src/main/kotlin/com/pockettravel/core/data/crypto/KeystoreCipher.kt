package com.pockettravel.core.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Cifratura AES-256-GCM con chiave Android Keystore non esportabile — generalizzata da quella
// che era una classe privata dentro feature/ai/AiSettingsStore (un solo segreto, un solo alias).
// Qui una chiave cifra N record indipendenti: ogni encrypt() genera un IV nuovo (requisito GCM
// gia' soddisfatto da Cipher.init), quindi non serve una chiave per record.
//
// requireUserAuthentication=true (usato dal vault passaporti, non dalla chiave API AI) lega
// l'usabilita' della chiave allo stato di autenticazione del dispositivo a livello di
// crittografia, non solo a livello di UI: la chiave diventa inutilizzabile se la biometria viene
// disabilitata o ri-registrata (setInvalidatedByBiometricEnrollment), coerente con la sensibilita'
// maggiore dei dati di un passaporto rispetto a una chiave API.
class KeystoreCipher(
    private val keyAlias: String,
    private val requireUserAuthentication: Boolean = false,
) {
    fun encrypt(plainText: String): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val encodedIv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val encodedCiphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            return "$encodedIv:$encodedCiphertext"
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("Impossibile cifrare il valore per l'alias $keyAlias", error)
        }
    }

    fun decrypt(payload: String): String? {
        val separatorIndex = payload.indexOf(':')
        if (separatorIndex == -1) return null
        return try {
            val iv = Base64.decode(payload.substring(0, separatorIndex), Base64.NO_WRAP)
            val ciphertext = Base64.decode(payload.substring(separatorIndex + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // setUserAuthenticationValidityDurationSeconds e' deprecata dall'API 30 in favore di
    // setUserAuthenticationParameters(timeout, authTypes) — non usabile qui perche' minSdk=26.
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
                                // Finestra dopo l'autenticazione biometrica/PIN entro cui la chiave resta
                                // utilizzabile senza un nuovo prompt legato a un CryptoObject — coerente
                                // con lo sblocco "a livello di schermata" del vault (vedi PassportVaultScreen):
                                // se scade mentre la schermata e' aperta, l'operazione fallisce e va
                                // ripetuto l'accesso alla schermata (limite noto, accettato per la v1).
                                setUserAuthenticationRequired(true)
                                setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
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
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val AUTH_VALIDITY_SECONDS = 300
    }
}
