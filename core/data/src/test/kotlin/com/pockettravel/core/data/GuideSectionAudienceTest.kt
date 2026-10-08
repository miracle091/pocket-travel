package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideSectionAudienceTest {
    private val url = "https://www.gov.uk/foreign-travel-advice/palestine/safety-and-security"

    @Test
    fun `le sezioni per una nazionalita' solo a chi ce l'ha, il rimando a tutti gli altri`() {
        assertTrue(isGuideSectionFor("$url#for-nationality=IL", "IL"))
        assertFalse(isGuideSectionFor("$url#for-nationality=IL", "IT"))
        assertFalse(isGuideSectionFor("$url#for-nationality=IL", null))
        assertFalse(isGuideSectionFor("$url#not-for-nationality=IL", "IL"))
        assertTrue(isGuideSectionFor("$url#not-for-nationality=IL", null))
        assertTrue(isGuideSectionFor(url, "IL"))
        assertTrue(isGuideSectionFor("https://en.wikivoyage.org/wiki/Palestinian_territories", null))
    }

    @Test
    fun `il link si mostra senza il suffisso`() {
        assertEquals(url, guideSourceUrlWithoutAudience("$url#for-nationality=IL"))
        assertEquals(url, guideSourceUrlWithoutAudience("$url#not-for-nationality=IL"))
        assertEquals(url, guideSourceUrlWithoutAudience(url))
    }
}
