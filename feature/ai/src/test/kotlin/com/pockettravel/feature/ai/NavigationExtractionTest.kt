package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationExtractionTest {

    @Test
    fun `reads the json shaped by the grammar`() {
        assertEquals("Colosseo", parseNavigationJson("""{"destination": "Colosseo"}"""))
    }

    @Test
    fun `invalid or truncated output is no destination`() {
        assertNull(parseNavigationJson("""{"destination": "Colos"""))
        assertNull(parseNavigationJson("""{"destination": " "}"""))
        assertNull(parseNavigationJson("not json"))
        assertNull(parseNavigationJson("[]"))
    }

    @Test
    fun `model can shorten the rules destination, profile stays from the rules`() {
        val rules = NavigationRequest("vedere il Colosseo domani", NavigationRequestProfile.WALK)
        assertEquals(NavigationRequest("Colosseo", NavigationRequestProfile.WALK), mergeNavigationRequest(rules, "Colosseo"))
    }

    @Test
    fun `invented, translated or longer destination falls back to the rules`() {
        val rules = NavigationRequest("hotel Danieli", null)
        assertEquals(rules, mergeNavigationRequest(rules, "Hotel Daniele"))
        assertEquals(rules, mergeNavigationRequest(rules, "naviga fino all'hotel Danieli"))
        assertEquals(rules, mergeNavigationRequest(rules, null))
    }

    @Test
    fun `prompt contains the request`() {
        assertTrue(navigationPrompt("vedere il Colosseo domani").startsWith("Testo: vedere il Colosseo domani\n"))
    }
}
