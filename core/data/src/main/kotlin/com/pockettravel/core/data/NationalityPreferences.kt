package com.pockettravel.core.data

import android.content.Context
import android.content.res.Resources
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Nazionalita' di chi viaggia (ISO 3166-1 alpha-2), scelta nel primo avvio o in Altro: la guida mostra
 * ambasciate e consolati di quel paese. Finche' non la sceglie vale il paese del telefono.
 */
@Singleton
class NationalityPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val _nationality = MutableStateFlow(prefs.getString(KEY_NATIONALITY, null) ?: deviceCountry())
    val nationality: StateFlow<String?> = _nationality.asStateFlow()

    fun setNationality(countryCode: String) {
        prefs.edit { putString(KEY_NATIONALITY, countryCode) }
        _nationality.value = countryCode
    }

    // Paese delle impostazioni di sistema: Locale.getDefault() segue la lingua scelta nell'app ("it", "en"),
    // che non ha paese.
    private fun deviceCountry(): String? =
        Resources.getSystem().configuration.locales[0].country.takeIf { it.length == 2 }

    private companion object {
        const val KEY_NATIONALITY = "nationality"
    }
}
