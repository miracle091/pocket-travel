package com.pockettravel.feature.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class TransitRealtimeClientTest {
    // FeedMessage minimo: header { gtfs_realtime_version: "1.0", incrementality: FULL_DATASET }.
    private val protobuf = byteArrayOf(0x0A, 0x0D, 0x0A, 0x03, 0x31, 0x2E, 0x30, 0x10, 0x00)
    private val encoded = Base64.getEncoder().encodeToString(protobuf)

    @Test
    fun `il corpo base64 di Adelaide si decodifica`() {
        assertEquals("Cg0KAzEuMBAA", encoded)
        assertArrayEquals(protobuf, unwrapBase64Body(encoded.toByteArray()))
    }

    @Test
    fun `il base64 tra virgolette, con spazi e a capo si decodifica`() {
        assertArrayEquals(protobuf, unwrapBase64Body("\"$encoded\"".toByteArray()))
        assertArrayEquals(protobuf, unwrapBase64Body("  \"Cg0K\nAzEu\r\nMBAA\"\n".toByteArray()))
        assertArrayEquals(protobuf, unwrapBase64Body("$encoded\n".toByteArray()))
    }

    @Test
    fun `un protobuf vero resta com'e'`() {
        assertArrayEquals(protobuf, unwrapBase64Body(protobuf))
        // Anche se per caso il resto sembrasse base64: conta il primo byte.
        val tricky = byteArrayOf(0x0A) + "ABCD".toByteArray()
        assertArrayEquals(tricky, unwrapBase64Body(tricky))
    }

    @Test
    fun `un corpo che non e' base64 resta com'e'`() {
        val html = "<html>errore</html>".toByteArray()
        assertArrayEquals(html, unwrapBase64Body(html))
        val json = "{\"a\":1}".toByteArray()
        assertArrayEquals(json, unwrapBase64Body(json))
        // Alfabeto giusto ma non decodificabile (padding sbagliato): invariato.
        val broken = "A=B=C".toByteArray()
        assertArrayEquals(broken, unwrapBase64Body(broken))
        val empty = ByteArray(0)
        assertArrayEquals(empty, unwrapBase64Body(empty))
        val blank = "  \n".toByteArray()
        assertArrayEquals(blank, unwrapBase64Body(blank))
    }
}
