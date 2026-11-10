package com.pockettravel.app.regions

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.ReplacedRegion
import com.pockettravel.core.ui.countryName
import kotlinx.coroutines.flow.map
import java.text.Normalizer
import java.util.Locale

/**
 * Una nazione installata quando il catalogo non c'e': solo i pacchetti sul dispositivo, tutti "installati" (senza
 * catalogo non si sa se ci sono aggiornamenti) e senza nulla da scaricare.
 */
internal fun offlineRegionItem(local: InstalledRegion, installedBytes: (InstalledRegion, PackageKind) -> Long?): RegionUiItem =
    RegionUiItem(
        regionId = local.regionId,
        displayName = local.displayName,
        sizeBytes = local.sizeBytes,
        status = RegionStatus.INSTALLED,
        countryCode = local.countryCode,
        packages = PackageKind.entries.filter { local.versionOf(it) != null }.map { kind ->
            PackageUiState(kind = kind, status = RegionStatus.INSTALLED, downloadBytes = 0, installedBytes = installedBytes(local, kind))
        },
    )

/** Regioni installate che il catalogo non offre piu' perche' divise in regioni piu' piccole. */
internal fun replacedItems(
    installed: List<InstalledRegion>,
    remoteRegions: List<RegionManifestEntry>,
    replacedRegions: List<ReplacedRegion>,
): List<ReplacedRegionItem> {
    val remoteIds = remoteRegions.mapTo(mutableSetOf()) { it.regionId }
    val groups = replacedRegions.associate { it.regionId to it.groupName }
    return installed
        .filter { it.regionId !in remoteIds && it.regionId in groups }
        .map { ReplacedRegionItem(it.regionId, it.displayName, it.countryCode, groups.getValue(it.regionId), it.sizeBytes) }
}

/** Pacchetti installati la cui versione nel catalogo e' cambiata (tra quelli che il catalogo offre ancora). */
internal fun outdatedKinds(remote: RegionManifestEntry, local: InstalledRegion?): Set<PackageKind> =
    remote.availableKinds.filterTo(mutableSetOf()) { kind -> local?.versionOf(kind)?.let { it != remote.versionOf(kind) } == true }

internal fun regionUiItem(
    remote: RegionManifestEntry,
    local: InstalledRegion?,
    installedBytes: (InstalledRegion, PackageKind) -> Long?,
    // Civici a griglia: celle gia' installate (id -> version), per
    // contare solo quelle nuove o cambiate nella dimensione da scaricare. Non serve per le regioni
    // senza griglia: il default basta a tutti i test.
    installedAddressCells: (regionId: String) -> Map<String, String> = { emptyMap() },
    // "Percorsi di navigazione" attivo: "Scarica" comprende la rete stradale (RegionManifestEntry.downloadKinds).
    withRouting: Boolean = false,
    // Indice dei mezzi pubblici letto: una regione senza reti le mostra come "non disponibili" (senza indice non si sa).
    transitKnown: Boolean = false,
): RegionUiItem {
    val addressCellVersions = if (remote.addressGrid != null && local != null) installedAddressCells(remote.regionId) else emptyMap()
    val outdated = outdatedKinds(remote, local)
    val status = when {
        local == null -> RegionStatus.NOT_INSTALLED
        outdated.isNotEmpty() -> RegionStatus.UPDATE_AVAILABLE
        else -> RegionStatus.INSTALLED
    }
    // POI extra e civici possono mancare dal catalogo: la riga c'e' se il catalogo li offre o se sono
    // installati. Solo i civici mancanti si segnalano come "non disponibili": i POI extra arrivano man
    // mano che la pipeline rigenera i POI delle regioni.
    val (shown, missing) = PackageKind.entries.partition { it in remote.availableKinds || local?.versionOf(it) != null }
    val unavailable = missing.filter { it == PackageKind.ADDRESSES || (it == PackageKind.TRANSIT && transitKnown) }
    val packages = shown.map { kind ->
        PackageUiState(
            kind = kind,
            status = when {
                local?.versionOf(kind) == null -> RegionStatus.NOT_INSTALLED
                kind in outdated -> RegionStatus.UPDATE_AVAILABLE
                else -> RegionStatus.INSTALLED
            },
            downloadBytes = remote.downloadBytes(setOf(kind), addressCellVersions),
            installedBytes = local?.let { installedBytes(it, kind) },
            detail = if (kind == PackageKind.TRANSIT && remote.transit?.available.orEmpty().size <= 1) remote.transit?.feeds?.joinToString { it.name }?.ifEmpty { null } else null,
            networks = if (kind == PackageKind.TRANSIT) remote.transitNetworks() else emptyList(),
            transitDefaultReason = if (kind == PackageKind.TRANSIT) remote.transit?.defaultReason else null,
            // Una rete scaricata e non piu' pubblicata compare solo tra quelle non piu' aggiornate, non due volte.
            missingNetworks = if (kind == PackageKind.TRANSIT) {
                remote.transit?.missing.orEmpty().filter { it.name !in remote.transit?.stale.orEmpty() }
            } else {
                emptyList()
            },
            staleNetworks = if (kind == PackageKind.TRANSIT) remote.transit?.stale.orEmpty() else emptyList(),
        )
    }
    val toDownload = when (status) {
        RegionStatus.NOT_INSTALLED -> remote.downloadKinds(withRouting)
        RegionStatus.UPDATE_AVAILABLE -> outdated
        RegionStatus.INSTALLED -> emptySet()
    }
    val sizeBytes = if (status == RegionStatus.INSTALLED) local!!.sizeBytes else remote.downloadBytes(toDownload, addressCellVersions)
    return RegionUiItem(
        remote.regionId, remote.displayName, sizeBytes, status, remote.continent, remote.countryCode, packages, unavailable,
        remote.groupName, remote.groupLabel,
        bbox = remote.map.source.let { RegionBbox(it.minLon, it.minLat, it.maxLon, it.maxLat) },
        includesMap = PackageKind.MAP in toDownload,
    )
}

/**
 * Il catalogo ha i nomi in italiano: nazioni intere e nazioni divise in piu' regioni prendono il nome
 * dal codice paese nella lingua dell'interfaccia ("be" -> "Belgio"/"Belgium"). Le singole regioni di un
 * nazione divisa prendono groupLabelEn in inglese (se il catalogo lo ha), groupLabel altrimenti, col nome
 * della nazione davanti. La regione principale di una nazione con isole a parte ha come etichetta il nome della
 * nazione ("Portogallo" accanto ad "Azzorre"): prende solo il nome della nazione.
 */
internal fun RegionManifestEntry.localizedNames(locale: Locale): RegionManifestEntry {
    val code = countryCode ?: return this
    val country = countryName(code, locale).takeIf { !it.equals(code, ignoreCase = true) } ?: return this
    return if (groupName == null) {
        copy(displayName = country)
    } else if (groupLabel == groupName) {
        copy(displayName = country, groupName = country, groupLabel = country)
    } else {
        val label = groupLabelEn?.takeIf { locale.language == "en" } ?: groupLabel
        copy(displayName = label?.let { "$country - $it" } ?: displayName, groupName = country, groupLabel = label)
    }
}

/**
 * Il nome salvato di una regione installata (in italiano, dal catalogo) nella lingua dell'interfaccia,
 * senza catalogo: il nome della nazione dal codice quando e' la nazione intera ("Francia", "Sint Maarten
 * (Paesi Bassi)"), la nazione tradotta davanti all'etichetta per le regioni di una nazione divisa
 * ("Francia - Bretagna" -> "France - Bretagna"). Altrimenti resta com'e'.
 */
internal fun localizedInstalledName(displayName: String, countryCode: String?, locale: Locale): String {
    if (countryCode == null || locale.language == "it") return displayName
    val italian = countryName(countryCode, Locale.ITALIAN)
    val local = countryName(countryCode, locale).takeIf { !it.equals(countryCode, ignoreCase = true) } ?: return displayName
    return when {
        displayName == italian || displayName.startsWith("$italian (") -> local
        displayName.startsWith("$italian - ") -> local + displayName.removePrefix(italian)
        else -> displayName
    }
}

/**
 * Il nome di una regione installata nella lingua dell'interfaccia: dalla voce del catalogo salvato quando c'e' (con
 * l'etichetta inglese delle regioni di una nazione divisa, "Portugal - Madeira and Savage Islands"), altrimenti da
 * [localizedInstalledName], che traduce solo la nazione.
 */
internal fun installedRegionName(displayName: String, countryCode: String?, catalogEntry: RegionManifestEntry?, locale: Locale): String =
    catalogEntry?.localizedNames(locale)?.displayName ?: localizedInstalledName(displayName, countryCode, locale)

/** Il nome di una regione di una nazione divisa ("Francia - Bretagna", anche con la nazione tradotta da [localizedInstalledName]). */
internal fun isSplitCountryName(displayName: String, countryCode: String?, locale: Locale = Locale.getDefault()): Boolean =
    countryCode != null && listOf(Locale.ITALIAN, locale).any { displayName.startsWith("${countryName(countryCode, it)} - ") }

// Ricerca senza distinzione di maiuscole e accenti ("cina" trova "Cina", "sao" trova "São Tomé").
internal fun matchesQuery(displayName: String, query: String): Boolean {
    if (query.isBlank()) return true
    return displayName.foldForSearch().contains(query.trim().foldForSearch())
}

// Segni diacritici dopo la scomposizione NFD; compilata una volta sola (la ricerca la usa a ogni tasto).
private val DIACRITICS = Regex("\\p{Mn}+")

private fun String.foldForSearch(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")

// Le reti da scegliere una per una: solo se la regione ne ha piu' di una.
private fun RegionManifestEntry.transitNetworks(): List<TransitNetworkUi> {
    val transit = transit ?: return emptyList()
    if (transit.available.size <= 1) return emptyList()
    val chosen = transit.feeds.mapTo(mutableSetOf()) { it.id }
    return transit.available.map { TransitNetworkUi(it.id, it.name, it.downloadFile.sizeBytes, it.id in chosen) }
}
