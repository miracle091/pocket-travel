package com.pockettravel.core.data.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AesGcmCodecTest {

    @Test
    fun `encode poi decodeIv e decodeCiphertext ricostruiscono iv e ciphertext originali`() {
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val ciphertext = byteArrayOf(9, 8, 7, 6, 5, -1, -2, -3)

        val payload = AesGcmCodec.encode(iv, ciphertext)

        assertEquals(true, iv.contentEquals(AesGcmCodec.decodeIv(payload)))
        assertEquals(true, ciphertext.contentEquals(AesGcmCodec.decodeCiphertext(payload)))
    }

    @Test
    fun `encode produce una singola riga senza a-capo`() {
        val payload = AesGcmCodec.encode(ByteArray(12) { it.toByte() }, ByteArray(64) { it.toByte() })

        assertEquals(false, payload.contains('\n'))
    }

    @Test
    fun `decodeIv e decodeCiphertext restituiscono null senza separatore`() {
        assertNull(AesGcmCodec.decodeIv("nessun-separatore"))
        assertNull(AesGcmCodec.decodeCiphertext("nessun-separatore"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decodeIv propaga IllegalArgumentException su base64 invalido`() {
        AesGcmCodec.decodeIv("###:validsegment")
    }
}
