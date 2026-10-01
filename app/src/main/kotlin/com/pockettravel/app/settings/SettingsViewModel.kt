package com.pockettravel.app.settings

import androidx.lifecycle.ViewModel
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.feature.map.NavigationPreferences
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Preferenze delle Impostazioni (anche la nazionalita', che usano le Fonti ufficiali). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val usageModePreferences: UsageModePreferences,
    private val nationalityPreferences: NationalityPreferences,
    private val navigationPreferences: NavigationPreferences,
) : ViewModel() {
    val useDynamicColor: StateFlow<Boolean> = themePreferences.useDynamicColor
    val forceDark: StateFlow<Boolean> = themePreferences.forceDark
    val usageMode: StateFlow<UsageMode?> = usageModePreferences.mode
    val accessible: StateFlow<Boolean> = usageModePreferences.accessible
    val wantsDirections: StateFlow<Boolean> = usageModePreferences.wantsDirections

    fun setUseDynamicColor(enabled: Boolean) = themePreferences.setUseDynamicColor(enabled)

    fun setForceDark(enabled: Boolean) = themePreferences.setForceDark(enabled)

    val nationality: StateFlow<String?> = nationalityPreferences.nationality

    fun setNationality(countryCode: String) = nationalityPreferences.setNationality(countryCode)

    fun setUsageMode(mode: UsageMode) = usageModePreferences.setMode(mode)

    fun setAccessible(accessible: Boolean) = usageModePreferences.setAccessible(accessible)

    fun setWantsDirections(wants: Boolean) = usageModePreferences.setWantsDirections(wants)

    val stopGpsOnArrival: StateFlow<Boolean> = navigationPreferences.stopGpsOnArrival

    fun setStopGpsOnArrival(stop: Boolean) = navigationPreferences.setStopGpsOnArrival(stop)

    val walkingHaptics: StateFlow<Boolean> = navigationPreferences.walkingHaptics

    fun setWalkingHaptics(enabled: Boolean) = navigationPreferences.setWalkingHaptics(enabled)
}
