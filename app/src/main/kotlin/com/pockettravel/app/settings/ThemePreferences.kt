package com.pockettravel.app.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Scelta dell'utente tra i colori del wallpaper (dynamic color, Android 12+) e quelli del brand. */
@Singleton
class ThemePreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("theme", Context.MODE_PRIVATE)
    private val _useDynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, true))
    val useDynamicColor: StateFlow<Boolean> = _useDynamicColor.asStateFlow()

    fun setUseDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
        _useDynamicColor.value = enabled
    }

    private companion object {
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
