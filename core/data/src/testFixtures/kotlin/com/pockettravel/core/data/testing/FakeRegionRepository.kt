package com.pockettravel.core.data.testing

import com.pockettravel.core.data.InstalledGuides
import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * [RegionRepository] in memoria per i test JVM. Lo stato e' pubblico: il test imposta [installedRegions], [guides],
 * [availableBytes] e le dimensioni su disco ([packageSizes], [addressCells]); le chiamate che cambiano qualcosa
 * restano in [removedPackages], [recovered] e [compactions]. Le dimensioni sono quelle registrate, senza disco.
 */
class FakeRegionRepository(initial: List<InstalledRegion> = emptyList()) : RegionRepository {
    val installedRegions = MutableStateFlow(initial)
    val guides = MutableStateFlow<InstalledGuides?>(null)

    /** Dimensioni su disco dei pacchetti su file (mappa, rete stradale, civici, orari), per regione e tipo. */
    val packageSizes = mutableMapOf<Pair<String, PackageKind>, Long>()

    /** Pacchetti registrati ma senza file su disco, per regione: li toglie [forgetMissingPackages]. */
    val missingPackages = mutableMapOf<String, Set<PackageKind>>()
    val addressCells = mutableMapOf<String, Map<String, String>>()
    var availableBytes: Long = Long.MAX_VALUE

    val removedPackages = mutableListOf<Pair<String, PackageKind>>()
    val recovered = mutableListOf<String>()
    var compactions = 0
        private set

    override fun observeInstalled(): Flow<List<InstalledRegion>> = installedRegions

    override suspend fun displayName(regionId: String): String? = installed(regionId)?.displayName

    override suspend fun installed(regionId: String): InstalledRegion? = installedRegions.value.firstOrNull { it.regionId == regionId }

    override suspend fun markPackagesInstalled(
        regionId: String,
        displayName: String,
        countryCode: String?,
        versions: Map<PackageKind, String>,
        poiSizeBytes: Long?,
        poiExtraSizeBytes: Long?,
        previewVersion: String?,
        citiesSizeBytes: Long?,
    ) {
        require(PackageKind.POI !in versions || poiSizeBytes != null) { "poiSizeBytes mancante per $regionId" }
        require(PackageKind.POI_EXTRA !in versions || poiExtraSizeBytes != null) { "poiExtraSizeBytes mancante per $regionId" }
        require(PackageKind.CITIES !in versions || citiesSizeBytes != null) { "citiesSizeBytes mancante per $regionId" }
        val current = installed(regionId)
        save(
            regionId, displayName, countryCode ?: current?.countryCode,
            versionOf = { kind -> versions[kind] ?: current?.versionOf(kind) },
            poiSizeBytes = if (PackageKind.POI in versions) poiSizeBytes else current?.poiSizeBytes,
            poiExtraSizeBytes = if (PackageKind.POI_EXTRA in versions) poiExtraSizeBytes else current?.poiExtraSizeBytes,
            previewVersion = previewVersion ?: current?.previewVersion,
            citiesSizeBytes = if (PackageKind.CITIES in versions) citiesSizeBytes else current?.citiesSizeBytes,
        )
    }

    override suspend fun removePackage(regionId: String, kind: PackageKind) {
        val current = installed(regionId) ?: return
        removedPackages += regionId to kind
        save(
            regionId, current.displayName, current.countryCode,
            versionOf = { if (it == kind) null else current.versionOf(it) },
            poiSizeBytes = if (kind == PackageKind.POI) null else current.poiSizeBytes,
            poiExtraSizeBytes = if (kind == PackageKind.POI_EXTRA) null else current.poiExtraSizeBytes,
            previewVersion = current.previewVersion,
            citiesSizeBytes = if (kind == PackageKind.CITIES) null else current.citiesSizeBytes,
        )
    }

    override suspend fun forgetMissingPackages(regionId: String): Set<PackageKind> {
        val current = installed(regionId) ?: return emptySet()
        val missing = missingPackages[regionId].orEmpty().filterTo(mutableSetOf()) { current.versionOf(it) != null }
        if (missing.isEmpty()) return missing
        save(
            regionId, current.displayName, current.countryCode,
            versionOf = { if (it in missing) null else current.versionOf(it) },
            poiSizeBytes = current.poiSizeBytes,
            poiExtraSizeBytes = current.poiExtraSizeBytes,
            previewVersion = current.previewVersion,
            citiesSizeBytes = current.citiesSizeBytes,
        )
        return missing
    }

    // Come il salvataggio vero: senza pacchetti la regione sparisce, altrimenti si aggiunge o sostituisce.
    private fun save(
        regionId: String,
        displayName: String,
        countryCode: String?,
        versionOf: (PackageKind) -> String?,
        poiSizeBytes: Long?,
        poiExtraSizeBytes: Long?,
        previewVersion: String?,
        citiesSizeBytes: Long?,
    ) {
        if (PackageKind.entries.all { versionOf(it) == null }) {
            installedRegions.update { list -> list.filterNot { it.regionId == regionId } }
            return
        }
        val diskBytes = PackageKind.entries.sumOf { kind -> if (versionOf(kind) == null) 0L else packageSizes[regionId to kind] ?: 0L }
        val region = InstalledRegion(
            regionId = regionId,
            displayName = displayName,
            countryCode = countryCode,
            mapVersion = versionOf(PackageKind.MAP),
            routingVersion = versionOf(PackageKind.ROUTING),
            poiVersion = versionOf(PackageKind.POI),
            poiExtraVersion = versionOf(PackageKind.POI_EXTRA),
            addressesVersion = versionOf(PackageKind.ADDRESSES),
            poiSizeBytes = poiSizeBytes,
            poiExtraSizeBytes = poiExtraSizeBytes,
            sizeBytes = diskBytes + (poiSizeBytes ?: 0L) + (poiExtraSizeBytes ?: 0L) + (citiesSizeBytes ?: 0L),
            previewVersion = previewVersion,
            citiesVersion = versionOf(PackageKind.CITIES),
            citiesSizeBytes = citiesSizeBytes,
            transitVersion = versionOf(PackageKind.TRANSIT),
        )
        installedRegions.update { list ->
            if (list.any { it.regionId == regionId }) list.map { if (it.regionId == regionId) region else it } else list + region
        }
    }

    override suspend fun fillCountryCode(regionId: String, countryCode: String) {
        installedRegions.update { list ->
            list.map { if (it.regionId == regionId && it.countryCode == null) it.copy(countryCode = countryCode) else it }
        }
    }

    override suspend fun installedGuidesVersion(): String? = guides.value?.version

    override fun observeInstalledGuides(): Flow<InstalledGuides?> = guides

    override suspend fun markGuidesInstalled(version: String, sizeBytes: Long) {
        guides.value = InstalledGuides(version, sizeBytes)
    }

    override suspend fun compactDatabase() {
        compactions++
    }

    override suspend fun <T> inInstallTransaction(block: suspend () -> T): T = block()

    override suspend fun recoverInterruptedActivations(regionId: String) {
        recovered += regionId
    }

    override suspend fun remove(regionId: String) {
        installedRegions.update { list -> list.filterNot { it.regionId == regionId } }
    }

    override fun packageBytes(region: InstalledRegion, kind: PackageKind): Long? = when {
        region.versionOf(kind) == null -> null
        kind == PackageKind.POI_EXTRA -> region.poiExtraSizeBytes
        kind == PackageKind.CITIES -> region.citiesSizeBytes
        kind == PackageKind.POI -> region.poiSizeBytes
        else -> packageSizes[region.regionId to kind] ?: 0L
    }

    override fun availableStorageBytes(): Long = availableBytes

    override fun installedAddressCells(regionId: String): Map<String, String> = addressCells[regionId].orEmpty()
}
