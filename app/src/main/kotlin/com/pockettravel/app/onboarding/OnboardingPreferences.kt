package com.pockettravel.app.onboarding

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Ricorda se l'utente ha già visto l'onboarding — nessun dato personale, solo un flag locale. */
@Singleton
class OnboardingPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    fun isCompleted(): Boolean = prefs.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() = prefs.edit { putBoolean(KEY_COMPLETED, true) }

    private companion object {
        const val KEY_COMPLETED = "completed"
    }
}
