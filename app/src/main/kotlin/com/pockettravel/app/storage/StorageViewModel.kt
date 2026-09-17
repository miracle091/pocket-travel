package com.pockettravel.app.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.feature.ai.AiSettingsStore
import com.pockettravel.feature.ai.LlmModelManager
import com.pockettravel.feature.ai.OnDeviceLlmEngine
import com.pockettravel.feature.ai.selectedModelDefinition
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StorageRegionItem(
    val regionId: String,
    val displayName: String,
    val sizeBytes: Long,
)

data class StorageUiState(
    val installedRegions: List<StorageRegionItem> = emptyList(),
    val regionsSizeBytes: Long = 0L,
    val isModelDownloaded: Boolean = false,
    val modelSizeBytes: Long = 0L,
    val availableBytes: Long = 0L,
)

private data class ModelState(val isDownloaded: Boolean, val sizeBytes: Long)

/**
 * Vista unica dello spazio occupato da regioni scaricate e modello IA locale, con
 * cancellazione — le due sole cose che, tra i pacchetti regionali (Fase 3) e il modello
 * (Fase 4), occupano spazio scaricabile su richiesta.
 */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val modelManager: LlmModelManager,
    private val engine: OnDeviceLlmEngine,
    private val aiSettingsStore: AiSettingsStore,
) : ViewModel() {

    private val modelState = MutableStateFlow(currentModelState())
    private val availableBytes = MutableStateFlow(regionRepository.availableStorageBytes())

    val uiState: StateFlow<StorageUiState> = combine(
        regionRepository.observeInstalled(),
        modelState,
        availableBytes,
    ) { regions, model, available ->
        StorageUiState(
            installedRegions = regions.map { StorageRegionItem(it.regionId, it.displayName, it.sizeBytes) },
            regionsSizeBytes = regions.sumOf { it.sizeBytes },
            isModelDownloaded = model.isDownloaded,
            modelSizeBytes = model.sizeBytes,
            availableBytes = available,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    fun deleteRegion(regionId: String) {
        viewModelScope.launch {
            regionRepository.remove(regionId)
            refreshDeviceState()
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            engine.releaseAndDelete()
            refreshDeviceState()
        }
    }

    private fun refreshDeviceState() {
        modelState.value = currentModelState()
        availableBytes.value = regionRepository.availableStorageBytes()
    }

    private fun currentModelState(): ModelState {
        val definition = aiSettingsStore.selectedModelDefinition()
        return ModelState(modelManager.isDownloaded(definition), modelManager.sizeOnDisk(definition))
    }
}
