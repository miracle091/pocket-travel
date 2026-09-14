package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelAssistantLogicTest {

    @Test
    fun `builds an OR query from words of at least four characters`() {
        val query = buildFtsQuery("Posso portare farmaci da banco in Giappone?")

        assertEquals("Posso OR portare OR farmaci OR banco OR Giappone", query)
    }

    @Test
    fun `drops punctuation from each token`() {
        val query = buildFtsQuery("vaccini, dogana; normativa?!")

        assertEquals("vaccini OR dogana OR normativa", query)
    }

    @Test
    fun `returns blank for a question made only of short words`() {
        val query = buildFtsQuery("hi to a in")

        assertTrue(query.isBlank())
    }

    @Test
    fun `truncates context beyond the configured character limit`() {
        val longContext = "a".repeat(3000)

        val truncated = truncateContext(longContext, maxChars = 2000)

        assertEquals(2000, truncated.length)
    }

    @Test
    fun `leaves short context untouched`() {
        val shortContext = "Dogane: dichiarare importi oltre una certa soglia."

        assertEquals(shortContext, truncateContext(shortContext, maxChars = 2000))
    }
}
