package com.pockettravel.app.onboarding

import androidx.lifecycle.ViewModel
import com.pockettravel.feature.ai.DeviceAiCapability
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingPreferences: OnboardingPreferences,
    deviceAiCapability: DeviceAiCapability,
    private val usageModePreferences: UsageModePreferences,
) : ViewModel() {

    val usageMode: StateFlow<UsageMode?> = usageModePreferences.mode

    fun setUsageMode(mode: UsageMode) = usageModePreferences.setMode(mode)

    // Fascia di RAM statica per la durata della sessione: nessun bisogno di un Flow, un val letto
    // una volta all'apertura dell'onboarding basta (vedi RAM-aware step in OnboardingScreen).
    val isOnDeviceAiSupported: Boolean = deviceAiCapability.isOnDeviceAiSupported()

    fun complete() {
        onboardingPreferences.markCompleted()
    }
}
