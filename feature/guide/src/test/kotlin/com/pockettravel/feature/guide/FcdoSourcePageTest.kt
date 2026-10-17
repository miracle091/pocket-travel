package com.pockettravel.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Test

class FcdoSourcePageTest {
    @Test
    fun `la pagina health dell'FCDO e' riconosciuta, anche con frammento o barra finale`() {
        assertEquals(FcdoPage.HEALTH, fcdoPageOf("https://www.gov.uk/foreign-travel-advice/palestine/health"))
        assertEquals(FcdoPage.HEALTH, fcdoPageOf("https://www.gov.uk/foreign-travel-advice/palestine/health/"))
        assertEquals(FcdoPage.HEALTH, fcdoPageOf("https://www.gov.uk/foreign-travel-advice/palestine/health#for-nationality=IL"))
    }

    @Test
    fun `ingresso e assistenza consolare delle sezioni per i britannici hanno pagine proprie`() {
        assertEquals(
            FcdoPage.ENTRY_REQUIREMENTS,
            fcdoPageOf("https://www.gov.uk/foreign-travel-advice/japan/entry-requirements#for-nationality=GB"),
        )
        assertEquals(FcdoPage.GETTING_HELP, fcdoPageOf("https://www.gov.uk/foreign-travel-advice/japan/getting-help"))
    }

    @Test
    fun `le altre pagine dell'FCDO sono quella della sicurezza`() {
        assertEquals(FcdoPage.SAFETY, fcdoPageOf("https://www.gov.uk/foreign-travel-advice/palestine/safety-and-security"))
        assertEquals(
            FcdoPage.SAFETY,
            fcdoPageOf("https://www.gov.uk/foreign-travel-advice/palestine/safety-and-security#not-for-nationality=IL"),
        )
    }
}
