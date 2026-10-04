package com.pockettravel.app.storage

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.app.R
import com.pockettravel.core.data.InstalledGuides
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.ai.AiSettingsStore
import com.pockettravel.feature.ai.LlmModelManager
import com.pockettravel.feature.ai.OnDeviceLlmEngine
import com.pockettravel.feature.ai.selectedModelDefinition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pockettravel.app.regions.localizedInstalledName
import java.util.Locale
import javax.inject.Inject

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
    // Messaggio una tantum (es. eliminazione fallita): Snackbar, poi onMessageShown().
    @StringRes val message: Int? = null,
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
    // Letto fuori dal thread principale (StorageManager.getAllocatableBytes puo' bloccare): 0 finche' non arriva.
    private val availableBytes = MutableStateFlow(0L)
    private val message = MutableStateFlow<Int?>(null)

    init {
        refreshAvailableBytes()
    }

    val uiState: StateFlow<StorageUiState> = combine(
        regionRepository.observeInstalled(),
        regionRepository.observeInstalledGuides(),
        modelState,
        availableBytes,
        message,
    ) { regions, guides, model, available, currentMessage ->
        StorageUiState(
            guides = guides,
            installedRegions = regions.map { region ->
                val packages = PackageKind.entries
                    .filter { region.versionOf(it) != null }
                    .map { StoragePackageItem(it, regionRepository.packageBytes(region, it)) }
                StorageRegionItem(
                    region.regionId,
                    localizedInstalledName(region.displayName, region.countryCode, Locale.getDefault()),
                    region.countryCode,
                    region.sizeBytes,
                    packages,
                )
            },
            regionsSizeBytes = regions.sumOf { it.sizeBytes } + (guides?.sizeBytes ?: 0L),
            isModelDownloaded = model.isDownloaded,
            modelSizeBytes = model.sizeBytes,
            availableBytes = available,
            message = currentMessage,
        )
    }
        // packageBytes legge le dimensioni di mappa e routing dal disco.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    fun deleteRegion(regionId: String) {
        regionSyncScheduler.cancelDownload(regionId)
        viewModelScope.launch {
            removeOrReport { regionRepository.remove(regionId) }
            refreshDeviceState()
        }
    }

    fun deletePackage(regionId: String, kind: PackageKind) {
        viewModelScope.launch {
            removeOrReport { regionRepository.removePackage(regionId, kind) }
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

    fun onMessageShown() {
        message.value = null
    }

    // L'eliminazione dei file puo' fallire (check nel repository): un messaggio invece del crash.
    private suspend fun removeOrReport(remove: suspend () -> Unit) {
        try {
            remove()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            message.value = R.string.regions_delete_error
        }
    }

    private fun refreshDeviceState() {
        modelState.value = currentModelState()
        refreshAvailableBytes()
    }

    private fun refreshAvailableBytes() {
        viewModelScope.launch { availableBytes.value = withContext(Dispatchers.IO) { regionRepository.availableStorageBytes() } }
    }

    private fun currentModelState(): ModelState {
        val definition = aiSettingsStore.selectedModelDefinition()
        return ModelState(modelManager.isDownloaded(definition), modelManager.sizeOnDisk(definition))
    }
}
