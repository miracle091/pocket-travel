package com.pockettravel.feature.ai.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadFailureTest {
    @Test
    fun `un'architettura sconosciuta ha la sua eccezione con la riga del log`() {
        val log = "llama_model_load: error loading model: error loading model architecture: unknown model architecture: 'foo'\n" +
            "llama_model_load_from_file_impl: failed to load model\n"
        val error = modelLoadException("load", 1, log)
        assertTrue(error is UnsupportedArchitectureException)
        assertTrue(error.message.orEmpty().contains("unknown model architecture: 'foo'"))
    }

    @Test
    fun `una memoria insufficiente non e' un'architettura non supportata`() {
        val weights = "load_tensors: unable to allocate CPU buffer\nllama_model_load_from_file_impl: failed to load model\n"
        val compute = "sched_reserve: failed to allocate compute pp buffers"
        assertEquals(ModelLoadFailure.MEMORY, classifyModelLoadFailure(weights))
        assertEquals(ModelLoadFailure.MEMORY, classifyModelLoadFailure(compute))
        val error = modelLoadException("prepare", 1, compute)
        assertTrue(error is ModelLoadException)
        assertEquals(ModelLoadFailure.MEMORY, (error as ModelLoadException).reason)
    }

    @Test
    fun `un file mancante, illeggibile o troncato e' un errore di file`() {
        listOf(
            "gguf_init_from_file_impl: failed to open GGUF file '/x.gguf' (No such file or directory)",
            "llama_model_load: error loading model: tensor 'blk.0.attn_q.weight' data is not within the file bounds, model is corrupted or incomplete",
            "gguf_init_from_file_impl: invalid magic characters: 'abcd'",
        ).forEach { assertEquals(it, ModelLoadFailure.FILE, classifyModelLoadFailure(it)) }
    }

    @Test
    fun `senza log o con un log sconosciuto la causa resta altro`() {
        assertEquals(ModelLoadFailure.OTHER, classifyModelLoadFailure(""))
        assertEquals(ModelLoadFailure.OTHER, classifyModelLoadFailure("something else went wrong"))
        assertEquals("Model load failed (code 1)", modelLoadException("load", 1, "").message)
    }

    @Test
    fun `l'architettura ha la precedenza e il messaggio e' limitato alle ultime righe`() {
        assertEquals(
            ModelLoadFailure.ARCHITECTURE,
            classifyModelLoadFailure("failed to allocate\nunknown model architecture: 'x'"),
        )
        val long = "x".repeat(2000) + "\nlast line"
        val message = modelLoadException("load", 1, long).message.orEmpty()
        assertTrue(message.endsWith("last line"))
        assertTrue(message.length < 500)
    }
}
