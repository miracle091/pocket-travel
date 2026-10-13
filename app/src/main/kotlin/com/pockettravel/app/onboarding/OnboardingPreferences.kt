package com.pockettravel.app.onboarding

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Ricorda se l'utente ha già visto l'onboarding — nessun dato personale, solo un flag locale. */
@Singleton
class OnboardingPreferences @Inject constructor(preferences: PreferencesFactory) {

    private val prefs = preferences.open("onboarding")

    fun isCompleted(): Boolean = prefs.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() = prefs.edit { putBoolean(KEY_COMPLETED, true) }

    private companion object {
        const val KEY_COMPLETED = "completed"
    }
}
