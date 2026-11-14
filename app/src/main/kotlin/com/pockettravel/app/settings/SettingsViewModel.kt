package com.pockettravel.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.sync.AlertNotificationSwitch
import com.pockettravel.core.sync.CatalogCheck
import com.pockettravel.core.sync.CatalogPreferences
import com.pockettravel.core.sync.CustomCatalog
import com.pockettravel.feature.map.NavigationPreferences
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Tutto quello che le Impostazioni leggono dal ViewModel, in un solo stato. */
data class SettingsUiState(
    val useDynamicColor: Boolean = true,
    val forceDark: Boolean = false,
    val usageModes: Set<UsageMode> = emptySet(),
    val accessible: Boolean = false,
    val wantsDirections: Boolean = false,
    val nationality: String? = null,
    val stopGpsOnArrival: Boolean = true,
    val walkingHaptics: Boolean = true,
    val voiceGuidance: Boolean = true,
    val catalogState: CatalogState = CatalogState.Idle,
)

private data class UsageSettings(val modes: Set<UsageMode>, val accessible: Boolean, val wantsDirections: Boolean)

/** Preferenze delle Impostazioni (anche la nazionalita', che usano le Fonti ufficiali). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val usageModePreferences: UsageModePreferences,
    private val nationalityPreferences: NationalityPreferences,
    private val navigationPreferences: NavigationPreferences,
    private val catalogPreferences: CatalogPreferences,
    /** Interruttore "Notifiche delle allerte" (Altro > Impostazioni). */
    val alertNotifications: AlertNotificationSwitch,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    fun setUseDynamicColor(enabled: Boolean) = themePreferences.setUseDynamicColor(enabled)

    fun setForceDark(enabled: Boolean) = themePreferences.setForceDark(enabled)

    /** Anche fuori dalle Impostazioni (Altro): la schermata la legge da [uiState]. */
    val nationality: StateFlow<String?> = nationalityPreferences.nationality

    fun setNationality(countryCode: String) = nationalityPreferences.setNationality(countryCode)

    fun setUsageModes(modes: Set<UsageMode>) = usageModePreferences.setModes(modes)

    fun setAccessible(accessible: Boolean) = usageModePreferences.setAccessible(accessible)

    fun setWantsDirections(wants: Boolean) = usageModePreferences.setWantsDirections(wants)

    fun setStopGpsOnArrival(stop: Boolean) = navigationPreferences.setStopGpsOnArrival(stop)

    fun setWalkingHaptics(enabled: Boolean) = navigationPreferences.setWalkingHaptics(enabled)

    fun setVoiceGuidance(enabled: Boolean) = navigationPreferences.setVoiceGuidance(enabled)

    /** Catalogo scelto al posto di quello ufficiale; null = ufficiale. */
    val customCatalog: CustomCatalog? = catalogPreferences.saved()

    private val catalogState = MutableStateFlow<CatalogState>(CatalogState.Idle)

    private val appearance = combine(themePreferences.useDynamicColor, themePreferences.forceDark, ::Pair)
    private val usage = combine(
        usageModePreferences.modes, usageModePreferences.accessible, usageModePreferences.wantsDirections, ::UsageSettings,
    )
    private val navigation = combine(navigationPreferences.stopGpsOnArrival, navigationPreferences.walkingHaptics, navigationPreferences.voiceGuidance, ::Triple)

    /** Tutto quello che le Impostazioni leggono, in un solo stato. */
    val uiState: StateFlow<SettingsUiState> = combine(
        appearance, usage, nationalityPreferences.nationality, navigation, catalogState,
    ) { (dynamicColor, dark), usageSettings, nationality, (stopGps, haptics, voice), catalog ->
        SettingsUiState(
            useDynamicColor = dynamicColor,
            forceDark = dark,
            usageModes = usageSettings.modes,
            accessible = usageSettings.accessible,
            wantsDirections = usageSettings.wantsDirections,
            nationality = nationality,
            stopGpsOnArrival = stopGps,
            walkingHaptics = haptics,
            voiceGuidance = voice,
            catalogState = catalog,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(
            useDynamicColor = themePreferences.useDynamicColor.value,
            forceDark = themePreferences.forceDark.value,
            usageModes = usageModePreferences.modes.value,
            accessible = usageModePreferences.accessible.value,
            wantsDirections = usageModePreferences.wantsDirections.value,
            nationality = nationalityPreferences.nationality.value,
            stopGpsOnArrival = navigationPreferences.stopGpsOnArrival.value,
            walkingHaptics = navigationPreferences.walkingHaptics.value,
            voiceGuidance = navigationPreferences.voiceGuidance.value,
        ),
    )

    /** Prova il catalogo e, se e' valido, lo salva: la schermata riavvia allora l'app. */
    fun useCatalog(manifestUrl: String, publicKey: String) {
        if (catalogState.value == CatalogState.Checking) return
        catalogState.value = CatalogState.Checking
        viewModelScope.launch {
            val check = catalogPreferences.checkAndSave(manifestUrl, publicKey)
            catalogState.value = if (check == CatalogCheck.OK) CatalogState.Restart else CatalogState.Failed(check)
        }
    }

    fun useOfficialCatalog() {
        viewModelScope.launch {
            withContext(ioDispatcher) { catalogPreferences.reset() }
            catalogState.value = CatalogState.Restart
        }
    }

    fun clearCatalogError() {
        if (catalogState.value is CatalogState.Failed) catalogState.value = CatalogState.Idle
    }
}

/** Prova di un catalogo dalle Impostazioni; [Restart] = salvato, l'app va riavviata. */
sealed interface CatalogState {
    data object Idle : CatalogState
    data object Checking : CatalogState
    data class Failed(val check: CatalogCheck) : CatalogState
    data object Restart : CatalogState
}
