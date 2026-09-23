package com.pockettravel.app.more

import androidx.lifecycle.ViewModel
import com.pockettravel.app.settings.ThemePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class MoreViewModel @Inject constructor(private val themePreferences: ThemePreferences) : ViewModel() {
    val useDynamicColor: StateFlow<Boolean> = themePreferences.useDynamicColor

    fun setUseDynamicColor(enabled: Boolean) = themePreferences.setUseDynamicColor(enabled)
}
