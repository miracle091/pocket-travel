package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.ModelLoadException
import com.pockettravel.feature.ai.llamacpp.ModelLoadFailure
import com.pockettravel.feature.ai.llamacpp.UnsupportedArchitectureException
import org.junit.Assert.assertEquals
import org.junit.Test

class AskErrorMessageTest {
    @Test
    fun `una chiave rifiutata ha il suo messaggio, con l'opzione di rimuoverla`() {
        assertEquals(R.string.ai_error_key_rejected, askErrorMessage(OnlineApiKeyRejectedException("401")))
        assertEquals(R.string.ai_error_model, askErrorMessage(OnlineModelNotFoundException("404")))
        assertEquals(R.string.ai_error_answer, askErrorMessage(IllegalStateException("500")))
        assertEquals(R.string.ai_error_wrong_language, askErrorMessage(OnlineAnswerWrongLanguageException()))
        assertEquals(R.string.ai_error_empty_answer, askErrorMessage(OnlineAnswerEmptyException()))
    }

    @Test
    fun `un modello che non si carica dice la causa, quella sconosciuta resta generica`() {
        assertEquals(R.string.ai_error_model_file, askErrorMessage(ModelLoadException(ModelLoadFailure.FILE, "invalid magic")))
        assertEquals(R.string.ai_error_model_memory, askErrorMessage(ModelLoadException(ModelLoadFailure.MEMORY, "failed to allocate")))
        assertEquals(R.string.ai_error_model_architecture, askErrorMessage(UnsupportedArchitectureException("unknown model architecture")))
        assertEquals(R.string.ai_error_answer, askErrorMessage(ModelLoadException(ModelLoadFailure.OTHER, "")))
    }
}
