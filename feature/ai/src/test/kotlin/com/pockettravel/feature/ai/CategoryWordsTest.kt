package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryWordsTest {

    @Test
    fun `le categorie dalle parole della domanda in italiano e in inglese`() {
        assertEquals(setOf(GuideCategory.ALLOGGIO), questionCategories("Cerco un posto economico per pernottare a Rimini"))
        assertEquals(setOf(GuideCategory.ACQUISTI), questionCategories("Where can I buy souvenirs in Porto?"))
        assertEquals(setOf(GuideCategory.CLIMA), questionCategories("Serve l'ombrello a Bologna in autunno?"))
        // Le radici corte valgono solo come parola intera: "tipico" non e' "tip", "carta" non e' "car".
        assertEquals(emptySet<GuideCategory>(), questionCategories("Che cosa e' tipico della zona?"))
        assertEquals(setOf(GuideCategory.ACQUISTI), questionCategories("How much should I tip?"))
    }

    @Test
    fun `aggiunge alla query le parole dei titoli nella lingua della guida`() {
        assertEquals("pernot* OR rimini OR allogg*", withCategoryWords("pernot* OR rimini", "Dove pernottare a Rimini?", "it"))
        assertEquals("\"get in\" OR \"get around\"", withCategoryWords("", "Come si arriva in treno?", "en"))
        assertEquals("piatt*", withCategoryWords("piatt*", "Ciao", "it"))
    }
}
