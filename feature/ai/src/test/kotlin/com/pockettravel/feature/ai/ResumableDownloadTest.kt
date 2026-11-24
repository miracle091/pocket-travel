package com.pockettravel.feature.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class ResumableDownloadTest {
    private val server = MockWebServer()
    private val dir: File = createTempDirectory("resumable").toFile()
    private val part = File(dir, "file.part")
    private val data = ByteArray(1000) { it.toByte() }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private suspend fun download(expected: Long = data.size.toLong(), progress: MutableList<Pair<Long, Long>> = mutableListOf()) =
        resumableDownload(OkHttpClient(), server.url("/f").toString(), part, expected, "prova") { done, total ->
            progress += done to total
        }

    @Test
    fun `scarica da zero e riporta l'avanzamento finale`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(data)))
        val progress = mutableListOf<Pair<Long, Long>>()
        download(progress = progress)
        assertTrue(data.contentEquals(part.readBytes()))
        assertEquals(1000L to 1000L, progress.last())
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `riprende un parziale con Range e lo completa`() = runBlocking {
        part.writeBytes(data.copyOfRange(0, 400))
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 400-999/1000")
                .setBody(Buffer().write(data.copyOfRange(400, 1000))),
        )
        val progress = mutableListOf<Pair<Long, Long>>()
        download(progress = progress)
        assertEquals("bytes=400-", server.takeRequest().getHeader("Range"))
        assertTrue(data.contentEquals(part.readBytes()))
        assertEquals(1000L to 1000L, progress.last())
    }

    @Test
    fun `se il server risponde 200 al Range riparte da zero`() = runBlocking {
        part.writeBytes(ByteArray(400) { 7 })
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(data)))
        download()
        assertTrue(data.contentEquals(part.readBytes()))
    }

    @Test
    fun `un 206 con Content-Range diverso e' un errore e il parziale resta`() = runBlocking {
        part.writeBytes(data.copyOfRange(0, 400))
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-999/1000").setBody(Buffer().write(data)),
        )
        try {
            download()
            fail("atteso IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            assertEquals(400L, part.length())
        }
    }

    @Test
    fun `un 416 con un parziale lo cancella e si puo' ritentare`() = runBlocking {
        part.writeBytes(data.copyOfRange(0, 400))
        server.enqueue(MockResponse().setResponseCode(416))
        try {
            download()
            fail("atteso IOException")
        } catch (_: IOException) {
            // non permanente: ModelDownloadFailedException non e' una IOException e farebbe fallire il test
        }
        assertFalse(part.exists())
    }

    @Test
    fun `un 404 e' un errore permanente, il parziale resta`() = runBlocking {
        part.writeBytes(data.copyOfRange(0, 400))
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            download()
            fail("atteso ModelDownloadFailedException")
        } catch (_: ModelDownloadFailedException) {
            assertEquals(400L, part.length())
        }
    }

    @Test
    fun `408, 429 e 500 non sono permanenti`() = runBlocking {
        for (code in listOf(408, 429, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))
            try {
                download()
                fail("atteso IOException per $code")
            } catch (_: IOException) {
                // non permanente, come sopra
            }
        }
    }

    @Test
    fun `piu' byte del previsto interrompono e cancellano il parziale`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(data)))
        try {
            download(expected = 500)
            fail("atteso ModelDownloadFailedException")
        } catch (_: ModelDownloadFailedException) {
            assertFalse(part.exists())
        }
    }

    @Test
    fun `con dimensione ignota non c'e' limite`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(data)))
        download(expected = 0)
        assertEquals(1000L, part.length())
    }
}
