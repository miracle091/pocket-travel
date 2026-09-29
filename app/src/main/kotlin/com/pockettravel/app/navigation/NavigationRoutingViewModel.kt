package com.pockettravel.app.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.map.RoutePoint
import com.pockettravel.feature.map.RoutingPackageState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.pockettravel.app.regions.localizedNames
import kotlinx.coroutines.Job
import java.util.Locale
import javax.inject.Inject

/**
 * Il pacchetto Percorsi da scaricare per la navigazione: installato, mancante o in download, e il
 * suo download dalla schermata ("Scarica i percorsi"). E' quello della regione da cui si e' aperta
 * la navigazione, finche' [findMissingRegion] non trova che la partenza o l'arrivo cadono in un'altra
 * regione senza percorsi ("Scarica i percorsi di <regione>"). Qui e non in feature:map, che non dipende
 * da core:sync; stesso schema di "Scarica la mappa" in RegionHubViewModel.
 */
@HiltViewModel
class NavigationRoutingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val regionRepository: RegionRepository,
    private val manifestClient: ManifestClient,
    private val regionSyncScheduler: RegionSyncScheduler,
) : ViewModel() {
    private val regionId: String = checkNotNull(savedStateHandle[PocketTravelDestinations.ARG_REGION_ID])
    private val destination = RoutePoint(
        checkNotNull(savedStateHandle.get<String>(PocketTravelDestinations.ARG_LATITUDE)).toDouble(),
        checkNotNull(savedStateHandle.get<String>(PocketTravelDestinations.ARG_LONGITUDE)).toDouble(),
    )

    // La regione senza percorsi che copre partenza o arrivo, dal manifest; null = la regione di partenza.
    private val missing = MutableStateFlow<RegionManifestEntry?>(null)
    private var findJob: Job? = null

    /** Nome della regione da scaricare quando non e' quella da cui si e' aperta la navigazione. */
    // Nella lingua dell'app: il displayName del manifest e' in italiano.
    val missingRegionName: StateFlow<String?> = missing.map { it?.localizedNames(Locale.getDefault())?.displayName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<RoutingPackageState> = missing.flatMapLatest { target ->
        val id = target?.regionId ?: regionId
        combine(
            regionRepository.observeInstalled().map { regions -> regions.firstOrNull { it.regionId == id }?.routingVersion != null },
            regionSyncScheduler.observeDownload(id),
        ) { installed, work ->
            when {
                work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> RoutingPackageState.DOWNLOADING
                installed -> RoutingPackageState.INSTALLED
                else -> RoutingPackageState.MISSING
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutingPackageState.UNKNOWN)

    /**
     * Senza dati di percorso: cerca nel manifest la regione, senza percorsi installati, in cui cadono la
     * partenza [from] o l'arrivo. Offline il manifest non c'e': resta il messaggio generico.
     */
    fun findMissingRegion(from: RoutePoint?) {
        // Una ricerca alla volta: un manifest arrivato tardi non sovrascrive il risultato piu' recente.
        findJob?.cancel()
        findJob = viewModelScope.launch {
            val regions = runCatching { manifestClient.fetchManifest().regions }.getOrNull()
            val installed = regionRepository.observeInstalled().first().filter { it.routingVersion != null }.map { it.regionId }.toSet()
            missing.value = regions?.let { missingRoutingRegion(it, installed, listOfNotNull(from, destination)) }
        }
    }

    fun download() {
        viewModelScope.launch {
            runCatching { missing.value ?: manifestClient.fetchManifest().regions.first { it.regionId == regionId } }
                .onSuccess { regionSyncScheduler.enqueueDownload(it, setOf(PackageKind.ROUTING)) }
        }
    }
}

/**
 * La regione del manifest da cui scaricare i percorsi per il primo dei [points] che nessuna regione con i
 * percorsi installati ([installedRoutingIds]) copre col suo riquadro: fra quelle senza percorsi che lo
 * contengono, la piu' piccola (l'Italia contiene San Marino). Null se tutti i punti sono coperti o
 * nessuna regione ne contiene uno scoperto.
 */
internal fun missingRoutingRegion(regions: List<RegionManifestEntry>, installedRoutingIds: Set<String>, points: List<RoutePoint>): RegionManifestEntry? {
    fun RegionManifestEntry.contains(point: RoutePoint): Boolean = map.source.let {
        point.longitude in it.minLon..it.maxLon && point.latitude in it.minLat..it.maxLat
    }
    val uncovered = points.firstOrNull { point -> regions.none { it.regionId in installedRoutingIds && it.contains(point) } } ?: return null
    return regions.filter { it.regionId !in installedRoutingIds && it.contains(uncovered) }
        .minByOrNull { (it.map.source.maxLon - it.map.source.minLon) * (it.map.source.maxLat - it.map.source.minLat) }
}
