package com.pockettravel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `toglie titoli, elenchi, enfasi e link e mette la pausa in fondo alle voci`() {
        val text = """
            ## Orari
            - **Lunedì** chiuso
            - Martedì 9-18
            1) Vedi [il sito](https://example.org)
        """.trimIndent()
        assertEquals(
            listOf("Orari. Lunedì chiuso. Martedì 9-18. Vedi il sito."),
            speechChunks(text, 4000),
        )
    }

    @Test
    fun `i pezzi stanno nel limite e si dividono tra le frasi`() {
        val chunks = speechChunks("Prima frase. Seconda frase! Terza frase?", 30)
        assertEquals(listOf("Prima frase. Seconda frase!", "Terza frase?"), chunks)
    }

    @Test
    fun `una frase piu' lunga del limite si divide tra le parole`() {
        val chunks = speechChunks("uno due tre quattro cinque sei", 10)
        assertEquals(listOf("uno due", "tre", "quattro", "cinque", "sei."), chunks)
        assertTrue(chunks.all { it.length <= 10 })
    }

    @Test
    fun `testo vuoto o solo segni non da' pezzi`() {
        assertEquals(emptyList<String>(), speechChunks(" \n**\n# ", 4000))
    }
}
