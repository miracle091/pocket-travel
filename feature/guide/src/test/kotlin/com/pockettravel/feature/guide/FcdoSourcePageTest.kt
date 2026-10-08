package com.pockettravel.feature.guide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcdoSourcePageTest {
    @Test
    fun `la pagina health dell'FCDO e' riconosciuta, anche con frammento o barra finale`() {
        assertTrue(isFcdoHealthPage("https://www.gov.uk/foreign-travel-advice/palestine/health"))
        assertTrue(isFcdoHealthPage("https://www.gov.uk/foreign-travel-advice/palestine/health/"))
        assertTrue(isFcdoHealthPage("https://www.gov.uk/foreign-travel-advice/palestine/health#for-nationality=IL"))
    }

    @Test
    fun `le altre pagine dell'FCDO non sono quella della salute`() {
        assertFalse(isFcdoHealthPage("https://www.gov.uk/foreign-travel-advice/palestine/safety-and-security"))
        assertFalse(isFcdoHealthPage("https://www.gov.uk/foreign-travel-advice/palestine/safety-and-security#not-for-nationality=IL"))
    }
}
