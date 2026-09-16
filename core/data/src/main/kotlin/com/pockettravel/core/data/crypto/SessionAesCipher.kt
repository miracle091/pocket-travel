package com.pockettravel.core.data.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.GeneralSecurityException

// Stessa cifratura AES-256-GCM di KeystoreCipher, ma con una SecretKey in RAM (mai nel Keystore)
// invece che per-alias: usata per la chiave di sessione del vault passaporti, sbloccata una volta
// tramite VaultKeyEnvelope e tenuta in memoria finche' la schermata resta sbloccata (vedi
// PassportRepository.unlock/lock).
class SessionAesCipher(key: ByteArray) {
    private val secretKey = SecretKeySpec(key, "AES")

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(AesGcmCodec.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return AesGcmCodec.encode(cipher.iv, ciphertext)
    }

    fun decrypt(payload: String): String? {
        val iv = AesGcmCodec.decodeIv(payload) ?: return null
        val ciphertext = AesGcmCodec.decodeCiphertext(payload) ?: return null
        return try {
            val cipher = Cipher.getInstance(AesGcmCodec.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(AesGcmCodec.TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // Stesso schema di encrypt/decrypt ma su byte grezzi (foto), per non pagare il +33% di
    // Base64 su file che possono essere megabyte: IV impacchettato davanti al ciphertext,
    // prefissato dalla sua lunghezza (un byte, sempre <=255 per un IV GCM) invece di un
    // separatore testuale.
    fun encryptBytes(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AesGcmCodec.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(plainBytes)
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
    }

    fun decryptBytes(payload: ByteArray): ByteArray? {
        if (payload.isEmpty()) return null
        return try {
            val ivLength = payload[0].toInt() and 0xFF
            val iv = payload.copyOfRange(1, 1 + ivLength)
            val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)
            val cipher = Cipher.getInstance(AesGcmCodec.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(AesGcmCodec.TAG_LENGTH_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}
