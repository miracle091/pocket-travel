package com.pockettravel.core.sync

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.data.TransitFeedInfo

/** Una regione installata con almeno un pacchetto cambiato nel catalogo: [remote] porta anche civici e reti dei mezzi, [kinds] sono quelli da aggiornare. */
internal class RegionUpdate(val remote: RegionManifestEntry, val local: InstalledRegion, val kinds: Set<PackageKind>)

/**
 * Le regioni installate da aggiornare. Oltre a mappa, guida e il resto di `manifest.json`, confronta civici
 * ([addressIndex], limitati alla zona scelta, come l'elenco delle nazioni) e orari dei mezzi pubblici ([transitIndex]);
 * un indice null (catalogo senza, o non scaricato) lascia quei pacchetti fuori dal confronto. Per i mezzi si
 * confrontano le reti installate ([installedFeeds]) con quelle pubblicate: la scelta dell'utente sta nell'app, qui basta
 * che una rete installata abbia cambiato versione o sia sparita.
 */
internal fun regionUpdates(
    remotes: List<RegionManifestEntry>,
    installed: Map<String, InstalledRegion>,
    addressIndex: AddressGridIndex?,
    transitIndex: TransitIndex?,
    zoneOf: (String) -> RegionZone?,
    installedFeeds: (String) -> List<TransitFeedInfo>,
): List<RegionUpdate> {
    val candidates = remotes.filter { it.regionId in installed }
    return attachAddressGridCells(candidates, addressIndex).mapNotNull { grid ->
        val local = installed[grid.regionId] ?: return@mapNotNull null
        val remote = withInstalledTransit(grid.restrictedTo(zoneOf(grid.regionId)), local, transitIndex, installedFeeds)
        val outdated = remote.availableKinds.filterTo(mutableSetOf()) { kind -> local.versionOf(kind)?.let { it != remote.versionOf(kind) } == true }
        if (outdated.isEmpty()) null else RegionUpdate(remote, local, outdated)
    }
}

private fun withInstalledTransit(
    entry: RegionManifestEntry,
    local: InstalledRegion,
    index: TransitIndex?,
    installedFeeds: (String) -> List<TransitFeedInfo>,
): RegionManifestEntry {
    val all = if (index == null || local.transitVersion == null) emptyList() else regionTransitFeeds(index, entry.regionId)
    if (all.isEmpty()) return entry
    val installedIds = installedFeeds(entry.regionId).mapTo(mutableSetOf()) { it.id }
    val chosen = all.filter { it.id in installedIds }.ifEmpty { all }
    return entry.copy(transit = RegionTransitEntry(feeds = chosen, available = all))
}
