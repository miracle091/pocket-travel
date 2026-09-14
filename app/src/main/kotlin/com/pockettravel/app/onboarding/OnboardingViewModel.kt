package com.pockettravel.app.onboarding

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingPreferences: OnboardingPreferences,
) : ViewModel() {

    private val _isCompleted = MutableStateFlow(onboardingPreferences.isCompleted())
    val isCompleted: StateFlow<Boolean> = _isCompleted.asStateFlow()

    fun complete() {
        onboardingPreferences.markCompleted()
        _isCompleted.value = true
    }
}
