package com.pockettravel.core.data.crypto

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAesCipherTest {

    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `encrypt poi decrypt ricostruisce il testo originale`() {
        val cipher = SessionAesCipher(randomKey())

        val payload = cipher.encrypt("Mario Rossi")

        assertEquals("Mario Rossi", cipher.decrypt(payload))
    }

    @Test
    fun `encryptBytes poi decryptBytes ricostruisce i byte originali`() {
        val cipher = SessionAesCipher(randomKey())
        val original = ByteArray(4096) { (it % 256).toByte() }

        val payload = cipher.encryptBytes(original)

        assertEquals(true, original.contentEquals(cipher.decryptBytes(payload)))
    }

    @Test
    fun `encrypt genera un ciphertext diverso ad ogni chiamata (IV nuovo)`() {
        val cipher = SessionAesCipher(randomKey())

        val first = cipher.encrypt("stesso testo")
        val second = cipher.encrypt("stesso testo")

        assertNotEquals(first, second)
    }

    @Test
    fun `decrypt con una chiave diversa restituisce null`() {
        val payload = SessionAesCipher(randomKey()).encrypt("segreto")

        assertNull(SessionAesCipher(randomKey()).decrypt(payload))
    }

    @Test
    fun `decryptBytes con una chiave diversa restituisce null`() {
        val payload = SessionAesCipher(randomKey()).encryptBytes(byteArrayOf(1, 2, 3))

        assertNull(SessionAesCipher(randomKey()).decryptBytes(payload))
    }

    @Test
    fun `decrypt su un payload manomesso restituisce null invece di lanciare`() {
        val key = randomKey()
        val payload = SessionAesCipher(key).encrypt("dato integro")
        val tampered = payload.dropLast(4) + "abcd"

        assertNull(SessionAesCipher(key).decrypt(tampered))
    }

    @Test
    fun `decrypt su un payload non valido restituisce null invece di lanciare`() {
        assertNull(SessionAesCipher(randomKey()).decrypt("non e' un payload cifrato"))
    }

    @Test
    fun `decryptBytes su un payload vuoto restituisce null invece di lanciare`() {
        assertNull(SessionAesCipher(randomKey()).decryptBytes(ByteArray(0)))
    }
}
