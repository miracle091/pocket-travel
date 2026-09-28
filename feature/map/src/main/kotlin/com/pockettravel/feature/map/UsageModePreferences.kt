package com.pockettravel.feature.map

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modalita' d'uso scelta (onboarding o Altro), null finche' l'utente non ne sceglie una, e la
 * casella "Con disabilita'", che vale con qualsiasi modalita'.
 */
@Singleton
class UsageModePreferences @Inject constructor(
    @ApplicationContext context: Context,
    private val filterPreferences: MapFilterPreferences,
) {
    private val prefs = context.getSharedPreferences("usage_mode", Context.MODE_PRIVATE).apply {
        // Fino alla 0.8.0 "Con disabilita'" era una modalita' a parte, con il profilo di A piedi.
        if (getString(KEY_MODE, null) == LEGACY_ACCESSIBLE_MODE) {
            edit { putString(KEY_MODE, UsageMode.A_PIEDI.name); putBoolean(KEY_ACCESSIBLE, true) }
        }
    }
    private val _mode = MutableStateFlow(prefs.getString(KEY_MODE, null)?.let { name -> UsageMode.entries.firstOrNull { it.name == name } })
    val mode: StateFlow<UsageMode?> = _mode.asStateFlow()

    private val _accessible = MutableStateFlow(prefs.getBoolean(KEY_ACCESSIBLE, false))
    /** Nasconde sulla mappa i POI che OSM segna come non accessibili in sedia a rotelle (wheelchair=no). */
    val accessible: StateFlow<Boolean> = _accessible.asStateFlow()

    /** Salva la modalita' e riporta i filtri della mappa alle sue categorie predefinite. */
    fun setMode(mode: UsageMode) {
        prefs.edit { putString(KEY_MODE, mode.name) }
        _mode.value = mode
        filterPreferences.setHidden(mode.defaultHidden)
    }

    fun setAccessible(accessible: Boolean) {
        prefs.edit { putBoolean(KEY_ACCESSIBLE, accessible) }
        _accessible.value = accessible
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_ACCESSIBLE = "accessible"
        const val LEGACY_ACCESSIBLE_MODE = "ACCESSIBILITA"
    }
}
