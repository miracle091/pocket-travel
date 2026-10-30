package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Lettura di word_lists.txt, lo stesso file degli script Python (casi come test_word_lists.py).
class WordListsTest {

    @Test
    fun sectionsSkipCommentsAndBlankLines() {
        val text = "# commento\n[a]\nuno due\n\n# altro\ntre\n[b x]\nquattro\n"
        assertEquals(mapOf("a" to listOf("uno due", "tre"), "b x" to listOf("quattro")), parseWordLists(text))
    }

    @Test(expected = IllegalArgumentException::class)
    fun lineOutsideASectionIsAnError() {
        parseWordLists("uno\n[a]\n")
    }

    @Test
    fun listsFromTheFile() {
        assertTrue("conviene" in WordLists.questionStopwords)
        // Nei titoli delle sezioni ("Come arrivare"): resta nella query.
        assertFalse("come" in WordLists.questionStopwords)
        assertTrue("best+time" in WordLists.categoryTriggers.getValue("CLIMA"))
        assertEquals(listOf("get in", "get around"), WordLists.categoryGuideWords.getValue("en").getValue("TRASPORTI"))
        assertEquals(WordLists.categoryGuideWords.getValue("it").keys, WordLists.categoryGuideWords.getValue("en").keys)
        assertTrue(WordLists.categoryGuideWords.getValue("it").keys.containsAll(WordLists.categoryTriggers.keys))
        assertTrue("tomorrow" in WordLists.nearTimeStems)
    }

    @Test
    fun historyClimateLinesJoinWithoutSpaces() {
        val words = WordLists.historyClimateWords
        assertTrue(words.containsMatchIn("Quando conviene andare a Malta?"))
        assertTrue(words.containsMatchIn("HOW OLD is Lagos?"))
        assertFalse(words.containsMatchIn("How do I get to Porto by train?"))
        assertFalse('\n' in words.pattern)
    }
}
