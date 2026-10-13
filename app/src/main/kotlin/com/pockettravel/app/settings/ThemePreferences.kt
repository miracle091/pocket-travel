package com.pockettravel.app.settings

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aspetto scelto in Altro: colori del wallpaper (dynamic color, Android 12+) o del brand, e tema
 * scuro forzato (spento: l'app segue il tema del dispositivo).
 */
@Singleton
class ThemePreferences @Inject constructor(preferences: PreferencesFactory) {

    private val prefs = preferences.open("theme")
    private val _useDynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, true))
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    fun setUseDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
        _useDynamicColor.value = enabled
    }

    private val _forceDark = MutableStateFlow(prefs.getBoolean(KEY_FORCE_DARK, false))
    val forceDark: StateFlow<Boolean> = _forceDark.asStateFlow()

    fun setForceDark(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FORCE_DARK, enabled) }
        _forceDark.value = enabled
    }

    private companion object {
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_FORCE_DARK = "force_dark"
    }
}
