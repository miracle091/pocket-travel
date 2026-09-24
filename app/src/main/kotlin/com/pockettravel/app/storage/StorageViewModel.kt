package com.pockettravel.app.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.InstalledGuides
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.ai.AiSettingsStore
import com.pockettravel.feature.ai.LlmModelManager
import com.pockettravel.feature.ai.OnDeviceLlmEngine
import com.pockettravel.feature.ai.selectedModelDefinition
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StoragePackageItem(val kind: PackageKind, val sizeBytes: Long?)

data class StorageRegionItem(
    val regionId: String,
    val displayName: String,
    val countryCode: String?,
    val sizeBytes: Long,
    // Solo i pacchetti installati.
    val packages: List<StoragePackageItem> = emptyList(),
)

data class StorageUiState(
    val guides: InstalledGuides? = null,
    val installedRegions: List<StorageRegionItem> = emptyList(),
    val regionsSizeBytes: Long = 0L,
    val isModelDownloaded: Boolean = false,
    val modelSizeBytes: Long = 0L,
    val availableBytes: Long = 0L,
)

private data class ModelState(val isDownloaded: Boolean, val sizeBytes: Long)

/**
 * Vista unica dello spazio occupato da guide, pacchetti delle regioni e modello IA locale, con
 * cancellazione: tutto cio' che l'app scarica su richiesta.
 */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val modelManager: LlmModelManager,
    private val engine: OnDeviceLlmEngine,
    private val aiSettingsStore: AiSettingsStore,
) : ViewModel() {

    private val modelState = MutableStateFlow(currentModelState())
    private val availableBytes = MutableStateFlow(regionRepository.availableStorageBytes())

    val uiState: StateFlow<StorageUiState> = combine(
        regionRepository.observeInstalled(),
        regionRepository.observeInstalledGuides(),
        modelState,
        availableBytes,
    ) { regions, guides, model, available ->
        StorageUiState(
            guides = guides,
            installedRegions = regions.map { region ->
                val packages = PackageKind.entries
                    .filter { region.versionOf(it) != null }
                    .map { StoragePackageItem(it, regionRepository.packageBytes(region, it)) }
                StorageRegionItem(region.regionId, region.displayName, region.countryCode, region.sizeBytes, packages)
            },
            regionsSizeBytes = regions.sumOf { it.sizeBytes } + (guides?.sizeBytes ?: 0L),
            isModelDownloaded = model.isDownloaded,
            modelSizeBytes = model.sizeBytes,
            availableBytes = available,
        )
    }
        // packageBytes legge le dimensioni di mappa e routing dal disco.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    fun deleteRegion(regionId: String) {
        viewModelScope.launch {
            regionRepository.remove(regionId)
            refreshDeviceState()
        }
    }

    fun deletePackage(regionId: String, kind: PackageKind) {
        viewModelScope.launch {
            regionRepository.removePackage(regionId, kind)
            refreshDeviceState()
        }
    }

    fun downloadGuides() = regionSyncScheduler.enqueueGuidesSync(onlyOnWifi = false)

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
