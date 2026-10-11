package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryWordsTest {

    @Test
    fun `le categorie dalle parole della domanda in italiano e in inglese`() {
        assertEquals(setOf("ALLOGGIO"), questionCategories("Cerco un posto economico per pernottare a Rimini"))
        assertEquals(setOf("ACQUISTI"), questionCategories("Where can I buy souvenirs in Porto?"))
        assertEquals(setOf("CLIMA"), questionCategories("Serve l'ombrello a Bologna in autunno?"))
        // Le radici corte valgono solo come parola intera: "tipico" non e' "tip", "carta" non e' "car".
        assertEquals(emptySet<String>(), questionCategories("Che cosa e' tipico della zona?"))
        assertEquals(setOf("ACQUISTI"), questionCategories("How much should I tip?"))
        // Richieste di consigli e di cosa fare dopo una perdita, oltre alle categorie.
        assertEquals(setOf("CONSIGLI"), questionCategories("Come si combatte il jet lag?"))
        assertEquals(setOf("DOGANE", "EMERGENZE"), questionCategories("What do I do if I lose my passport abroad?"))
        assertEquals("come OR combat* OR prepar* OR recupe* OR preven* OR tratta*", withCategoryWords("come OR combat*", "Come si combatte il jet lag?", "it"))
    }

    @Test
    fun `aggiunge alla query le parole dei titoli nella lingua della guida`() {
        assertEquals("pernot* OR rimini OR allogg*", withCategoryWords("pernot* OR rimini", "Dove pernottare a Rimini?", "it"))
        assertEquals("\"get in\" OR \"get around\"", withCategoryWords("", "Come si arriva in treno?", "en"))
        assertEquals("piatt*", withCategoryWords("piatt*", "Ciao", "it"))
    }
}
