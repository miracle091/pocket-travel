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
        // "furto" e "furti" interi: "further" non e' una domanda sulla sicurezza.
        assertEquals(setOf("SICUREZZA"), questionGroups("Ci sono molti furti a Napoli?"))
        assertEquals(emptySet<String>(), questionGroups("Is Sintra further than Cascais?"))
        // Momenti dell'anno: il clima di quel periodo. Natal, citta' del Brasile, non e' Natale.
        assertEquals(setOf("PERIODI"), questionGroups("Che tempo trovo a Roma a Natale?"))
        assertEquals(setOf("ALLOGGIO"), questionGroups("Dove dormire a Natal?"))
        // Le stesse domande valgono come domande sul clima (storia e clima di Wikipedia non si abbassano); "centro storico" no.
        assertTrue(isHistoryOrClimateQuestion("Che tempo trovo a Roma a Natale?"))
        assertFalse(isHistoryOrClimateQuestion("Cosa vedere nel centro storico di Lucca?"))
        assertEquals("come OR combat* OR prepar* OR recupe* OR preven* OR tratta*", withCategoryWords("come OR combat*", "Come si combatte il jet lag?", "it"))
    }

    @Test
    fun `giri di frase delle domande di sviluppo`() {
        assertEquals(setOf("TRASPORTI"), questionGroups("Ci sono taxi a Concesio?"))
        assertEquals(setOf("CIBO_BEVANDE"), questionGroups("C'è una trattoria tipica a Fátima?"))
        assertEquals(setOf("ACQUISTI"), questionGroups("Cosa portare a casa come ricordo da Siena?"))
        assertEquals(setOf("COSA_VEDERE"), questionGroups("Is there a castle in Guimarães?"))
        assertEquals(setOf("ACQUISTI"), questionGroups("Quanto costa la vita?"))
        assertEquals(setOf("STORIA"), questionGroups("How old is Lagos?"))
        // "dolci" e "costa" solo come parole intere o nell'espressione: Dolceacqua e la costa non sono cibo e prezzi.
        assertEquals(setOf("COSA_VEDERE"), questionGroups("Cosa vedere a Dolceacqua?"))
        assertEquals(setOf("COSA_VEDERE"), questionGroups("Cosa vedere sulla costa?"))
        assertTrue(isHistoryOrClimateQuestion("Quali popoli hanno abitato Spalato?"))
        assertTrue(isHistoryOrClimateQuestion("How old is Lagos?"))
        assertEquals(setOf("CLIMA"), questionGroups("Quando conviene andare a Malta?"))
        assertTrue("CLIMA" in questionGroups("When to visit Malta?"))
        assertTrue(isHistoryOrClimateQuestion("Quando conviene andare a Malta?"))
    }

    @Test
    fun `previsioni del tempo separate dal clima`() {
        listOf(
            "Che tempo fa domani a Roma?", "Pioverà sabato a Lisbona?", "Previsioni per il weekend a Spalato",
            "Fa caldo oggi a Siviglia?", "Nevica stanotte a Zermatt?", "Will it rain tomorrow in Porto?",
            "What's the weather like this weekend in Athens?", "Weather forecast for Zagreb", "Is it cold tonight in Bern?",
        ).forEach { assertEquals(it, setOf("METEO"), questionGroups(it)) }
        listOf(
            "Com'è il clima a Roma?", "Piove molto a novembre a Lisbona?", "Che tempo trovo a Roma a Natale?",
            "When is the best time to go to Porto?", "Is it rainy in Athens in winter?",
        ).forEach { assertFalse(it, "METEO" in questionGroups(it)) }
        // Il momento da solo non e' una previsione.
        assertEquals(setOf("COSA_VEDERE"), questionGroups("Cosa vedere oggi a Roma?"))
        assertEquals(setOf("COSA_VEDERE"), questionGroups("What to see tomorrow in Lisbon?"))
        // Le previsioni restano domande sul clima per la ricerca (sezione Clima, Wikipedia con il peso pieno).
        assertTrue(isHistoryOrClimateSearch("Pioverà domani a Roma?", wordLists = true, modelGroups = emptySet()))
        assertEquals("piov* OR clima", withCategoryWords("piov*", "Pioverà domani a Roma?", "it"))
    }

    @Test
    fun `aggiunge alla query le parole dei titoli nella lingua della guida`() {
        assertEquals("pernot* OR rimini OR allogg*", withCategoryWords("pernot* OR rimini", "Dove pernottare a Rimini?", "it"))
        assertEquals("\"get in\" OR \"get around\"", withCategoryWords("", "Come si arriva in treno?", "en"))
        assertEquals("piatt*", withCategoryWords("piatt*", "Ciao", "it"))
    }
}
