package com.pockettravel.core.data.crypto

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * La parte della cassaforte che non tocca il Keystore: avvolgere e svolgere la chiave di sessione con un Cipher
 * qualunque (qui una chiave AES in software al posto della KEK) e il file in cui sta quella avvolta.
 */
class VaultKeyEnvelopeTest {

    private val kek: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun encryptionCipher() = Cipher.getInstance(AesGcmCodec.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, kek) }

    private fun decryptionCipher(iv: ByteArray) = Cipher.getInstance(AesGcmCodec.TRANSFORMATION)
        .apply { init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(AesGcmCodec.TAG_LENGTH_BITS, iv)) }

    @Test
    fun `la chiave avvolta sta nel file vault_key_envelope_v2 e si svolge con la KEK`() {
        val files = InMemoryPreferencesFactory()
        val cipher = encryptionCipher()

        val dek = VaultKeyEnvelope(files).completeUnlock(VaultKeyEnvelope.UnlockCipher.GenerateAndWrap(cipher), cipher)
        val stored = files.open("vault_key_envelope_v2").getString("wrapped_dek", null)!!
        val unwrapped = decryptionCipher(AesGcmCodec.decodeIv(stored)!!).doFinal(AesGcmCodec.decodeCiphertext(stored)!!)

        assertEquals(32, dek.size)
        assertArrayEquals(dek, unwrapped)
    }

    @Test
    fun `una nuova apertura della cassaforte svolge la chiave scritta dalla prima`() {
        val files = InMemoryPreferencesFactory()
        val first = encryptionCipher()
        val dek = VaultKeyEnvelope(files).completeUnlock(VaultKeyEnvelope.UnlockCipher.GenerateAndWrap(first), first)

        val stored = files.open("vault_key_envelope_v2").getString("wrapped_dek", null)!!
        val second = decryptionCipher(AesGcmCodec.decodeIv(stored)!!)
        val unwrapped = VaultKeyEnvelope(files).completeUnlock(VaultKeyEnvelope.UnlockCipher.Unwrap(second), second)

        assertArrayEquals(dek, unwrapped)
    }

    @Test
    fun `svolgere senza una chiave avvolta e' un errore`() {
        val envelope = VaultKeyEnvelope(InMemoryPreferencesFactory())
        val cipher = decryptionCipher(ByteArray(12))

        val failed = runCatching { envelope.completeUnlock(VaultKeyEnvelope.UnlockCipher.Unwrap(cipher), cipher) }

        assertTrue(failed.exceptionOrNull() is IllegalStateException)
    }
}
