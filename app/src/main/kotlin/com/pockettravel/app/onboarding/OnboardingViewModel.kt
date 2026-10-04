package com.pockettravel.app.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.ui.ContextualHints
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val onboardingPreferences: OnboardingPreferences,
    @ApplicationContext private val context: Context,
    private val usageModePreferences: UsageModePreferences,
    private val nationalityPreferences: NationalityPreferences,
) : ViewModel() {

    val usageModes: StateFlow<Set<UsageMode>> = usageModePreferences.modes
    val accessible: StateFlow<Boolean> = usageModePreferences.accessible
    val wantsDirections: StateFlow<Boolean> = usageModePreferences.wantsDirections

    fun setUsageModes(modes: Set<UsageMode>) = usageModePreferences.setModes(modes)

    fun setAccessible(accessible: Boolean) = usageModePreferences.setAccessible(accessible)

    fun setWantsDirections(wants: Boolean) = usageModePreferences.setWantsDirections(wants)

    val nationality: StateFlow<String?> = nationalityPreferences.nationality

    fun setNationality(countryCode: String) = nationalityPreferences.setNationality(countryCode)

    fun complete() {
        onboardingPreferences.markCompleted()
        // Anche con "Rivedi il tutorial": i suggerimenti delle schede tornano a comparire alla prima apertura.
        ContextualHints.reset(context)
    }
}
