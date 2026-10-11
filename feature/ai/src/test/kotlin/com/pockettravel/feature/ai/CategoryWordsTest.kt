package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryWordsTest {

    @Test
    fun `le categorie dalle parole della domanda in italiano e in inglese`() {
        assertEquals(setOf("ALLOGGIO"), questionGroups("Cerco un posto economico per pernottare a Rimini"))
        assertEquals(setOf("ACQUISTI"), questionGroups("Where can I buy souvenirs in Porto?"))
        assertEquals(setOf("CLIMA"), questionGroups("Serve l'ombrello a Bologna in autunno?"))
        // Le radici corte valgono solo come parola intera: "tipico" non e' "tip", "carta" non e' "car".
        assertEquals(emptySet<String>(), questionGroups("Che cosa e' tipico della zona?"))
        assertEquals(setOf("ACQUISTI"), questionGroups("How much should I tip?"))
        // Espressioni di piu' parole: "best time" e "periodo migliore" sono il clima, "best" da solo no.
        assertEquals(setOf("CLIMA"), questionGroups("When is the best time to go to Porto?"))
        assertEquals(setOf("CLIMA"), questionGroups("Qual è il periodo migliore per andare a Lisbona?"))
        assertEquals(emptySet<String>(), questionGroups("Which is the best beach?"))
        // Richieste di consigli e di cosa fare dopo una perdita, oltre alle categorie.
        assertEquals(setOf("CONSIGLI"), questionGroups("Come si combatte il jet lag?"))
        assertEquals(setOf("DOGANE", "EMERGENZE"), questionGroups("What do I do if I lose my passport abroad?"))
        // Con "=" la parola e' intera: nomi di citta' e parole comuni che cominciano come una radice non contano.
        assertEquals(setOf("ALLOGGIO"), questionGroups("Un hotel per due persone a Campobasso?"))
        assertEquals(setOf("CIBO_BEVANDE"), questionGroups("Where to eat pasta in Cardiff?"))
        assertEquals(setOf("EMERGENZE"), questionGroups("Ho perso il portafoglio"))
        // Momenti dell'anno: il clima di quel periodo. Natal, citta' del Brasile, non e' Natale.
        assertEquals(setOf("PERIODI"), questionGroups("Che tempo trovo a Roma a Natale?"))
        assertEquals(setOf("ALLOGGIO"), questionGroups("Dove dormire a Natal?"))
        // Le stesse domande valgono come domande sul clima (storia e clima di Wikipedia non si abbassano); "centro storico" no.
        assertTrue(isHistoryOrClimateQuestion("Che tempo trovo a Roma a Natale?"))
        assertFalse(isHistoryOrClimateQuestion("Cosa vedere nel centro storico di Lucca?"))
        assertEquals("come OR combat* OR prepar* OR recupe* OR preven* OR tratta*", withCategoryWords("come OR combat*", "Come si combatte il jet lag?", "it"))
    }

    @Test
    fun `aggiunge alla query le parole dei titoli nella lingua della guida`() {
        assertEquals("pernot* OR rimini OR allogg*", withCategoryWords("pernot* OR rimini", "Dove pernottare a Rimini?", "it"))
        assertEquals("\"get in\" OR \"get around\"", withCategoryWords("", "Come si arriva in treno?", "en"))
        assertEquals("piatt*", withCategoryWords("piatt*", "Ciao", "it"))
    }
}
