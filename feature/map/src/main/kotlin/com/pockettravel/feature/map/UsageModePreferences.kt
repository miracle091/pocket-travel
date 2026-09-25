package com.pockettravel.feature.map

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Modalita' d'uso scelta (onboarding o Altro), null finche' l'utente non ne sceglie una. */
@Singleton
class UsageModePreferences @Inject constructor(
    @ApplicationContext context: Context,
    private val filterPreferences: MapFilterPreferences,
) {
    private val prefs = context.getSharedPreferences("usage_mode", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(prefs.getString(KEY_MODE, null)?.let { name -> UsageMode.entries.firstOrNull { it.name == name } })
    val mode: StateFlow<UsageMode?> = _mode.asStateFlow()

    /** Salva la modalita' e riporta i filtri della mappa alle sue categorie predefinite. */
    fun setMode(mode: UsageMode) {
        prefs.edit { putString(KEY_MODE, mode.name) }
        _mode.value = mode
        filterPreferences.setHidden(mode.defaultHidden)
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
