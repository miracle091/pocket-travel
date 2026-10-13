package com.pockettravel.core.data.vaccination

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Ultimo paese di partenza scelto per le vaccinazioni (ISO alpha-2 maiuscolo): ha la precedenza sulla nazionalita'. */
@Singleton
class VaccinationPreferences @Inject constructor(preferences: PreferencesFactory) {
    private val prefs = preferences.open("vaccination")

    var departure: String?
        get() = prefs.getString(KEY_DEPARTURE, null)
        set(value) = prefs.edit { putString(KEY_DEPARTURE, value) }

    private companion object {
        const val KEY_DEPARTURE = "departure"
    }
}
