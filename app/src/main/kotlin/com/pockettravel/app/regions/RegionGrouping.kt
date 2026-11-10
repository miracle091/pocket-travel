package com.pockettravel.app.regions

import java.text.Collator
import java.util.Locale

// Raggruppamento dell'elenco regioni, senza interfaccia: sezioni, nazioni divise in regioni e righe visibili.

// Ordine dei continenti come in regions.sh/assemble-site.sh; un continente sconosciuto va in
// coda, le regioni senza continente (cataloghi pubblicati prima del campo) nel gruppo "Altro".
private val CONTINENT_ORDER = listOf("Europa", "Asia", "Africa", "Nord America", "Sud America", "Oceania")

// Gruppo dell'elenco regioni: prima "Nazioni scaricate" (scaricate o da aggiornare), poi i continenti
// con le sole nazioni non ancora scaricate.
internal sealed interface RegionGroup {
    val key: String

    data object Downloaded : RegionGroup {
        override val key = "downloaded"
    }

    data class Continent(val name: String?) : RegionGroup {
        override val key = "continent_" + (name ?: "")
    }
}

// Voce di un continente: una regione, oppure una nazione divisa in piu' regioni (stesso groupName).
internal sealed interface RegionListEntry {
    data class Single(val item: RegionUiItem) : RegionListEntry

    data class Country(val name: String, val countryCode: String?, val items: List<RegionUiItem>) : RegionListEntry
}

/**
 * Raccoglie in una voce nazione le regioni con lo stesso groupName, anche quando nell'elenco ce n'e' una sola
 * (le altre sono nell'altra sezione), nell'ordine per nome.
 */
internal fun countryEntries(regions: List<RegionUiItem>): List<RegionListEntry> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val byGroup = regions.mapNotNull { item -> item.groupName?.let { it to item } }.groupBy({ it.first }, { it.second })
    val entries = regions.filter { it.groupName !in byGroup }.map<RegionUiItem, RegionListEntry> { RegionListEntry.Single(it) } +
        byGroup.map { (name, items) ->
            RegionListEntry.Country(name, items.first().countryCode, items.sortedWith(compareBy(collator) { it.groupLabel ?: it.displayName }))
        }
    return entries.sortedWith(
        compareBy(collator) { entry ->
            when (entry) {
                is RegionListEntry.Single -> entry.item.displayName
                is RegionListEntry.Country -> entry.name
            }
        },
    )
}

// Righe visibili di un continente aperto: le nazioni chiuse mostrano solo la propria riga.
internal sealed interface ListRow {
    val key: String

    data class Region(val item: RegionUiItem, val inCountry: Boolean) : ListRow {
        override val key = item.regionId
    }

    data class Replaced(val item: ReplacedRegionItem) : ListRow {
        override val key = "replaced_" + item.regionId
    }

    // La stessa nazione puo' stare sia tra le scaricate sia nel suo continente: la chiave dice la sezione.
    data class CountryHeader(val country: RegionListEntry.Country, val expanded: Boolean, override val key: String = COUNTRY_PREFIX + country.name) : ListRow
}

internal const val COUNTRY_PREFIX = "country_"
internal const val DOWNLOADED_COUNTRY_PREFIX = "downloaded_country_"

internal fun RegionListEntry.hasUpdate(): Boolean = when (this) {
    is RegionListEntry.Single -> item.status == RegionStatus.UPDATE_AVAILABLE
    is RegionListEntry.Country -> items.any { it.status == RegionStatus.UPDATE_AVAILABLE }
}

internal fun visibleRows(
    entries: List<RegionListEntry>,
    keyPrefix: String = COUNTRY_PREFIX,
    isExpanded: (String) -> Boolean,
): List<ListRow> = entries.flatMap { entry ->
    when (entry) {
        is RegionListEntry.Single -> listOf(ListRow.Region(entry.item, inCountry = false))
        is RegionListEntry.Country -> {
            val expanded = isExpanded(entry.name)
            listOf(ListRow.CountryHeader(entry, expanded, keyPrefix + entry.name)) +
                if (expanded) entry.items.map { ListRow.Region(it, inCountry = true) } else emptyList()
        }
    }
}

/** [downloading]: le regioni con un download in corso o in coda, che stanno gia' con le nazioni scaricate. */
internal fun groupRegions(items: List<RegionUiItem>, downloading: Set<String> = emptySet()): List<Pair<RegionGroup, List<RegionUiItem>>> {
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val byName = compareBy(collator) { item: RegionUiItem -> item.displayName }
    val (downloaded, others) = items.partition { it.status != RegionStatus.NOT_INSTALLED || it.regionId in downloading }
    val downloadedGroup = downloaded
        // Da aggiornare prima delle gia' aggiornate, poi per nome.
        .sortedWith(compareBy<RegionUiItem> { it.status != RegionStatus.UPDATE_AVAILABLE }.then(byName))
        .takeIf { it.isNotEmpty() }
        ?.let { listOf(RegionGroup.Downloaded to it) }
        .orEmpty()
    val continentGroups = others
        .groupBy { it.continent }
        .toList()
        .sortedBy { (continent, _) ->
            when (continent) {
                null -> Int.MAX_VALUE
                in CONTINENT_ORDER -> CONTINENT_ORDER.indexOf(continent)
                else -> CONTINENT_ORDER.size
            }
        }
        .map { (continent, regions) -> RegionGroup.Continent(continent) to regions.sortedWith(byName) }
    return downloadedGroup + continentGroups
}
