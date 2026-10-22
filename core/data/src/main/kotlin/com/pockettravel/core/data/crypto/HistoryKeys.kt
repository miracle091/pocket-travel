package com.pockettravel.core.data.crypto

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.inject.Inject

/**
 * Chiavi della cronologia dell'assistente, su due livelli perche' le conversazioni si scrivano senza sblocco e si
 * leggano solo dopo la biometria:
 * - ogni conversazione ha una chiave AES-256 sua (in memoria finche' la conversazione e' aperta), salvata avvolta con
 *   la chiave pubblica RSA della cronologia: per scrivere basta la chiave pubblica;
 * - la chiave privata della cronologia e' salvata cifrata con una chiave AES casuale, avvolta a sua volta con la chiave
 *   pubblica di una coppia RSA del Keystore la cui chiave privata richiede la biometria forte a ogni operazione
 *   ([HistoryMasterKey]). Lo sblocco e' quindi una sola operazione legata a un BiometricPrompt.CryptoObject, come la
 *   cassaforte (VaultKeyEnvelope), e apre tutta la cronologia.
 */
class HistoryKeys(private val prefs: SharedPreferences, private val masterKey: HistoryMasterKey) {
    @Inject
    constructor(preferences: PreferencesFactory) : this(preferences.open(PREFERENCES_NAME), KeystoreHistoryMasterKey())

    /** Cosa serve per aprire la cronologia. */
    sealed interface Unlock {
        /** Nessuna conversazione e' mai stata salvata: non c'e' niente da sbloccare. */
        data object Empty : Unlock

        /** Chiave del Keystore invalidata (impronte aggiunte o tolte): la cronologia non si legge piu'. */
        data object Invalidated : Unlock

        /** Cipher da avvolgere nel CryptoObject del BiometricPrompt. */
        class Ready(val cipher: Cipher) : Unlock
    }

    /** Chiave di una conversazione nuova, da tenere in memoria per scriverne i turni. */
    fun newConversationKey(): ByteArray = ByteArray(AES_KEY_BYTES).also { random.nextBytes(it) }

    /**
     * La chiave di una conversazione avvolta con la chiave pubblica della cronologia, da salvare nel database. La prima
     * volta crea le chiavi della cronologia: lento, va fuori dal main thread.
     */
    @Synchronized
    fun wrap(conversationKey: ByteArray): String {
        val publicKey = storedPublicKey()?.also {
            // Chiave del Keystore invalidata (impronte cambiate): la conversazione non si potrebbe piu' leggere, quindi
            // non si salva; la cronologia mostra che va cancellata. Cipher.init lancia gia' senza biometria.
            masterKey.newDecryptionCipher()
        } ?: createHistoryKeyPair()
        return encode(rsaCipher(Cipher.ENCRYPT_MODE, publicKey).doFinal(conversationKey))
    }

    fun prepareUnlock(): Unlock {
        if (prefs.getString(KEY_WRAPPED_SECRET, null) == null) return Unlock.Empty
        return try {
            Unlock.Ready(masterKey.newDecryptionCipher())
        } catch (_: GeneralSecurityException) {
            // KeyPermanentlyInvalidatedException (impronte cambiate) o chiave rifiutata dal Keystore.
            Unlock.Invalidated
        } catch (_: IllegalStateException) {
            // Chiave sparita dal Keystore.
            Unlock.Invalidated
        }
    }

    /**
     * Da chiamare nel callback di successo del BiometricPrompt con il Cipher del CryptoObject: restituisce chi apre le
     * chiavi delle conversazioni. [HistoryUnreadableException] se i dati salvati sono rovinati, la chiave del Keystore
     * non e' piu' quella o la chiave pubblica salvata non corrisponde a quella privata (sostituita: le conversazioni
     * scritte con quella si leggerebbero da fuori). Altri errori del Keystore escono come sono: si puo' riprovare.
     */
    fun completeUnlock(authenticatedCipher: Cipher): HistoryReader =
        HistoryReader(readPrivateKey(authenticatedCipher) ?: throw HistoryUnreadableException("Chiavi della cronologia non leggibili"))

    // La chiave privata della cronologia, o null se i dati salvati non la danno o non corrisponde alla chiave pubblica.
    private fun readPrivateKey(authenticatedCipher: Cipher): RSAPrivateCrtKey? {
        val publicKey = storedPublicKey() as? RSAPublicKey
        val secret = prefs.getString(KEY_WRAPPED_SECRET, null)?.let { unwrapSecret(authenticatedCipher, it) }
        val encodedPrivate = secret?.let { key ->
            try {
                prefs.getString(KEY_PRIVATE, null)?.let { SessionAesCipher(key).decryptBytes(decode(it)) }
            } catch (_: IllegalArgumentException) {
                null
            } finally {
                key.fill(0)
            }
        }
        val privateKey = encodedPrivate?.let(::decodePrivateKey)
        return privateKey?.takeIf { publicKey != null && it.modulus == publicKey.modulus && it.publicExponent == publicKey.publicExponent }
    }

    private fun unwrapSecret(authenticatedCipher: Cipher, stored: String): ByteArray? = try {
        authenticatedCipher.doFinal(decode(stored))
    } catch (_: BadPaddingException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decodePrivateKey(encoded: ByteArray): RSAPrivateCrtKey? = try {
        KeyFactory.getInstance(RSA).generatePrivate(PKCS8EncodedKeySpec(encoded)) as? RSAPrivateCrtKey
    } catch (_: InvalidKeySpecException) {
        null
    } finally {
        encoded.fill(0)
    }

    /** Dimentica tutte le chiavi: le conversazioni salvate non si leggono piu' (si cancellano insieme). */
    @Synchronized
    fun reset() {
        masterKey.delete()
        prefs.edit { clear() }
    }

    private fun storedPublicKey(): PublicKey? =
        prefs.getString(KEY_PUBLIC, null)?.let { KeyFactory.getInstance(RSA).generatePublic(X509EncodedKeySpec(decode(it))) }

    private fun createHistoryKeyPair(): PublicKey {
        // Una coppia del Keystore rimasta da una creazione interrotta potrebbe essere gia' invalidata: se ne fa una nuova.
        masterKey.delete()
        val pair = KeyPairGenerator.getInstance(RSA).apply { initialize(RSA_KEY_BITS, random) }.generateKeyPair()
        val secret = ByteArray(AES_KEY_BYTES).also { random.nextBytes(it) }
        try {
            val wrapped = rsaCipher(Cipher.ENCRYPT_MODE, masterKey.publicKey()).doFinal(secret)
            prefs.edit(commit = true) {
                putString(KEY_PRIVATE, encode(SessionAesCipher(secret).encryptBytes(pair.private.encoded)))
                putString(KEY_WRAPPED_SECRET, encode(wrapped))
                putString(KEY_PUBLIC, encode(pair.public.encoded))
            }
        } finally {
            secret.fill(0)
        }
        return pair.public
    }

    private val random = SecureRandom()

    companion object {
        private const val PREFERENCES_NAME = "history_keys"
        private const val KEY_PUBLIC = "public_key"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_WRAPPED_SECRET = "wrapped_secret"
        private const val AES_KEY_BYTES = 32
        private const val RSA_KEY_BITS = 2048
        internal const val RSA = "RSA"
    }
}

/** La cronologia non si puo' piu' leggere: resta solo la cancellazione. */
class HistoryUnreadableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Apre le chiavi delle conversazioni; vive solo mentre la cronologia e' sbloccata. */
class HistoryReader(private val privateKey: PrivateKey) {
    /** La chiave della conversazione, o null se [wrapped] non e' stata avvolta con questa cronologia. */
    fun conversationKey(wrapped: String): ByteArray? = try {
        rsaCipher(Cipher.DECRYPT_MODE, privateKey).doFinal(decode(wrapped))
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Coppia RSA da cui dipende lo sblocco della cronologia: la chiave privata si usa solo dopo la biometria. */
interface HistoryMasterKey {
    /** Chiave pubblica, usabile senza sblocco; crea la coppia se manca. */
    fun publicKey(): PublicKey

    /** Cipher di decifratura da autorizzare con il BiometricPrompt; lancia se la chiave e' invalidata o manca. */
    fun newDecryptionCipher(): Cipher

    fun delete()
}

// Nel Keystore l'MGF1 di OAEP e' sempre SHA-1, anche con SHA-256 come digest: lo stesso parametro vale per cifrare e decifrare.
private val OAEP_SPEC = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
private const val RSA_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

private fun rsaCipher(mode: Int, key: java.security.Key): Cipher = Cipher.getInstance(RSA_OAEP).apply { init(mode, key, OAEP_SPEC) }

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

/**
 * Coppia RSA nel Keystore: la chiave privata non esce mai e richiede la biometria forte a ogni operazione (dall'API 30
 * AUTH_BIOMETRIC_STRONG, sotto validita' -1, come KeystoreCipher); si invalida se cambiano le impronte. La chiave
 * pubblica si copia in una chiave software, perche' le operazioni con la chiave pubblica non passano dal Keystore.
 */
private class KeystoreHistoryMasterKey : HistoryMasterKey {
    override fun publicKey(): PublicKey {
        val keyStore = keyStore()
        if (!keyStore.containsAlias(ALIAS)) generate()
        val certificate = checkNotNull(keyStore().getCertificate(ALIAS)) { "Chiave della cronologia non disponibile" }
        return KeyFactory.getInstance(HistoryKeys.RSA).generatePublic(X509EncodedKeySpec(certificate.publicKey.encoded))
    }

    override fun newDecryptionCipher(): Cipher {
        val privateKey = checkNotNull(keyStore().getKey(ALIAS, null) as? PrivateKey) { "Chiave della cronologia non disponibile" }
        return rsaCipher(Cipher.DECRYPT_MODE, privateKey)
    }

    override fun delete() {
        keyStore().deleteEntry(ALIAS)
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Suppress("DEPRECATION")
    private fun generate() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(RSA_KEY_BITS)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setUserAuthenticationRequired(true)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        } else {
                            setUserAuthenticationValidityDurationSeconds(-1)
                        }
                    }
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            generateKeyPair()
        }
    }

    private companion object {
        const val ALIAS = "pocket_travel_history_master"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val RSA_KEY_BITS = 2048
    }
}
