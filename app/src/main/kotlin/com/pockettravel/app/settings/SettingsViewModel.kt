package com.pockettravel.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.NationalityPreferences
import com.pockettravel.core.sync.CatalogCheck
import com.pockettravel.core.sync.CatalogSettings
import com.pockettravel.core.sync.CustomCatalog
import com.pockettravel.feature.map.NavigationPreferences
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Preferenze delle Impostazioni (anche la nazionalita', che usano le Fonti ufficiali). */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val usageModePreferences: UsageModePreferences,
    private val nationalityPreferences: NationalityPreferences,
    private val navigationPreferences: NavigationPreferences,
    private val catalogSettings: CatalogSettings,
) : ViewModel() {
    val useDynamicColor: StateFlow<Boolean> = themePreferences.useDynamicColor
    val forceDark: StateFlow<Boolean> = themePreferences.forceDark
    val usageModes: StateFlow<Set<UsageMode>> = usageModePreferences.modes
    val accessible: StateFlow<Boolean> = usageModePreferences.accessible
    val wantsDirections: StateFlow<Boolean> = usageModePreferences.wantsDirections

    fun setUseDynamicColor(enabled: Boolean) = themePreferences.setUseDynamicColor(enabled)

    fun setForceDark(enabled: Boolean) = themePreferences.setForceDark(enabled)

    val nationality: StateFlow<String?> = nationalityPreferences.nationality

    fun setNationality(countryCode: String) = nationalityPreferences.setNationality(countryCode)

    fun setUsageModes(modes: Set<UsageMode>) = usageModePreferences.setModes(modes)

    fun setAccessible(accessible: Boolean) = usageModePreferences.setAccessible(accessible)

    fun setWantsDirections(wants: Boolean) = usageModePreferences.setWantsDirections(wants)

    val stopGpsOnArrival: StateFlow<Boolean> = navigationPreferences.stopGpsOnArrival

    fun setStopGpsOnArrival(stop: Boolean) = navigationPreferences.setStopGpsOnArrival(stop)

    val walkingHaptics: StateFlow<Boolean> = navigationPreferences.walkingHaptics

    fun setWalkingHaptics(enabled: Boolean) = navigationPreferences.setWalkingHaptics(enabled)

    /** Catalogo scelto al posto di quello ufficiale; null = ufficiale. */
    val customCatalog: CustomCatalog? = catalogSettings.saved()

    private val _catalogState = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val catalogState: StateFlow<CatalogState> = _catalogState.asStateFlow()

    /** Prova il catalogo e, se e' valido, lo salva: la schermata riavvia allora l'app. */
    fun useCatalog(manifestUrl: String, publicKey: String) {
        if (_catalogState.value == CatalogState.Checking) return
        _catalogState.value = CatalogState.Checking
        viewModelScope.launch {
            val check = catalogSettings.checkAndSave(manifestUrl, publicKey)
            _catalogState.value = if (check == CatalogCheck.OK) CatalogState.Restart else CatalogState.Failed(check)
        }
    }

    fun useOfficialCatalog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { catalogSettings.reset() }
            _catalogState.value = CatalogState.Restart
        }
    }

    fun clearCatalogError() {
        if (_catalogState.value is CatalogState.Failed) _catalogState.value = CatalogState.Idle
    }
}

/** Prova di un catalogo dalle Impostazioni; [Restart] = salvato, l'app va riavviata. */
sealed interface CatalogState {
    data object Idle : CatalogState
    data object Checking : CatalogState
    data class Failed(val check: CatalogCheck) : CatalogState
    data object Restart : CatalogState
}
