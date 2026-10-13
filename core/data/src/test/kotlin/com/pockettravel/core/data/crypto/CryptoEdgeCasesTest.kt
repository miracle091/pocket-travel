package com.pockettravel.core.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** Alterazioni, formati e limiti delle chiavi per il codec e la cifratura di sessione della cassaforte. */
class CryptoEdgeCasesTest {

    private fun randomKey(size: Int = 32): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    // Il payload di testo e' "iv:ciphertext" in Base64; qui si altera un byte del ciphertext decodificato.
    private fun flipCiphertextByte(payload: String, index: Int): String {
        val iv = checkNotNull(AesGcmCodec.decodeIv(payload))
        val ciphertext = checkNotNull(AesGcmCodec.decodeCiphertext(payload)).copyOf()
        ciphertext[index] = (ciphertext[index].toInt() xor 0x01).toByte()
        return AesGcmCodec.encode(iv, ciphertext)
    }

    // AesGcmCodec

    @Test
    fun `payload vuoto o con il solo separatore non e' un errore ma dati vuoti`() {
        assertNull(AesGcmCodec.decodeIv(""))
        assertNull(AesGcmCodec.decodeCiphertext(""))
        assertEquals(0, checkNotNull(AesGcmCodec.decodeIv(":")).size)
        assertEquals(0, checkNotNull(AesGcmCodec.decodeCiphertext(":")).size)
    }

    @Test
    fun `i due punti in piu' finiscono nel ciphertext e Base64 li rifiuta`() {
        assertThrows(IllegalArgumentException::class.java) { AesGcmCodec.decodeCiphertext("AAAA:BBBB:CCCC") }
        assertEquals(3, checkNotNull(AesGcmCodec.decodeIv("AAAA:BBBB:CCCC")).size)
    }

    @Test
    fun `encode con iv e ciphertext vuoti da solo il separatore`() {
        val payload = AesGcmCodec.encode(ByteArray(0), ByteArray(0))
        assertEquals(":", payload)
    }

    @Test
    fun `i parametri di AES-GCM sono quelli attesi`() {
        assertEquals("AES/GCM/NoPadding", AesGcmCodec.TRANSFORMATION)
        assertEquals(128, AesGcmCodec.TAG_LENGTH_BITS)
    }

    // SessionAesCipher: testo

    @Test
    fun `testo vuoto, unicode e lungo si ricostruiscono`() {
        val cipher = SessionAesCipher(randomKey())
        listOf("", "Zażółć gęślą jaźń 😀", "a:b:c", "x".repeat(100_000)).forEach {
            assertEquals(it.take(20), it, cipher.decrypt(cipher.encrypt(it)))
        }
    }

    @Test
    fun `il payload di testo ha un iv di 12 byte e un tag di 16 dopo il testo`() {
        val cipher = SessionAesCipher(randomKey())
        val payload = cipher.encrypt("abcde")
        assertEquals(12, checkNotNull(AesGcmCodec.decodeIv(payload)).size)
        assertEquals(5 + 16, checkNotNull(AesGcmCodec.decodeCiphertext(payload)).size)
    }

    @Test
    fun `cambiare un bit del ciphertext o dell'iv fa fallire la decifratura`() {
        val cipher = SessionAesCipher(randomKey())
        val payload = cipher.encrypt("dato integro")
        val size = checkNotNull(AesGcmCodec.decodeCiphertext(payload)).size
        for (index in listOf(0, size / 2, size - 1)) {
            assertNull("byte $index", cipher.decrypt(flipCiphertextByte(payload, index)))
        }
        val iv = checkNotNull(AesGcmCodec.decodeIv(payload)).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(cipher.decrypt(AesGcmCodec.encode(iv, checkNotNull(AesGcmCodec.decodeCiphertext(payload)))))
    }

    @Test
    fun `un ciphertext troncato o senza tag non si decifra`() {
        val cipher = SessionAesCipher(randomKey())
        val payload = cipher.encrypt("dato integro")
        val iv = checkNotNull(AesGcmCodec.decodeIv(payload))
        val ciphertext = checkNotNull(AesGcmCodec.decodeCiphertext(payload))
        assertNull(cipher.decrypt(AesGcmCodec.encode(iv, ciphertext.copyOf(ciphertext.size - 1))))
        assertNull(cipher.decrypt(AesGcmCodec.encode(iv, ByteArray(0))))
        assertNull(cipher.decrypt(AesGcmCodec.encode(iv, ByteArray(8))))
    }

    @Test
    fun `un iv vuoto o di lunghezza insolita non si decifra`() {
        val cipher = SessionAesCipher(randomKey())
        val ciphertext = checkNotNull(AesGcmCodec.decodeCiphertext(cipher.encrypt("x")))
        assertNull(cipher.decrypt(AesGcmCodec.encode(ByteArray(0), ciphertext)))
        assertNull(cipher.decrypt(AesGcmCodec.encode(ByteArray(11), ciphertext)))
    }

    @Test
    fun `gli iv di mille cifrature sono tutti diversi`() {
        val cipher = SessionAesCipher(randomKey())
        val ivs = (1..1000).map { checkNotNull(AesGcmCodec.decodeIv(cipher.encrypt("x"))).toList() }
        assertEquals(1000, ivs.toSet().size)
    }

    @Test
    fun `chiavi da 16, 24 e 32 byte funzionano, ma quella vuota e' rifiutata subito`() {
        listOf(16, 24, 32).forEach {
            val cipher = SessionAesCipher(randomKey(it))
            assertEquals("chiave da $it", "ciao", cipher.decrypt(cipher.encrypt("ciao")))
        }
        assertThrows(IllegalArgumentException::class.java) { SessionAesCipher(ByteArray(0)) }
    }

    @Test
    fun `una chiave di lunghezza non valida fallisce alla prima cifratura`() {
        val cipher = SessionAesCipher(randomKey(10))
        assertThrows(java.security.GeneralSecurityException::class.java) { cipher.encrypt("x") }
        assertNull(cipher.decrypt(AesGcmCodec.encode(ByteArray(12), ByteArray(32))))
    }

    @Test
    fun `la stessa chiave in due istanze decifra a vicenda`() {
        val key = randomKey()
        val payload = SessionAesCipher(key).encrypt("condiviso")
        assertEquals("condiviso", SessionAesCipher(key.copyOf()).decrypt(payload))
    }

    // SessionAesCipher: byte

    @Test
    fun `byte vuoti e grandi si ricostruiscono`() {
        val cipher = SessionAesCipher(randomKey())
        assertArrayEquals(ByteArray(0), cipher.decryptBytes(cipher.encryptBytes(ByteArray(0))))
        val big = ByteArray(2_000_000) { (it * 31).toByte() }
        assertArrayEquals(big, cipher.decryptBytes(cipher.encryptBytes(big)))
    }

    @Test
    fun `il formato dei byte e' lunghezza dell'iv, iv, ciphertext e tag`() {
        val cipher = SessionAesCipher(randomKey())
        val payload = cipher.encryptBytes(ByteArray(100))
        assertEquals(12, payload[0].toInt())
        assertEquals(1 + 12 + 100 + 16, payload.size)
    }

    @Test
    fun `un byte alterato in qualunque parte fa fallire la decifratura`() {
        val cipher = SessionAesCipher(randomKey())
        val payload = cipher.encryptBytes(ByteArray(64) { it.toByte() })
        for (index in listOf(1, 12, 13, payload.size / 2, payload.size - 1)) {
            val tampered = payload.copyOf().also { it[index] = (it[index].toInt() xor 0x80).toByte() }
            assertNull("byte $index", cipher.decryptBytes(tampered))
        }
    }

    @Test
    fun `la lunghezza dell'iv falsa o fuori scala non lancia`() {
        val cipher = SessionAesCipher(randomKey())
        val payload = cipher.encryptBytes(ByteArray(16))
        listOf(0, 1, 11, 13, 255).forEach { length ->
            val tampered = payload.copyOf().also { it[0] = length.toByte() }
            assertNull("lunghezza $length", cipher.decryptBytes(tampered))
        }
        assertNull(cipher.decryptBytes(byteArrayOf(12)))
        assertNull(cipher.decryptBytes(byteArrayOf(12, 1, 2, 3)))
        assertNull(cipher.decryptBytes(byteArrayOf(-1)))
    }

    @Test
    fun `un payload troncato non si decifra`() {
        val cipher = SessionAesCipher(randomKey())
        val payload = cipher.encryptBytes(ByteArray(32))
        listOf(1, 13, payload.size - 16, payload.size - 1).forEach {
            assertNull("troncato a $it", cipher.decryptBytes(payload.copyOf(it)))
        }
    }

    @Test
    fun `testo e byte non si scambiano`() {
        val cipher = SessionAesCipher(randomKey())
        assertNull(cipher.decryptBytes(cipher.encrypt("testo").toByteArray()))
        val bytes = Base64.getEncoder().encodeToString(cipher.encryptBytes(byteArrayOf(1, 2, 3)))
        assertNull(cipher.decrypt(bytes))
    }

    @Test
    fun `due cifrature degli stessi byte sono diverse`() {
        val cipher = SessionAesCipher(randomKey())
        val data = ByteArray(32)
        assertNotEquals(cipher.encryptBytes(data).toList(), cipher.encryptBytes(data).toList())
    }
}
