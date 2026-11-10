package com.pockettravel.app.regions

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.core.data.DefaultDispatcher
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.LastKnownPosition
import com.pockettravel.core.data.MapDetailPreferences
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.RoutingVariantChoice
import com.pockettravel.core.data.RoutingVariantPreferences
import com.pockettravel.core.data.UpdateCheck
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.core.sync.AddressGridClient
import com.pockettravel.core.sync.ManifestClient
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.core.sync.ReplacedRegion
import com.pockettravel.core.sync.TransitClient
import com.pockettravel.core.sync.TransitIndex
import com.pockettravel.core.sync.attachAddressGridCells
import com.pockettravel.core.sync.attachTransitFeeds
import com.pockettravel.core.sync.automaticOnMeteredNetwork
import com.pockettravel.core.sync.guidesChoice
import com.pockettravel.core.sync.hasCitiesIn
import com.pockettravel.core.sync.restrictedTo
import com.pockettravel.core.sync.withRoutingVariant
import com.pockettravel.feature.map.ConnectivityChecker
import com.pockettravel.feature.map.UsageModePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Spazio libero minimo per estrarre una mappa non ancora installata: il suo peso reale si conosce solo a estrazione finita. */
internal const val MAP_MIN_FREE_BYTES = 100L * 1024 * 1024

/** Le righe dell'elenco senza il filtro della ricerca. */
private data class RegionCatalog(val items: List<RegionUiItem>, val replaced: List<ReplacedRegionItem>)

private data class LoadStatus(
    val isLoading: Boolean = true,
    val checking: CatalogCheck? = null,
    @StringRes val loadError: Int? = null,
    @StringRes val message: Int? = null,
)

@HiltViewModel
class RegionListViewModel @Inject constructor(
    private val manifestClient: ManifestClient,
    private val addressGridClient: AddressGridClient,
    private val transitClient: TransitClient,
    private val regionRepository: RegionRepository,
    private val regionSyncScheduler: RegionSyncScheduler,
    private val updateChecks: Set<@JvmSuppressWildcards UpdateCheck>,
    private val usageModePreferences: UsageModePreferences,
    private val transitNetworkPreferences: TransitNetworkPreferences,
    private val mapDetailPreferences: MapDetailPreferences,
    private val regionZonePreferences: RegionZonePreferences,
    private val routingVariantPreferences: RoutingVariantPreferences,
    private val lastKnownPosition: LastKnownPosition,
    private val countryLocator: CountryLocator,
    private val connectivityChecker: ConnectivityChecker,
    private val recentRegionStore: RecentRegionStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    // Per le reti dei mezzi pubblici vicine (defaultTransitChoice): ultima posizione nota e il suo paese, senza GPS.
    private val place = MutableStateFlow<DevicePlace?>(null)

    private val manifestRegions = MutableStateFlow<List<RegionManifestEntry>>(emptyList())
    // Catalogo e indice dei mezzi pubblici come arrivano: manifestRegions li unisce con le reti scelte.
    private val baseRegions = MutableStateFlow<List<RegionManifestEntry>>(emptyList())
    // Elenco dalla copia salvata del catalogo: le sue voci non hanno civici a griglia e mezzi pubblici, quindi download e
    // zone aspettano il catalogo del sito (awaitSiteCatalog). manifestFromSavedCopy segue manifestRegions.
    private val fromSavedCopy = MutableStateFlow(false)
    private val manifestFromSavedCopy = MutableStateFlow(false)
    private val transitIndex = MutableStateFlow<TransitIndex?>(null)
    private val replacedRegions = MutableStateFlow<List<ReplacedRegion>>(emptyList())
    private val status = MutableStateFlow(LoadStatus())
    private val query = MutableStateFlow("")
    private val locale = MutableStateFlow(Locale.getDefault())

    /** Lingua dell'interfaccia, per i nomi delle nazioni (vedi [localizedNames]); la passa la schermata. */
    fun setLocale(newLocale: Locale) {
        val languageChanged = newLocale.language != locale.value.language
        locale.value = newLocale
        // Lingua cambiata dalle Impostazioni con l'elenco gia' letto: le citta' (italiane o inglesi), le loro dimensioni e
        // versioni vengono dal catalogo della lingua di prima, che resterebbe in memoria fino al prossimo avvio.
        if (languageChanged && baseRegions.value.isNotEmpty()) refresh()
    }

    // Elenco senza il filtro di ricerca: dimensioni e stati (packageBytes legge dal disco, installedAddressCells il database)
    // si ricalcolano solo se cambia il catalogo, non a ogni tasto della ricerca.
    private val catalog = combine(
        // Ogni regione con la sua zona: dimensioni e versioni (quella dei civici dipende dalle celle) sono quelle della zona.
        // Con la rete stradale "solo auto" scelta, anche la dimensione e la versione della rete stradale sono quelle della variante.
        combine(manifestRegions, manifestFromSavedCopy, locale, regionZonePreferences.zones, routingVariantPreferences.choices) { regions, saved, currentLocale, zones, choices ->
            Triple(regions.map { it.localizedNames(currentLocale).withRoutingVariant(choices.isCarOnly(it.regionId)) to zones[it.regionId] }, currentLocale, saved)
        },
        // Con "Percorsi di navigazione" la dimensione di "Scarica" comprende la rete stradale.
        combine(regionRepository.observeInstalled(), usageModePreferences.wantsDirections, ::Pair),
        replacedRegions,
    ) { (zonedRegions, currentLocale, fromSaved), (installedOnDb, wantsDirections), replacedByManifest ->
        // Con le copie delle citta' lette dal disco, come in Spazio di archiviazione.
        val installed = installedOnDb.map { it.copy(sizeBytes = regionRepository.regionBytes(it)) }
        val remoteRegions = zonedRegions.map { it.first }
        val installedByRegion = installed.associateBy { it.regionId }
        // Senza catalogo (offline, o non ancora letto) le nazioni installate restano apribili: i loro dati sono sul dispositivo.
        val items = if (remoteRegions.isEmpty()) {
            // Con il nome nella lingua dell'interfaccia, come quando arriva il catalogo (niente "Italia" che diventa "Italy").
            installed.map {
                offlineRegionItem(it, regionRepository::packageBytes)
                    .copy(displayName = localizedInstalledName(it.displayName, it.countryCode, currentLocale))
            }
        } else {
            zonedRegions.map { (remote, zone) ->
                regionUiItem(remote.restrictedTo(zone), installedByRegion[remote.regionId], regionRepository::packageBytes, regionRepository::installedAddressCells, wantsDirections, transitIndex.value != null)
                    .copy(
                        bbox = remote.map.source.let { RegionBbox(it.minLon, it.minLat, it.maxLon, it.maxLat) },
                        zone = zone,
                        routingCarAvailable = remote.routingCar != null,
                        fromSavedCopy = fromSaved,
                    )
            }
        }
        RegionCatalog(items, replacedItems(installed, remoteRegions, replacedByManifest))
    }
        // packageBytes e regionBytes leggono dal disco le dimensioni di mappa, rete stradale e copie delle citta'.
        .flowOn(ioDispatcher)

    // Parte vuoto: l'elenco non aspetta la prima risposta di WorkManager.
    private val downloading = regionSyncScheduler.observeDownloadingRegions().onStart { emit(emptySet()) }

    val uiState: StateFlow<RegionListUiState> = combine(catalog, status, query, downloading) { currentCatalog, currentStatus, currentQuery, currentDownloading ->
        RegionListUiState(
            items = currentCatalog.items.filter { matchesQuery(it.displayName, currentQuery) },
            replaced = currentCatalog.replaced.filter { matchesQuery(it.displayName, currentQuery) },
            query = currentQuery,
            isLoading = currentStatus.isLoading,
            loadError = currentStatus.loadError,
            message = currentStatus.message,
            downloadingRegions = currentDownloading,
            checking = currentStatus.checking,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RegionListUiState())

    init {
        refresh()
        // Le reti scelte cambiano versione e dimensione del pacchetto: le regioni si ricalcolano subito.
        viewModelScope.launch {
            combine(combine(baseRegions, fromSavedCopy, ::Pair), transitIndex, transitNetworkPreferences.excluded, place, regionRepository.observeInstalled()) { (regions, saved), index, stored, here, installed ->
                val choices = effectiveTransitChoices(regions, index, stored, here)
                // Le reti installate si leggono dal disco, solo per le regioni con gli orari e solo con l'indice.
                val feeds = if (index == null) {
                    emptyMap()
                } else {
                    withContext(ioDispatcher) {
                        installed.filter { it.transitVersion != null }.associate { it.regionId to regionRepository.installedTransitFeeds(it.regionId) }
                    }
                }
                attachTransitFeeds(regions, index, choices.excluded, choices.reasons, feeds) to saved
            }.collect { (regions, saved) ->
                // Prima il segnale "copia salvata", poi le voci; al contrario quando arriva il catalogo del sito: chi aspetta
                // in awaitSiteCatalog non vede mai le voci della copia con il segnale spento.
                if (saved) manifestFromSavedCopy.value = true
                manifestRegions.value = regions
                if (!saved) manifestFromSavedCopy.value = false
            }
        }
        viewModelScope.launch(defaultDispatcher) {
            place.value = lastKnownPosition.get()?.let { (lat, lon) -> DevicePlace(lat, lon, countryLocator.countryAt(lat, lon)) }
        }
    }

    /** Aggiunge o toglie una rete dei mezzi pubblici della regione (foglio Contenuti). */
    fun setTransitNetwork(regionId: String, feedId: String, included: Boolean) {
        val region = baseRegions.value.filter { it.regionId == regionId }
        val defaults = effectiveTransitChoices(region, transitIndex.value, emptyMap(), place.value).excluded[regionId].orEmpty()
        transitNetworkPreferences.setIncluded(regionId, feedId, included, defaults)
    }

    private var loadJob: Job? = null

    fun refresh() = load(fresh = false)

    // [fresh]: il controllo manuale scarica sempre; all'apertura e al "Riprova" va bene il `manifest.json` scaricato da poco (l'hub lo
    // chiede insieme all'elenco: ManifestClient.recentManifest).
    private fun load(fresh: Boolean) {
        val previous = loadJob
        loadJob = viewModelScope.launch {
            // Un solo caricamento alla volta: il vecchio finisce di annullarsi (finally compreso) prima che parta il nuovo.
            previous?.cancelAndJoin()
            val online = connectivityChecker.isOnline()
            status.update { it.copy(isLoading = true, checking = if (online) CatalogCheck.UPDATES else CatalogCheck.CATALOG) }
            try {
                // Con la rete, alla prima lettura: subito l'ultimo catalogo verificato (copia sul telefono), mentre il sito
                // risponde. Senza civici a griglia e mezzi pubblici, che arrivano con il catalogo del sito. Senza rete
                // restano le nazioni installate.
                if (online && baseRegions.value.isEmpty()) {
                    runCatchingCancellable { manifestClient.savedManifest() }.getOrNull()?.let { saved ->
                        fromSavedCopy.value = true
                        baseRegions.value = withContext(defaultDispatcher) { attachAddressGridCells(saved.regions, null) }
                        replacedRegions.value = saved.replacedRegions
                    }
                }
                val manifest = if (fresh) manifestClient.fetchManifest() else manifestClient.recentManifest()
                // Civici a griglia: un errore qui (rete, indice non
                // valido) non deve bloccare l'elenco delle regioni, solo lasciarle senza civici a
                // griglia per questo aggiornamento — riprovera' al prossimo refresh().
                val addressGridIndex = manifest.addressGrid?.let { entry -> runCatchingCancellable { addressGridClient.fetchIndex(entry) }.getOrNull() }
                // Come i civici: senza indice dei mezzi pubblici le regioni restano senza quel pacchetto.
                val transitIndex = manifest.transit?.let { entry -> runCatchingCancellable { transitClient.fetchIndex(entry) }.getOrNull() }
                this@RegionListViewModel.transitIndex.value = transitIndex
                // Calcolo su tutte le regioni e le celle della griglia: fuori dal thread principale.
                baseRegions.value = withContext(defaultDispatcher) { attachAddressGridCells(manifest.regions, addressGridIndex) }
                fromSavedCopy.value = false
                replacedRegions.value = manifest.replacedRegions
                // Le guide si aggiornano da sole anche da qui, non solo col controllo periodico (sotto i 5 MB anche su
                // rete a consumo).
                val guides = manifest.guidesChoice()
                if (regionRepository.installedGuidesVersion() != guides.installedVersion) {
                    regionSyncScheduler.enqueueGuidesSync(onlyOnWifi = !guides.automaticOnMeteredNetwork, automatic = true)
                }
                // Regioni installate prima che il database salvasse il codice paese: serve alla bandiera in Spazio.
                val withoutCode = regionRepository.observeInstalled().first().filter { it.countryCode == null }.mapTo(mutableSetOf()) { it.regionId }
                manifest.regions.filter { it.regionId in withoutCode }.forEach { remote ->
                    remote.countryCode?.let { regionRepository.fillCountryCode(remote.regionId, it) }
                }
                status.update { it.copy(loadError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status.update { it.copy(loadError = R.string.regions_load_error) }
            } finally {
                // Il sito non ha risposto: al posto della copia salvata restano le nazioni installate, come senza rete.
                if (fromSavedCopy.value) {
                    baseRegions.value = emptyList()
                    replacedRegions.value = emptyList()
                    fromSavedCopy.value = false
                }
                status.update { it.copy(isLoading = false, checking = null) }
            }
        }
    }

    private suspend fun awaitSiteCatalog() {
        manifestFromSavedCopy.first { !it }
    }

    /** Controllo manuale immediato, in aggiunta a quello periodico: catalogo (qui, sincrono)
     *  + versione app e modello IA (in background, notificano se c'e' un aggiornamento). */
    fun checkForUpdatesNow() {
        load(fresh = true)
        updateChecks.forEach { it.checkNow() }
    }

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    /** Non installata: scarica tutti i pacchetti. Installata: aggiorna solo quelli cambiati. */
    fun download(regionId: String) {
        viewModelScope.launch {
            awaitSiteCatalog()
            val entry = zonedEntry(regionId) ?: return@launch
            val local = regionRepository.installed(regionId)
            enqueue(entry, if (local == null) entry.downloadKinds(usageModePreferences.wantsDirections.value) else outdatedKinds(entry, local))
        }
    }

    /** Scarica i pacchetti scelti di una regione (primo avvio, selezione multipla): false se non e' partito. */
    suspend fun downloadKinds(regionId: String, kinds: Set<PackageKind>): Boolean {
        awaitSiteCatalog()
        val entry = zonedEntry(regionId) ?: return false
        return enqueue(entry, kinds)
    }

    /** Scarica o aggiorna un solo pacchetto della regione (foglio "Contenuti"). */
    fun downloadPackage(regionId: String, kind: PackageKind) {
        viewModelScope.launch {
            awaitSiteCatalog()
            val entry = zonedEntry(regionId) ?: return@launch
            enqueue(entry, setOf(kind))
        }
    }

    private suspend fun enqueue(listed: RegionManifestEntry, kinds: Set<PackageKind>): Boolean {
        if (kinds.isEmpty()) return false
        val entry = if (PackageKind.CITIES in kinds) withCitiesInCurrentLanguage(listed) else listed
        // StorageManager.getAllocatableBytes puo' bloccare: fuori dal thread principale.
        // La mappa non ha un peso noto in anticipo (si estrae sul dispositivo): se non c'e' ancora, se ne stima un minimo;
        // un aggiornamento ne estrae una nuova in staging mentre la vecchia resta fino all'attivazione, quindi vale il suo peso.
        val installedRegion = regionRepository.installed(entry.regionId)
        val mapEstimate = when {
            PackageKind.MAP !in kinds -> 0L
            installedRegion?.versionOf(PackageKind.MAP) == null -> MAP_MIN_FREE_BYTES
            else -> withContext(ioDispatcher) { regionRepository.packageBytes(installedRegion, PackageKind.MAP) } ?: MAP_MIN_FREE_BYTES
        }
        val enoughSpace = withContext(ioDispatcher) { regionRepository.availableStorageBytes() } >= entry.downloadBytes(kinds) + mapEstimate
        if (enoughSpace) {
            transitNetworkPreferences.rememberChoice(entry, kinds)
            regionSyncScheduler.enqueueDownload(entry, kinds)
        } else {
            status.update { it.copy(message = R.string.regions_not_enough_space) }
        }
        return enoughSpace
    }

    // L'eliminazione dei file puo' fallire (check nel repository): un messaggio invece del crash.
    private suspend fun removeOrReport(remove: suspend () -> Unit) {
        try {
            remove()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            status.update { it.copy(message = R.string.regions_delete_error) }
        }
    }

    fun onMessageShown() {
        status.update { it.copy(message = null) }
    }

    fun delete(regionId: String) {
        regionSyncScheduler.cancelDownload(regionId)
        regionZonePreferences.setZone(regionId, null)
        viewModelScope.launch {
            removeOrReport {
                regionRepository.remove(regionId)
                withContext(ioDispatcher) { recentRegionStore.clearIfLast(regionId) }
            }
        }
    }

    fun deletePackage(regionId: String, kind: PackageKind) {
        viewModelScope.launch {
            removeOrReport {
                regionRepository.removePackage(regionId, kind)
                // Con l'ultimo pacchetto se ne va l'intera regione.
                if (regionRepository.installed(regionId) == null) withContext(ioDispatcher) { recentRegionStore.clearIfLast(regionId) }
            }
        }
    }

    /** Mappa leggera: la scelta dell'utente o, senza, com'e' la mappa installata; senza mappa leggera (il default). */
    fun observeMapLight(regionId: String): Flow<Boolean> =
        combine(mapDetailPreferences.choices, mapDetailPreferences.installedLight, regionRepository.observeInstalled()) { choices, light, installed ->
            choices[regionId] ?: (regionId in light || installed.none { it.regionId == regionId && it.versionOf(PackageKind.MAP) != null })
        }

    /**
     * Sceglie la mappa leggera o dettagliata. Con la mappa gia' installata la si estrae di nuovo subito: le tile si
     * riusano da quella installata, quindi verso la leggera non si scarica niente e verso la dettagliata solo la z14.
     */
    fun setMapLight(regionId: String, light: Boolean) {
        mapDetailPreferences.setChoice(regionId, light)
        viewModelScope.launch {
            if (regionRepository.installed(regionId)?.versionOf(PackageKind.MAP) != null) downloadPackage(regionId, PackageKind.MAP)
        }
    }

    // La voce del catalogo limitata alla zona scelta: anche il worker la limita, ma dimensioni, spazio libero e versioni
    // da confrontare devono essere gia' quelle della zona.
    /**
     * [entry] con le guide delle citta' nella lingua di adesso: se l'elenco e' stato letto nell'altra lingua (cambiata
     * mentre era aperto, prima che setLocale lo rileggesse) si prendono dal catalogo, letto ora nella lingua giusta.
     * Senza rete restano quelle dell'elenco.
     */
    private suspend fun withCitiesInCurrentLanguage(entry: RegionManifestEntry): RegionManifestEntry {
        if (entry.hasCitiesIn(currentGuidesLanguage())) return entry
        val current = runCatchingCancellable { manifestClient.recentManifest() }.getOrNull()
            ?.regions?.firstOrNull { it.regionId == entry.regionId }
        return current?.let { entry.copy(cities = it.cities) } ?: entry
    }

    private fun zonedEntry(regionId: String, zone: RegionZone? = regionZonePreferences.zone(regionId)): RegionManifestEntry? =
        manifestRegions.value.firstOrNull { it.regionId == regionId }
            ?.withRoutingVariant(routingVariantPreferences.choices.value.isCarOnly(regionId))?.restrictedTo(zone)

    /**
     * Sceglie la zona della regione (null = tutta). Con la regione installata mappa, rete stradale e civici si rifanno subito:
     * la mappa riusa le tile gia' installate, la rete stradale i segmenti gia' scaricati, i civici le celle invariate; i civici
     * senza celle nella zona si tolgono. Con [download] e la regione non installata parte il download di tutta la regione.
     */
    fun setZone(regionId: String, zone: RegionZone?, download: Boolean = false) {
        viewModelScope.launch {
            // Con le voci della copia salvata (senza civici a griglia) i civici installati si toglierebbero.
            awaitSiteCatalog()
            // Solo la parte dentro la regione (la zona inquadrata puo' prendere anche nazioni vicine, vedi regionsInZone).
            val source = manifestRegions.value.firstOrNull { it.regionId == regionId }?.map?.source ?: return@launch
            if (zone != null && (zone.maxLon <= source.minLon || zone.minLon >= source.maxLon || zone.maxLat <= source.minLat || zone.minLat >= source.maxLat)) return@launch
            val clamped = zone?.let {
                RegionZone(maxOf(it.minLon, source.minLon), maxOf(it.minLat, source.minLat), minOf(it.maxLon, source.maxLon), minOf(it.maxLat, source.maxLat))
            }
            regionZonePreferences.setZone(regionId, clamped)
            val entry = zonedEntry(regionId, clamped) ?: return@launch
            val local = regionRepository.installed(regionId)
            if (local == null) {
                if (download) enqueue(entry, entry.downloadKinds(usageModePreferences.wantsDirections.value))
                return@launch
            }
            val installedKinds = setOf(PackageKind.MAP, PackageKind.ROUTING, PackageKind.ADDRESSES).filter { local.versionOf(it) != null }
            if (PackageKind.ADDRESSES in installedKinds && PackageKind.ADDRESSES !in entry.availableKinds) {
                removeOrReport { regionRepository.removePackage(regionId, PackageKind.ADDRESSES) }
            }
            enqueue(entry, installedKinds.filterTo(mutableSetOf()) { it in entry.availableKinds })
        }
    }

    fun observeRoutingChoice(regionId: String): Flow<RoutingVariantOption> =
        routingVariantPreferences.choices.map { choices ->
            when (choices.choiceFor(regionId)) {
                RoutingVariantChoice.CAR -> RoutingVariantOption.CAR
                RoutingVariantChoice.BIKE_FOOT -> RoutingVariantOption.BIKE_FOOT
                RoutingVariantChoice.ALL -> RoutingVariantOption.ALL
            }
        }

    /**
     * Rete stradale per l'auto (variante "solo auto") o per bici e piedi / tutti i modi (rete stradale completa); se
     * cambia la variante e la rete stradale e' gia' installata, si riscarica subito.
     */
    fun setRoutingChoice(regionId: String, choice: RoutingVariantOption) {
        val carOnly = choice == RoutingVariantOption.CAR
        val packageChanged = carOnly != routingVariantPreferences.choices.value.isCarOnly(regionId)
        routingVariantPreferences.setCarOnly(regionId, carOnly)
        routingVariantPreferences.setBikeFoot(regionId, choice == RoutingVariantOption.BIKE_FOOT)
        if (!packageChanged) return
        viewModelScope.launch {
            if (regionRepository.installed(regionId)?.versionOf(PackageKind.ROUTING) != null) downloadPackage(regionId, PackageKind.ROUTING)
        }
    }

    /** Le regioni del catalogo dentro [zone], dalla piu' presente (terra, non mare): id e nome nella lingua dell'interfaccia. */
    suspend fun regionsInZone(zone: RegionZone): List<Pair<String, String>> = withContext(defaultDispatcher) {
        val regions = manifestRegions.value
        val candidates = regions.mapNotNull { region ->
            region.countryCode?.let { code -> region.map.source.let { ZoneCandidate(region.regionId, code, RegionBbox(it.minLon, it.minLat, it.maxLon, it.maxLat)) } }
        }
        zoneShares(zone, candidates, countryLocator::countryAt).map { (id, _) ->
            id to regions.first { it.regionId == id }.localizedNames(locale.value).displayName
        }
    }

    /** La regione e' installata e senza zona: la si ha per intero. */
    suspend fun isInstalledWhole(regionId: String): Boolean =
        regionRepository.installed(regionId) != null && regionZonePreferences.zone(regionId) == null

    fun observeDownloadProgress(regionId: String): Flow<WorkInfo?> =
        regionSyncScheduler.observeDownload(regionId)
}
