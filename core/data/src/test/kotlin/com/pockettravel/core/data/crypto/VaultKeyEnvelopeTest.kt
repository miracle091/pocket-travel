package com.pockettravel.core.data.crypto

import android.content.SharedPreferences
import com.pockettravel.core.data.PreferencesFactory
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.data.testing.InMemorySharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `una chiave avvolta rovinata nel file da' null, non un crash`() {
        val files = InMemoryPreferencesFactory()
        files.open("vault_key_envelope_v2").edit().putString("wrapped_dek", "non-base64!:abc").commit()

        assertNull(VaultKeyEnvelope(files).prepareUnlockCipher())
    }

    @Test
    fun `svolgere senza una chiave avvolta e' un errore`() {
        val envelope = VaultKeyEnvelope(InMemoryPreferencesFactory())
        val cipher = decryptionCipher(ByteArray(12))

        val failed = runCatching { envelope.completeUnlock(VaultKeyEnvelope.UnlockCipher.Unwrap(cipher), cipher) }

        assertTrue(failed.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `la chiave avvolta si scrive subito, non con apply che il processo puo' perdere`() {
        // apply() scrive su disco dopo: se il processo muore prima, il prossimo sblocco crea una DEK nuova
        // e i documenti cifrati con la vecchia spariscono. Qui apply() non scrive mai.
        val disk = InMemoryPreferencesFactory()
        val applyLost = PreferencesFactory { name ->
            val prefs = disk.open(name)
            object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by prefs.edit() {
                    override fun apply() = Unit
                }
            }
        }
        val cipher = encryptionCipher()

        VaultKeyEnvelope(applyLost).completeUnlock(VaultKeyEnvelope.UnlockCipher.GenerateAndWrap(cipher), cipher)

        assertNotNull(disk.open("vault_key_envelope_v2").getString("wrapped_dek", null))
    }

    @Test
    fun `una chiave avvolta che non si scrive su disco non sblocca la cassaforte`() {
        // Come SharedPreferences di Android con il disco pieno: il valore entra in memoria, commit() da' false. Una DEK
        // usata senza essere sul disco cifrerebbe documenti che dopo il riavvio non si aprono piu'.
        val prefs = InMemorySharedPreferences().apply { diskFull = true }
        val cipher = encryptionCipher()

        val failed = runCatching {
            VaultKeyEnvelope { prefs }.completeUnlock(VaultKeyEnvelope.UnlockCipher.GenerateAndWrap(cipher), cipher)
        }

        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        assertNull(prefs.getString("wrapped_dek", null))
    }
}
