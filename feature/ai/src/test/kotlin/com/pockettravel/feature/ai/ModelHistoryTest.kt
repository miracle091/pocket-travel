package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.HistoryTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHistoryTest {
    private fun turn(n: Int, answerChars: Int = 300) = HistoryTurn("Domanda $n?", "R$n ".padEnd(answerChars, 'x'))

    private val prompt = "p".repeat(2000)

    private fun definition(historyInContext: Boolean) =
        LlmModelDefinition("m", "M", "https://example.test/m.gguf", "m.gguf", "00", 1L, RamTier.MINIMO, historyInContext = historyInContext)

    @Test
    fun `nessun modello del catalogo ha la cronologia nel contesto`() {
        assertTrue(LlmModelCatalog.ALL.none { it.historyInContext })
    }

    @Test
    fun `la cronologia vale solo per i modelli che la chiedono`() {
        val turns = listOf(turn(1), turn(2))

        assertEquals(turns, historyForModel(definition(historyInContext = true), turns))
        assertTrue(historyForModel(definition(historyInContext = false), turns).isEmpty())
    }

    @Test
    fun `con una foto o un testo selezionato il prompt sta da solo`() {
        val turns = listOf(turn(1))
        val model = definition(historyInContext = true)

        assertTrue(historyForModel(model, turns, withPhoto = true).isEmpty())
        assertTrue(historyForModel(model, turns, selectedText = "Un testo").isEmpty())
        assertEquals(turns, historyForModel(model, turns, selectedText = " "))
    }

    @Test
    fun `con la cronologia che entra il prompt ha la domanda scritta, altrimenti quella unita`() {
        val build = { question: String -> "contesto: $question" }

        assertEquals("contesto: E quanto costa?", promptWithHistory(listOf(turn(1)), "Quanto costa arrivare a Lisbona?", "E quanto costa?", build))
        assertEquals("contesto: Quanto costa arrivare a Lisbona?", promptWithHistory(emptyList(), "Quanto costa arrivare a Lisbona?", "E quanto costa?", build))
        // Se la cronologia viene scartata tutta, un seguito senza i turni prima non si capirebbe: resta quella unita.
        val huge = { question: String -> "p".repeat(10_000) + question }
        assertEquals(huge("Quanto costa arrivare a Lisbona?"), promptWithHistory(listOf(turn(1)), "Quanto costa arrivare a Lisbona?", "E quanto costa?", huge))
    }

    @Test
    fun `i turni senza testo non contano come turni tolti`() {
        val turns = listOf(HistoryTurn("Domanda?", " "), turn(2))

        assertEquals(1, turns.usable().size)
    }

    @Test
    fun `una conversazione di dieci turni sta nel contesto`() {
        val turns = (1..10).map { turn(it) }

        assertEquals(turns, fitHistory(turns, prompt))
    }

    @Test
    fun `se non entra si tolgono i turni piu' vecchi dopo il primo`() {
        // Ogni turno costa circa 200 token stimati, il prompt circa 670: nella soglia di 3072 ne entrano una dozzina.
        val turns = (1..20).map { turn(it, answerChars = 500) }

        val kept = fitHistory(turns, prompt)

        assertTrue(kept.size in 2 until turns.size)
        assertEquals(turns.first(), kept.first())
        assertEquals(turns.last(), kept.last())
        // Quelli tolti sono i piu' vecchi dopo il primo: i rimasti sono la coda della conversazione.
        assertEquals(turns.takeLast(kept.size - 1), kept.drop(1))
    }

    @Test
    fun `con due soli turni che non entrano si toglie il primo e resta l'ultimo`() {
        val turns = listOf(turn(1, answerChars = 5000), turn(2, answerChars = 3000))

        assertEquals(listOf(turns.last()), fitHistory(turns, prompt))
    }

    @Test
    fun `un prompt che da solo riempie la soglia lascia senza cronologia`() {
        assertTrue(fitHistory(listOf(turn(1)), "p".repeat(10_000)).isEmpty())
    }

    @Test
    fun `i turni senza testo si scartano`() {
        val turns = listOf(HistoryTurn("Domanda?", " "), turn(2), HistoryTurn("", "Risposta"))

        assertEquals(listOf(turn(2)), fitHistory(turns, prompt))
    }

    @Test
    fun `la cronologia con il prompt sta nella soglia di compattazione`() {
        val turns = (1..40).map { turn(it, answerChars = 400) }

        val kept = fitHistory(turns, prompt)
        val estimated = kept.sumOf { (it.question.length + it.answer.length) / 3 } + prompt.length / 3

        assertTrue("stima $estimated sopra la soglia", estimated < compactionThreshold(ON_DEVICE_CONTEXT_SIZE))
    }
}
