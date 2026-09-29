package com.pockettravel.app.regions

import android.content.Context
import androidx.core.content.edit
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.TransitIndex
import com.pockettravel.core.sync.defaultTransitExclusions
import com.pockettravel.core.sync.regionTransitFeeds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reti dei mezzi pubblici tolte dall'utente, per regione (id della regione -> id delle reti). Senza una
 * scelta vale defaultTransitExclusions (con molte reti, come le 12 aree del Regno Unito, solo quelle
 * vicine alla posizione); al primo download degli orari la scelta si salva e non cambia piu' da sola.
 */
@Singleton
class TransitNetworkPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("transit_networks", Context.MODE_PRIVATE)
    private val _excluded = MutableStateFlow(read())
    val excluded: StateFlow<Map<String, Set<String>>> = _excluded.asStateFlow()

    /** [defaults]: le reti tolte di default (reti vicine) se l'utente non ha ancora scelto. */
    fun setIncluded(regionId: String, feedId: String, included: Boolean, defaults: Set<String>) {
        val current = _excluded.value[regionId] ?: defaults
        setExcluded(regionId, if (included) current - feedId else current + feedId)
    }

    /** La scelta diventa dell'utente: salvata anche vuota, cosi' la posizione non la cambia piu'. */
    fun setExcluded(regionId: String, excluded: Set<String>) {
        prefs.edit { putStringSet(regionId, excluded) }
        _excluded.value = _excluded.value + (regionId to excluded)
    }

    private fun read(): Map<String, Set<String>> =
        prefs.all.mapNotNull { (regionId, value) -> (value as? Set<*>)?.filterIsInstance<String>()?.toSet()?.let { regionId to it } }.toMap()
}

/**
 * Le reti tolte per ogni regione di [regionIds]: la scelta salvata dell'utente o, senza, quella di default
 * (reti vicine a [position] quando la regione ne ha molte).
 */
internal fun effectiveTransitExclusions(
    regionIds: List<String>,
    index: TransitIndex?,
    stored: Map<String, Set<String>>,
    position: Pair<Double, Double>?,
): Map<String, Set<String>> {
    if (index == null) return stored
    return regionIds.associateWith { id ->
        stored[id] ?: defaultTransitExclusions(regionTransitFeeds(index, id), position?.first, position?.second)
    }
}

/** Al download degli orari la scelta di default diventa quella salvata: spostandosi non cambia piu'. */
internal fun TransitNetworkPreferences.rememberChoice(entry: RegionManifestEntry, kinds: Set<PackageKind>) {
    val transit = entry.transit ?: return
    if (PackageKind.TRANSIT !in kinds || excluded.value.containsKey(entry.regionId)) return
    val chosen = transit.feeds.mapTo(mutableSetOf()) { it.id }
    setExcluded(entry.regionId, transit.available.map { it.id }.filterNot { it in chosen }.toSet())
}
