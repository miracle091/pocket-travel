package com.pockettravel.app.more

import androidx.lifecycle.ViewModel
import com.pockettravel.app.settings.ThemePreferences
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class MoreViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val usageModePreferences: UsageModePreferences,
) : ViewModel() {
    val useDynamicColor: StateFlow<Boolean> = themePreferences.useDynamicColor
    val forceDark: StateFlow<Boolean> = themePreferences.forceDark
    val usageMode: StateFlow<UsageMode?> = usageModePreferences.mode
    val accessible: StateFlow<Boolean> = usageModePreferences.accessible

    fun setUseDynamicColor(enabled: Boolean) = themePreferences.setUseDynamicColor(enabled)

    fun setForceDark(enabled: Boolean) = themePreferences.setForceDark(enabled)

    fun setUsageMode(mode: UsageMode) = usageModePreferences.setMode(mode)

    fun setAccessible(accessible: Boolean) = usageModePreferences.setAccessible(accessible)
}
