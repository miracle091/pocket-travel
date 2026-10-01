package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AskErrorMessageTest {
    @Test
    fun `una chiave rifiutata ha il suo messaggio, con l'opzione di rimuoverla`() {
        assertEquals(R.string.ai_error_key_rejected, askErrorMessage(OnlineApiKeyRejectedException("401")))
        assertEquals(R.string.ai_error_model, askErrorMessage(OnlineModelNotFoundException("404")))
        assertEquals(R.string.ai_error_answer, askErrorMessage(IllegalStateException("500")))
    }
}
