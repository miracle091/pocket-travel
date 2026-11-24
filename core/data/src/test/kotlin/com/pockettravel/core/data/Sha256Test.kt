package com.pockettravel.core.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256Test {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `l'impronta dei byte e' quella nota in esadecimale minuscolo`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "abc".toByteArray().sha256Hex())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ByteArray(0).sha256Hex())
    }

    @Test
    fun `l'impronta di un file piu' grande di un blocco e' quella dei suoi byte`() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val file = tmp.newFile().apply { writeBytes(bytes) }
        assertEquals(bytes.sha256Hex(), file.sha256Hex())
    }
}
