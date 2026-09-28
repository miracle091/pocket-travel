package com.pockettravel.app.onboarding

import androidx.lifecycle.ViewModel
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.feature.ai.DeviceAiCapability
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingPreferences: OnboardingPreferences,
    deviceAiCapability: DeviceAiCapability,
    private val usageModePreferences: UsageModePreferences,
    private val nationalityPreferences: NationalityPreferences,
) : ViewModel() {

    val usageMode: StateFlow<UsageMode?> = usageModePreferences.mode
    val accessible: StateFlow<Boolean> = usageModePreferences.accessible
    val wantsDirections: StateFlow<Boolean> = usageModePreferences.wantsDirections

    fun setUsageMode(mode: UsageMode) = usageModePreferences.setMode(mode)

    fun setAccessible(accessible: Boolean) = usageModePreferences.setAccessible(accessible)

    fun setWantsDirections(wants: Boolean) = usageModePreferences.setWantsDirections(wants)

    val nationality: StateFlow<String?> = nationalityPreferences.nationality

    fun setNationality(countryCode: String) = nationalityPreferences.setNationality(countryCode)

    // Fascia di RAM statica per la durata della sessione: nessun bisogno di un Flow, un val letto
    // una volta all'apertura dell'onboarding basta (vedi RAM-aware step in OnboardingScreen).
    val isOnDeviceAiSupported: Boolean = deviceAiCapability.isOnDeviceAiSupported()

    fun complete() {
        onboardingPreferences.markCompleted()
    }
}
