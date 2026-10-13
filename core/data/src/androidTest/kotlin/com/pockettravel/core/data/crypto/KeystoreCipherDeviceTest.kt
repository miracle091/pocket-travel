package com.pockettravel.core.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.crypto.BadPaddingException

/**
 * KeystoreCipher con l'Android Keystore vero, senza autenticazione dell'utente (quella della cassaforte, con la
 * biometria, non si prova senza una persona): cifratura e decifratura, chiavi per alias, chiave tolta, payload alterati.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCipherDeviceTest {

    private val created = mutableListOf<KeystoreCipher>()

    private fun cipher(alias: String = "pocket_travel_test_${UUID.randomUUID()}") = KeystoreCipher(alias).also { created += it }

    @After
    fun tearDown() {
        created.forEach { runCatching { it.deleteKey() } }
    }

    @Test
    fun cifraEDecifraTestoUnicodeEVuoto() {
        val cipher = cipher()
        listOf("Mario Rossi", "Zażółć gęślą jaźń 😀", "", "riga\ncon a capo e : due punti").forEach {
            assertEquals(it, cipher.decrypt(cipher.encrypt(it)))
        }
    }

    @Test
    fun ogniCifraturaUsaUnIvNuovo() {
        val cipher = cipher()
        val payloads = (1..20).map { cipher.encrypt("stesso testo") }
        assertEquals(payloads.size, payloads.toSet().size)
        assertEquals(payloads.size, payloads.map { AesGcmCodec.decodeIv(it)!!.toList() }.toSet().size)
    }

    @Test
    fun ilPayloadHaIvDi12ByteESeparatoreDuePunti() {
        val payload = cipher().encrypt("x")
        assertEquals(12, AesGcmCodec.decodeIv(payload)!!.size)
        assertEquals(1, payload.count { it == ':' })
    }

    @Test
    fun unaltraIstanzaConLoStessoAliasDecifra() {
        val alias = "pocket_travel_test_${UUID.randomUUID()}"
        val payload = cipher(alias).encrypt("persistente")
        assertEquals("persistente", cipher(alias).decrypt(payload))
    }

    @Test
    fun unAliasDiversoNonDecifra() {
        val payload = cipher().encrypt("segreto")
        assertNull(cipher().decrypt(payload))
    }

    @Test
    fun unPayloadAlteratoOMalformatoDaNull() {
        val cipher = cipher()
        val payload = cipher.encrypt("dato integro")
        assertNull(cipher.decrypt(payload.dropLast(4) + "abcd"))
        assertNull(cipher.decrypt("senza separatore"))
        assertNull(cipher.decrypt("###:###"))
        assertNull(cipher.decrypt(""))
        assertNull(cipher.decrypt(":"))
    }

    @Test
    fun conLaChiaveTolta_ilVecchioPayloadNonSiDecifraEUnoNuovoSi() {
        val cipher = cipher()
        val old = cipher.encrypt("prima")
        cipher.deleteKey()

        assertNull(cipher.decrypt(old))
        assertEquals("dopo", cipher.decrypt(cipher.encrypt("dopo")))
    }

    @Test
    fun togliereLaChiaveDiUnAliasNonToccaGliAltri() {
        val a = cipher()
        val b = cipher()
        val payload = b.encrypt("resta")
        a.encrypt("crea la chiave")
        a.deleteKey()
        assertEquals("resta", b.decrypt(payload))
    }

    @Test
    fun deleteKeyDueVolteNonLancia() {
        val cipher = cipher()
        cipher.encrypt("x")
        cipher.deleteKey()
        cipher.deleteKey()
    }

    @Test
    fun conUnIvSbagliatoLaDecifraturaFallisceConUnTagNonValido() {
        val cipher = cipher()
        val payload = cipher.encrypt("dato")
        val ciphertext = AesGcmCodec.decodeCiphertext(payload)!!
        val wrongIv = ByteArray(12)
        assertThrows(BadPaddingException::class.java) { cipher.newDecryptionCipher(wrongIv).doFinal(ciphertext) }
    }

    @Test
    fun ilCipherDiCifraturaHaUnIvDi12ByteDiversoOgniVolta() {
        val cipher = cipher()
        val first = cipher.newEncryptionCipher().iv
        val second = cipher.newEncryptionCipher().iv
        assertEquals(12, first.size)
        assertNotEquals(first.toList(), second.toList())
    }
}
