package com.pockettravel.app.onboarding

import androidx.lifecycle.ViewModel
import com.pockettravel.feature.ai.DeviceAiCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingPreferences: OnboardingPreferences,
    deviceAiCapability: DeviceAiCapability,
) : ViewModel() {

    // Fascia di RAM statica per la durata della sessione: nessun bisogno di un Flow, un val letto
    // una volta all'apertura dell'onboarding basta (vedi RAM-aware step in OnboardingScreen).
    val isOnDeviceAiSupported: Boolean = deviceAiCapability.isOnDeviceAiSupported()

    private val _isCompleted = MutableStateFlow(onboardingPreferences.isCompleted())
    val isCompleted: StateFlow<Boolean> = _isCompleted.asStateFlow()

    fun complete() {
        onboardingPreferences.markCompleted()
        _isCompleted.value = true
    }
}
