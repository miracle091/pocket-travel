package com.pockettravel.core.data.vaccination

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Ultimo paese di partenza scelto per le vaccinazioni (ISO alpha-2 maiuscolo): ha la precedenza sulla nazionalita'. */
@Singleton
class VaccinationPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("vaccination", Context.MODE_PRIVATE)

    var departure: String?
        get() = prefs.getString(KEY_DEPARTURE, null)
        set(value) = prefs.edit { putString(KEY_DEPARTURE, value) }

    private companion object {
        const val KEY_DEPARTURE = "departure"
    }
}
