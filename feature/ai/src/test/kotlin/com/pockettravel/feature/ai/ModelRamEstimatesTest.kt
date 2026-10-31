package com.pockettravel.feature.ai

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ModelRamEstimatesTest {
    private val server = MockWebServer()
    private lateinit var modelsDir: File
    private lateinit var estimates: ModelRamEstimates
    private val header = qwen35(blocks = 24, embedding = 1024).bytes(vocab = 248_320)

    private fun definition(sha: String = "aa") = LlmModelDefinition(
        id = "m", displayName = "M", url = server.url("/m.gguf").toString(), fileName = "m.gguf", sha256 = sha,
        sizeBytes = 500L * 1024 * 1024, minRamTier = RamTier.MINIMO,
    )

    @Before
    fun setUp() {
        server.start()
        modelsDir = createTempDirectory("pocket-travel-ram-test").toFile()
        val manager = FileLlmModelManager(OkHttpClient(), modelsDir, AiModelCoordinator(), Dispatchers.IO)
        estimates = ModelRamEstimates(InMemoryPreferencesFactory(), OkHttpClient(), manager, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        server.shutdown()
        modelsDir.deleteRecursively()
    }

    @Test
    fun `prima del download chiede solo i primi 64 KB e poi usa la stima salvata`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(header)))
        val first = estimates.estimate(definition())
        assertNotNull(first)
        assertEquals("bytes=0-65535", server.takeRequest().getHeader("Range"))
        assertEquals(first, estimates.estimate(definition()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `un'impronta nuova si stima di nuovo`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(header)))
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(header)))
        estimates.estimate(definition("aa"))
        estimates.estimate(definition("bb"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `scaricato si legge dal file, senza rete`() = runBlocking {
        File(modelsDir, "m.gguf").writeBytes(header)
        assertNotNull(estimates.estimate(definition()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `errore del server o file che non e' un GGUF danno null e non si salvano`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(206).setBody("<html>"))
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(header)))
        assertNull(estimates.estimate(definition()))
        assertNull(estimates.estimate(definition()))
        assertNotNull(estimates.estimate(definition()))
    }
}
