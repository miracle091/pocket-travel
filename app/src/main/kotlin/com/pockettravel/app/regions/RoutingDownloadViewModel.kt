package com.pockettravel.app.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.map.MissingRegion
import com.pockettravel.feature.map.RoutePoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * I Percorsi che mancano al Navigatore, nella tab della regione e in quello della barra principale: quali regioni
 * scaricare tra partenza e arrivo, il download e il suo avanzamento. [load] dice la regione aperta (null nel
 * Navigatore generico): senza altre indicazioni "Scarica i percorsi" scarica i suoi.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RoutingDownloadViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val manifestClient: ManifestClient,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val countryLocator: CountryLocator,
    private val routingVariantPreferences: RoutingVariantPreferences,
) : ViewModel() {
    private val regionId = MutableStateFlow<String?>(null)

    /** Ci sono nazioni scaricate (null finche' non si sa): senza, il Navigatore della barra invita a scaricarne una. */
    val hasRegions: StateFlow<Boolean?> = regionRepository.observeInstalled().map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Le regioni di cui il Navigatore segue il download dei Percorsi: altre, se sono quelle tra partenza e arrivo.
    private val targets = MutableStateFlow<List<String>>(emptyList())
    private val downloadIds: Flow<List<String>> = combine(regionId, targets) { id, targets -> targets.ifEmpty { listOfNotNull(id) } }

    /**
     * Avanzamento 0..1 del download in corso, null se nessuno. Con piu' regioni, la media: quelle finite contano 1,
     * quelle in coda 0.
     */
    val downloadProgress: StateFlow<Float?> = downloadIds.flatMapLatest { ids ->
        if (ids.isEmpty()) return@flatMapLatest flowOf(null)
        combine(ids.map { regionSyncScheduler.observeDownload(it) }) { works ->
            val active = { work: WorkInfo? -> work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED }
            if (works.none(active)) return@combine null
            works.map { work ->
                when {
                    work?.state == WorkInfo.State.SUCCEEDED -> 1f
                    !active(work) -> 0f
                    else -> {
                        val total = work!!.progress.getLong(RegionPackageDownloadWorker.KEY_TOTAL_BYTES, 0L)
                        if (total > 0) work.progress.getLong(RegionPackageDownloadWorker.KEY_BYTES_DOWNLOADED, 0L) / total.toFloat() else 0f
                    }
                }
            }.average().toFloat()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Il catalogo non si e' potuto leggere nell'ultimo tentativo di scaricare (offline).
    private val manifestFailed = MutableStateFlow(false)

    /** L'ultimo download (di una delle regioni) e' finito in errore (lavoro fallito o catalogo non raggiungibile): si puo' riprovare. */
    val downloadFailed: StateFlow<Boolean> = downloadIds.flatMapLatest { ids ->
        if (ids.isEmpty()) return@flatMapLatest manifestFailed
        combine(combine(ids.map { regionSyncScheduler.observeDownload(it) }) { it.toList() }, manifestFailed) { works, noManifest ->
            noManifest || works.any { it?.state == WorkInfo.State.FAILED }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun load(regionId: String?) {
        if (regionId == this.regionId.value) return
        this.regionId.value = regionId
        targets.value = emptyList()
    }

    /** "Scarica i percorsi" dal Navigatore: della regione aperta, o di [targetIds] se sono quelle tra partenza e arrivo. */
    fun downloadRouting(targetIds: List<String> = emptyList()) {
        targets.value = targetIds
        val ids = targetIds.ifEmpty { listOfNotNull(regionId.value) }
        // Percorsi solo per l'auto proposti da scaricare (a piedi, in bici): si scaricano quelli completi.
        ids.filter { it in routingVariantPreferences.installedCarOnly.value }.forEach { routingVariantPreferences.setCarOnly(it, false) }
        viewModelScope.launch {
            manifestFailed.value = false
            // Un errore del catalogo non si butta: il Navigatore lo mostra e lascia riprovare, invece di restare fermo.
            runCatching { manifestClient.fetchManifest().regions.filter { it.regionId in ids } }
                .onSuccess { entries -> entries.forEach { regionSyncScheduler.enqueueDownload(it, setOf(PackageKind.ROUTING)) } }
                .onFailure { manifestFailed.value = true }
        }
    }

    /**
     * Le regioni del catalogo senza Percorsi tra partenza e arrivo ([points]: partenza, linea in mezzo, arrivo), in
     * ordine, col nome nella lingua dell'app e il peso dei Percorsi. Vuota se il catalogo non si legge (offline) o
     * tutto e' coperto: il Navigatore resta col messaggio generico.
     */
    suspend fun missingRoutingRegions(points: List<RoutePoint>, usableRouting: Set<String>): List<MissingRegion> {
        val regions = runCatching { manifestClient.fetchManifest().regions }.getOrNull() ?: return emptyList()
        val carOnly = routingVariantPreferences.installedCarOnly.value
        return withContext(Dispatchers.Default) {
            missingRoutingRegions(regions, usableRouting, points, { countryLocator.countryAt(it.latitude, it.longitude) }, countryLocator.neighbours, countryLocator.centres)
        }.map { missing ->
            MissingRegion(missing.regionId, missing.localizedNames(Locale.getDefault()).displayName, missing.routing.files.sumOf { it.sizeBytes }, carOnly = missing.regionId in carOnly)
        }
    }
}
