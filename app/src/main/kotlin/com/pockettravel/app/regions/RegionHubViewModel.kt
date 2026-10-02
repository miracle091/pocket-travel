package com.pockettravel.app.regions

import com.pockettravel.core.data.LastKnownPosition
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.attachTransitFeeds
import com.pockettravel.core.sync.regionTransitFeeds
import com.pockettravel.feature.ai.AiAvailability
import com.pockettravel.feature.map.MissingRegion
import com.pockettravel.feature.map.RoutePoint
import com.pockettravel.feature.map.TransitPackageState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import javax.inject.Inject

/** Stato della mappa della regione aperta: i pacchetti si installano separatamente, puo' mancare. */
// LOADING: stato non ancora noto, per non mostrare la mappa (o il suo stato vuoto) prima del primo valore.
enum class RegionMapState { LOADING, INSTALLED, MISSING, DOWNLOADING }

// Prima di questo, RegionHubScreen mostrava il regionId grezzo (es. "italia") nella TopAppBar
// invece del nome regione reale.
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RegionHubViewModel @Inject constructor(
    private val regionRepository: RegionRepository,
    private val recentRegionPreferences: RecentRegionPreferences,
    private val manifestClient: ManifestClient,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val transitClient: TransitClient,
    private val transitNetworkPreferences: TransitNetworkPreferences,
    private val lastKnownPosition: LastKnownPosition,
    private val countryLocator: CountryLocator,
    private val aiAvailability: AiAvailability,
) : ViewModel() {

    // La tab IA c'e' solo con un modello scaricato o una chiave API (si configurano nelle Impostazioni).
    val aiAvailable: StateFlow<Boolean> = aiAvailability.available

    // Dopo un cambio di lingua: i modelli addestrati valgono solo per la lingua delle guide.
    fun refreshAiAvailability() = aiAvailability.refresh()

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    // true solo dopo che il caricamento ha escluso la regione dal database (mai installata, o
    // installata in una sessione precedente e poi eliminata) — RegionHubScreen ci naviga via in
    // automatico invece di mostrare guida/mappa vuote per un regionId ormai inesistente. Serve
    // soprattutto quando questa schermata e' la rotta di avvio dell'app (vedi StartDestinationViewModel,
    // basata sull'ultima regione aperta secondo RecentRegionPreferences, non su cio' che e' ancora
    // davvero installato).
    private val _regionMissing = MutableStateFlow(false)
    val regionMissing: StateFlow<Boolean> = _regionMissing.asStateFlow()

    private val regionId = MutableStateFlow<String?>(null)

    val mapState: StateFlow<RegionMapState> = regionId.filterNotNull().flatMapLatest { id ->
        combine(
            regionRepository.observeInstalled().map { regions -> regions.firstOrNull { it.regionId == id }?.mapVersion != null },
            regionSyncScheduler.observeDownload(id),
        ) { hasMap, work -> mapState(hasMap, work) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegionMapState.LOADING)

    // Le regioni di cui il Navigatore segue il download dei Percorsi: altre, se sono quelle tra partenza e arrivo.
    private val routingTargets = MutableStateFlow<List<String>>(emptyList())
    private val downloadIds: Flow<List<String>> = combine(regionId.filterNotNull(), routingTargets) { id, targets -> targets.ifEmpty { listOf(id) } }

    /**
     * Avanzamento 0..1 del download in corso (per esempio i Percorsi dal Navigatore), null se nessuno. Con piu'
     * regioni, la media: quelle finite contano 1, quelle in coda 0.
     */
    val downloadProgress: StateFlow<Float?> = downloadIds.flatMapLatest { ids ->
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

    // Il catalogo non si e' potuto leggere nell'ultimo tentativo di scaricare un pacchetto (offline).
    private val manifestFailed = MutableStateFlow(false)

    /** L'ultimo download (di una delle regioni) e' finito in errore (lavoro fallito o catalogo non raggiungibile): si puo' riprovare. */
    val downloadFailed: StateFlow<Boolean> = downloadIds.flatMapLatest { ids ->
        combine(combine(ids.map { regionSyncScheduler.observeDownload(it) }) { it.toList() }, manifestFailed) { works, noManifest ->
            noManifest || works.any { it?.state == WorkInfo.State.FAILED }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // La regione ha reti dei mezzi pubblici nel catalogo (letto solo se gli orari non sono ancora installati);
    // null finche' non si sa (offline, catalogo non letto).
    private val transitOffered = MutableStateFlow<Boolean?>(null)

    // Orari dei mezzi pubblici per la scheda delle fermate: installati, in scaricamento, scaricabili o non offerti.
    val transitState: StateFlow<TransitPackageState> = regionId.filterNotNull().flatMapLatest { id ->
        combine(
            regionRepository.observeInstalled().map { regions -> regions.firstOrNull { it.regionId == id }?.transitVersion != null },
            regionSyncScheduler.observeDownload(id),
            transitOffered,
        ) { installed, work, offered ->
            when {
                installed -> TransitPackageState.INSTALLED
                work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> TransitPackageState.DOWNLOADING
                offered == true -> TransitPackageState.AVAILABLE
                offered == false -> TransitPackageState.NOT_OFFERED
                else -> TransitPackageState.UNKNOWN
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransitPackageState.UNKNOWN)

    fun load(regionId: String) {
        this.regionId.value = regionId
        routingTargets.value = emptyList()
        recentRegionPreferences.setLastRegionId(regionId)
        viewModelScope.launch {
            val region = regionRepository.installed(regionId)
            val name = region?.let { localizedInstalledName(it.displayName, it.countryCode, Locale.getDefault()) }
            _displayName.value = name
            _regionMissing.value = name == null
        }
        viewModelScope.launch {
            val installed = regionRepository.installed(regionId)?.transitVersion != null
            // Senza transit.json nel catalogo non si sa ancora: niente "non ci sono orari".
            transitOffered.value = if (installed) null else runCatching {
                val manifest = manifestClient.fetchManifest()
                manifest.transit?.let { regionTransitFeeds(transitClient.fetchIndex(it), regionId).isNotEmpty() }
            }.getOrNull()
        }
    }

    /** Scarica o aggiorna gli orari dei mezzi pubblici della regione (scheda delle fermate). */
    fun downloadTransit() {
        val id = regionId.value ?: return
        viewModelScope.launch {
            runCatching { entryWithTransit(id) }
                .onSuccess { entry ->
                    if (entry.transit != null) {
                        transitNetworkPreferences.rememberChoice(entry, setOf(PackageKind.TRANSIT))
                        regionSyncScheduler.enqueueDownload(entry, setOf(PackageKind.TRANSIT))
                    }
                }
        }
    }

    // La voce del manifest della regione con le sue reti (transit.json), come nell'elenco delle regioni.
    private suspend fun entryWithTransit(id: String): RegionManifestEntry {
        val manifest = manifestClient.fetchManifest()
        val index = manifest.transit?.let { transitClient.fetchIndex(it) }
        val regions = manifest.regions.filter { it.regionId == id }
        val place = withContext(Dispatchers.Default) {
            lastKnownPosition.get()?.let { (lat, lon) -> DevicePlace(lat, lon, countryLocator.countryAt(lat, lon)) }
        }
        val choices = effectiveTransitChoices(regions, index, transitNetworkPreferences.excluded.value, place)
        return attachTransitFeeds(regions, index, choices.excluded, choices.reasons).first()
    }

    fun downloadMap() = downloadPackage(PackageKind.MAP)

    /** "Scarica i percorsi" dal Navigatore: della regione aperta, o di [targetIds] se sono quelle tra partenza e arrivo. */
    fun downloadRouting(targetIds: List<String> = emptyList()) {
        routingTargets.value = targetIds
        if (targetIds.isEmpty()) downloadPackage(PackageKind.ROUTING) else targetIds.forEach { downloadPackage(PackageKind.ROUTING, it) }
    }

    /**
     * Le regioni del catalogo senza Percorsi che coprono partenza, linea in mezzo e arrivo ([points]), in ordine, col nome
     * nella lingua dell'app e il peso dei Percorsi. Vuota se il catalogo non si legge (offline) o tutto e' coperto: il
     * Navigatore resta col messaggio generico.
     */
    suspend fun missingRoutingRegions(points: List<RoutePoint>): List<MissingRegion> {
        val regions = runCatching { manifestClient.fetchManifest().regions }.getOrNull() ?: return emptyList()
        val installed = regionRepository.observeInstalled().first().filter { it.routingVersion != null }.map { it.regionId }.toSet()
        return withContext(Dispatchers.Default) {
            missingRoutingRegions(regions, installed, points, { countryLocator.countryAt(it.latitude, it.longitude) }, countryLocator.neighbours, countryLocator.centres)
        }.map { missing ->
            MissingRegion(missing.regionId, missing.localizedNames(Locale.getDefault()).displayName, missing.routing.files.sumOf { it.sizeBytes })
        }
    }

    // Un errore del catalogo non si butta: il Navigatore lo mostra e lascia riprovare, invece di restare fermo.
    private fun downloadPackage(kind: PackageKind, targetId: String? = null) {
        val id = targetId ?: regionId.value ?: return
        viewModelScope.launch {
            manifestFailed.value = false
            runCatching { manifestClient.fetchManifest().regions.first { it.regionId == id } }
                .onSuccess { regionSyncScheduler.enqueueDownload(it, setOf(kind)) }
                .onFailure { manifestFailed.value = true }
        }
    }

    private fun mapState(hasMap: Boolean, work: WorkInfo?): RegionMapState = when {
        hasMap -> RegionMapState.INSTALLED
        work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED -> RegionMapState.DOWNLOADING
        else -> RegionMapState.MISSING
    }
}

/**
 * Le regioni del manifest da cui scaricare i percorsi per andare dal primo all'ultimo dei [points] (partenza, linea
 * retta in mezzo, arrivo), nell'ordine del viaggio. I paesi sono quelli del cammino via terra fra il paese di
 * partenza e quello di arrivo ([landPath] sui confini veri: da San Marino a Riga Italia, Austria, Cechia, Polonia,
 * Lituania, non la Croazia o Kaliningrad che la linea sfiora passando sul mare). Per ogni paese del cammino, le
 * regioni sotto la linea (gli Stati Uniti sono divisi in stati) o, se la linea non ci passa, la sua regione piu'
 * vicina alla linea. Senza un cammino via terra (isole, partenza in mare) si segue la linea. Vuota se tutto e'
 * coperto dai percorsi installati ([installedRoutingIds]).
 */
internal fun missingRoutingRegions(
    regions: List<RegionManifestEntry>,
    installedRoutingIds: Set<String>,
    points: List<RoutePoint>,
    countryAt: (RoutePoint) -> String?,
    neighbours: Map<String, Set<String>> = emptyMap(),
    centres: Map<String, RoutePoint> = emptyMap(),
): List<RegionManifestEntry> {
    val countries = points.map(countryAt)
    val from = countries.firstOrNull()
    val to = countries.lastOrNull()
    val path = if (from != null && to != null) {
        landPath(from, to, neighbours, centres) { country -> country == from || country == to || regions.any { it.countryCode == country } }
    } else {
        null
    }
    val covering = regions.filter { it.regionId in installedRoutingIds }.toMutableList()
    val missing = mutableListOf<RegionManifestEntry>()
    val onLine = points.indices.filter { path == null || countries[it] in path }.groupBy { countries[it] }
    for (country in path ?: listOf(null)) {
        val indices = if (country == null) points.indices.toList() else onLine[country].orEmpty()
        if (indices.isEmpty() && country != null) {
            // La linea non ci passa (la Lituania, da San Marino a Riga): la regione del paese piu' vicina alla linea.
            val nearest = regions.filter { it.countryCode == country }.minByOrNull { region -> points.minOf { region.distance(it) } } ?: continue
            if (nearest !in covering) {
                missing += nearest
                covering += nearest
            }
            continue
        }
        for (i in indices) {
            val region = regionToDownload(regions, points[i], countries[i] ?: continue, installedRoutingIds, covering) ?: continue
            missing += region
            covering += region
        }
    }
    return missing
}

/**
 * La regione senza percorsi da scaricare per [point], nel paese [country] secondo i confini veri; null se e' gia'
 * coperto ([covering]) o nessuna regione fa al caso. Fra quelle del paese che lo contengono, la piu' piccola (l'Italia
 * contiene San Marino, gli Stati Uniti sono divisi in stati). Se nessun riquadro del suo paese lo contiene (i riquadri
 * del catalogo sono approssimati: la penisola dei Curi e' Lituania ma fuori dal suo), la regione del paese col riquadro
 * piu' vicino; se il paese non ha regioni col suo codice, fra quelle che lo contengono con un codice che non e' un paese
 * ISO (le Canarie hanno "ic", i confini dicono "es"). Mai il riquadro di un altro paese: quello della Svezia copre
 * mezzo Baltico, quello dell'Italia l'Austria del sud.
 */
private fun regionToDownload(
    regions: List<RegionManifestEntry>,
    point: RoutePoint,
    country: String,
    installedRoutingIds: Set<String>,
    covering: List<RegionManifestEntry>,
): RegionManifestEntry? {
    val ofCountry = regions.filter { it.countryCode == country }
    val candidates = when {
        ofCountry.isEmpty() -> regions.filter { it.contains(point) && it.countryCode?.uppercase() !in ISO_COUNTRIES }
        ofCountry.any { it.contains(point) } -> ofCountry.filter { it.contains(point) }
        else -> listOfNotNull(ofCountry.minByOrNull { it.distance(point) })
    }
    if (candidates.any { it in covering }) return null
    return candidates.filter { it.regionId !in installedRoutingIds }.minByOrNull { it.area() }
}

/**
 * Il cammino via terra piu' corto fra due paesi, estremi compresi, sul grafo dei confini ([neighbours]) pesato con la
 * distanza fra i loro centri ([centres]), passando solo per i paesi [allowed] (quelli del catalogo). Null se non
 * c'e' (isole, paesi senza confini nel grafo).
 */
internal fun landPath(
    from: String,
    to: String,
    neighbours: Map<String, Set<String>>,
    centres: Map<String, RoutePoint>,
    allowed: (String) -> Boolean,
): List<String>? {
    if (from == to) return listOf(from)
    val distance = hashMapOf(from to 0.0)
    val previous = HashMap<String, String>()
    val queue = java.util.PriorityQueue<Pair<String, Double>>(compareBy { it.second })
    queue += from to 0.0
    while (queue.isNotEmpty()) {
        val (country, cost) = queue.poll()!!
        if (country == to) break
        if (cost > distance.getValue(country)) continue
        val centre = centres[country] ?: continue
        for (next in neighbours[country].orEmpty()) {
            if (!allowed(next)) continue
            val nextCost = cost + degrees(centre, centres[next] ?: continue)
            if (nextCost < (distance[next] ?: Double.MAX_VALUE)) {
                distance[next] = nextCost
                previous[next] = country
                queue += next to nextCost
            }
        }
    }
    if (to !in previous) return null
    return generateSequence(to) { previous[it] }.toList().reversed()
}

// Distanza in gradi, con la longitudine accorciata dalla latitudine: basta per confrontare cammini.
private fun degrees(a: RoutePoint, b: RoutePoint): Double =
    hypot((a.longitude - b.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2)), a.latitude - b.latitude)

private fun RegionManifestEntry.contains(point: RoutePoint): Boolean = map.source.let {
    point.longitude in it.minLon..it.maxLon && point.latitude in it.minLat..it.maxLat
}

private fun RegionManifestEntry.area(): Double = map.source.let { (it.maxLon - it.minLon) * (it.maxLat - it.minLat) }

// Distanza in gradi dal riquadro (0 dentro): basta per scegliere il piu' vicino.
private fun RegionManifestEntry.distance(point: RoutePoint): Double = map.source.let {
    hypot(maxOf(it.minLon - point.longitude, 0.0, point.longitude - it.maxLon), maxOf(it.minLat - point.latitude, 0.0, point.latitude - it.maxLat))
}

private val ISO_COUNTRIES = Locale.getISOCountries().toSet()
