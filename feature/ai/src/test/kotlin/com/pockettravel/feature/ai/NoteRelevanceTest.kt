package com.pockettravel.feature.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRelevanceTest {
    @Test
    fun `una nota e' pertinente solo con una parola di contenuto in comune`() {
        assertTrue(sharesContentWord("Dov'è la farmacia più vicina?", "Farmacia da ricordare: via Roma"))
        assertTrue(sharesContentWord("A che ora parte il VOLO?", "Volo AZ610, partenza alle 10"))
        // Solo parole senza contenuto in comune: "come", "dove", "della", "sono".
        assertFalse(sharesContentWord("Come si arriva al centro della città?", "Come pagare il parcheggio della via"))
        assertFalse(sharesContentWord("Dove sono i musei?", "Dove sono le chiavi"))
        assertFalse(sharesContentWord("e la?", "la"))
    }
}
