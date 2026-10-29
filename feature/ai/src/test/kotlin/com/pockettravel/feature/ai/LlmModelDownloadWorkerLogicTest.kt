package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LlmModelDownloadWorkerLogicTest {

    private val first = LlmModelCatalog.ALL[0]
    private val second = LlmModelCatalog.ALL[1]

    @Test
    fun `il download resta sul modello richiesto anche se ora ne e' scelto un altro`() {
        assertEquals(first.id, downloadDefinition(first.id, selected = second).id)
    }

    @Test
    fun `senza id o con un id sconosciuto si usa il modello scelto`() {
        assertEquals(second.id, downloadDefinition(null, selected = second).id)
        assertEquals(second.id, downloadDefinition("modello-che-non-esiste", selected = second).id)
    }

    @Test
    fun `i tentativi sono limitati`() {
        val attempts = (0 until 100).count { shouldRetryDownload(runAttemptCount = it) }
        assertEquals(LlmModelDownloadWorker.MAX_ATTEMPTS - 1, attempts)
        assertTrue(shouldRetryDownload(0))
        assertFalse(shouldRetryDownload(LlmModelDownloadWorker.MAX_ATTEMPTS - 1))
    }

    @Test
    fun `il disco pieno si riconosce`() {
        assertTrue(IOException("write failed: ENOSPC (No space left on device)").isNoSpaceLeft())
        assertFalse(IOException("Download modello fallito: HTTP 503").isNoSpaceLeft())
        assertFalse(IOException().isNoSpaceLeft())
    }
}
