package com.pockettravel.app.onboarding

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPreferencesTest {

    @Test
    fun `al primo avvio l'onboarding non e' completato`() {
        assertFalse(OnboardingPreferences(InMemoryPreferencesFactory()).isCompleted())
    }

    @Test
    fun `l'onboarding completato resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        OnboardingPreferences(files).markCompleted()

        assertTrue(OnboardingPreferences(files).isCompleted())
    }

    @Test
    fun `il flag sta nel file onboarding con la chiave completed`() {
        val files = InMemoryPreferencesFactory()
        OnboardingPreferences(files).markCompleted()

        assertTrue(files.open("onboarding").getBoolean("completed", false))
    }
}
