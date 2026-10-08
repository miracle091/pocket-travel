package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideSourceSiteTest {
    @Test
    fun `il sito dal link della sezione`() {
        assertEquals(GuideSourceSite.WIKIVOYAGE, guideSourceSiteOf("https://it.wikivoyage.org/wiki/Italia"))
        assertEquals(GuideSourceSite.WIKIPEDIA, guideSourceSiteOf("https://en.wikipedia.org/wiki/Venice"))
        assertEquals(GuideSourceSite.TRAVEL_GC_CA, guideSourceSiteOf("https://travel.gc.ca/destinations/it"))
        assertEquals(GuideSourceSite.FCDO, guideSourceSiteOf("https://www.gov.uk/foreign-travel-advice/palestine/health"))
        // un link con travel.gc.ca nel percorso non e' travel.gc.ca
        assertEquals(GuideSourceSite.WIKIVOYAGE, guideSourceSiteOf("https://en.wikivoyage.org/wiki/travel.gc.ca"))
    }
}
