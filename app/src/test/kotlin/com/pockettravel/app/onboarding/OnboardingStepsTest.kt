package com.pockettravel.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStepsTest {

    @Test
    fun `Benvenuto richiede almeno un modo di spostarsi e la nazionalita'`() {
        assertFalse(OnboardingStep.WELCOME.canAdvance(hasUsageModes = false, hasNationality = true))
        assertFalse(OnboardingStep.WELCOME.canAdvance(hasUsageModes = true, hasNationality = false))
        assertFalse(OnboardingStep.WELCOME.canAdvance(hasUsageModes = false, hasNationality = false))
        assertTrue(OnboardingStep.WELCOME.canAdvance(hasUsageModes = true, hasNationality = true))
    }

    @Test
    fun `gli altri passi non hanno requisiti`() {
        assertTrue(OnboardingStep.DESTINATIONS.canAdvance(hasUsageModes = false, hasNationality = false))
        assertTrue(OnboardingStep.READY.canAdvance(hasUsageModes = false, hasNationality = false))
    }

    @Test
    fun `i passi sono tre, in ordine`() {
        assertEquals(listOf(OnboardingStep.WELCOME, OnboardingStep.DESTINATIONS, OnboardingStep.READY), OnboardingStep.entries)
    }
}
