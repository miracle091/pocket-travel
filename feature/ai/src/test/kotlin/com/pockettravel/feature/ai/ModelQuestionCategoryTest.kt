package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.testing.FakeOnDeviceLlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelQuestionCategoryTest {

    @Test
    fun `la risposta del modello diventa il gruppo delle liste di parole`() = runTest {
        val engine = FakeOnDeviceLlmEngine().apply { answer = { "alloggio" } }

        assertEquals("ALLOGGIO", modelQuestionCategory(engine, "C'è un agriturismo a Zara?", "it"))
        val call = engine.grammarCalls.single()
        assertTrue(call.prompt.endsWith("Domanda: C'è un agriturismo a Zara?\nCategoria:"))
        assertTrue("alloggio (hotel, dormire, campeggio); cibo (ristoranti, piatti, bere)" in call.prompt)
        // Nella grammatica solo i nomi, senza gli esempi.
        assertTrue(call.grammar.startsWith("root ::= \"alloggio\" | \"cibo\" | \"cosa vedere\""))
    }

    @Test
    fun `viaggio in generale e risposte fuori elenco non danno un gruppo`() = runTest {
        assertNull(modelQuestionCategory(FakeOnDeviceLlmEngine().apply { answer = { "travel in general" } }, "How do I deal with jet lag?", "en"))
        assertNull(modelQuestionCategory(FakeOnDeviceLlmEngine().apply { answer = { "boh" } }, "Ciao", "it"))
        assertEquals("CLIMA", modelQuestionCategory(FakeOnDeviceLlmEngine().apply { answer = { " climate " } }, "Is it windy in Porto?", "en"))
        // Un errore della classificazione non ferma la risposta: si cerca senza categoria.
        assertNull(modelQuestionCategory(FakeOnDeviceLlmEngine().apply { failure = IllegalStateException("grammatica") }, "Tira vento?", "it"))
    }

    @Test(expected = CancellationException::class)
    fun `la cancellazione non diventa una domanda senza categoria`() = runTest {
        modelQuestionCategory(FakeOnDeviceLlmEngine().apply { failure = CancellationException("annullata") }, "Tira vento?", "it")
    }

    @Test
    fun `ogni gruppo del modello ha parole delle guide`() {
        for (language in listOf("it", "en")) {
            for (group in modelCategoryLabels(language).mapNotNull { it.group }) {
                assertTrue("$language $group", withCategoryWords("", "Ciao", language, extraGroups = setOf(group)).isNotBlank())
            }
        }
    }
}
