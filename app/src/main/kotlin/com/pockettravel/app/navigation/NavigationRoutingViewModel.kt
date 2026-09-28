package com.pockettravel.app.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.map.RoutingPackageState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Il pacchetto Percorsi della regione della navigazione: installato, mancante o in download, e il
 * suo download dalla schermata ("Scarica i percorsi"). Qui e non in feature:map, che non dipende da
 * core:sync; stesso schema di "Scarica la mappa" in RegionHubViewModel.
 */
@HiltViewModel
class NavigationRoutingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    regionRepository: RegionRepository,
    private val manifestClient: ManifestClient,
    private val regionSyncScheduler: RegionSyncScheduler,
) : ViewModel() {
    private val regionId: String = checkNotNull(savedStateHandle[PocketTravelDestinations.ARG_REGION_ID])

    val state: StateFlow<RoutingPackageState> = combine(
        regionRepository.observeInstalled().map { regions -> regions.firstOrNull { it.regionId == regionId }?.routingVersion != null },
        regionSyncScheduler.observeDownload(regionId),
    ) { installed, work ->
        when {
            work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> RoutingPackageState.DOWNLOADING
            installed -> RoutingPackageState.INSTALLED
            else -> RoutingPackageState.MISSING
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutingPackageState.UNKNOWN)

    fun download() {
        viewModelScope.launch {
            runCatching { manifestClient.fetchManifest().regions.first { it.regionId == regionId } }
                .onSuccess { regionSyncScheduler.enqueueDownload(it, setOf(PackageKind.ROUTING)) }
        }
    }
}
