package com.pockettravel.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.EmergencyNumbersRepository
import com.pockettravel.core.data.GuideRepository
import com.pockettravel.core.data.GuideSection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GuideUiState(
    val sections: List<GuideSection> = emptyList(),
    val emergencyNumbers: EmergencyNumbers? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@HiltViewModel
class GuideViewModel @Inject constructor(
    private val guideRepository: GuideRepository,
    private val emergencyNumbersRepository: EmergencyNumbersRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GuideUiState())
    val uiState: StateFlow<GuideUiState> = _uiState.asStateFlow()

    // regionId non e' costruito nella ViewModel (nessun uso di SavedStateHandle in questo
    // progetto): arriva a runtime dalla composable, come AiAssistantViewModel.ask(regionId) —
    // stesso pattern, per coerenza di stile.
    private var loadedForRegionId: String? = null

    fun load(regionId: String) {
        if (loadedForRegionId == regionId) return
        loadedForRegionId = regionId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val sections = guideRepository.sectionsFor(regionId)
                val emergencyNumbers = emergencyNumbersRepository.forRegion(regionId)
                _uiState.update { it.copy(sections = sections, emergencyNumbers = emergencyNumbers, isLoading = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                loadedForRegionId = null
                _uiState.update { it.copy(isLoading = false, errorMessage = "Impossibile caricare la guida per questa regione.") }
            }
        }
    }
}
