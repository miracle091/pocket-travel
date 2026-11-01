package com.pockettravel.core.sync

import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ReadBodyAtMostTest {

    @Test
    fun `un corpo entro il tetto si legge per intero`() {
        assertEquals(10, ByteArray(10).toResponseBody(null).readBodyAtMost(10).size)
    }

    @Test
    fun `un corpo oltre il tetto con lunghezza dichiarata e' rifiutato`() {
        assertThrows(IOException::class.java) { ByteArray(11).toResponseBody(null).readBodyAtMost(10) }
    }

    @Test
    fun `un corpo oltre il tetto senza lunghezza dichiarata e' rifiutato`() {
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = Buffer().write(ByteArray(50))
        }
        assertThrows(IOException::class.java) { body.readBodyAtMost(10) }
    }
}
