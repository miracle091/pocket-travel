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
    val usageMode: StateFlow<UsageMode?> = usageModePreferences.mode

    fun setUseDynamicColor(enabled: Boolean) = themePreferences.setUseDynamicColor(enabled)

    fun setUsageMode(mode: UsageMode) = usageModePreferences.setMode(mode)
}
