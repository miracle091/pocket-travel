package com.pockettravel.app.regions

import android.content.Context
import androidx.core.content.edit
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.TransitIndex
import com.pockettravel.core.sync.TransitDefaultReason
import com.pockettravel.core.sync.defaultTransitChoice
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

/** Dove si trova il telefono: ultima posizione nota e paese (CountryLocator), null se non nota. */
internal data class DevicePlace(val latitude: Double, val longitude: Double, val countryCode: String?)

/** Le reti tolte per regione e, per quelle con la scelta di default, il perche'. */
internal data class TransitChoices(val excluded: Map<String, Set<String>>, val reasons: Map<String, TransitDefaultReason>)

/**
 * Per ogni regione di [regions]: la scelta salvata dell'utente o, senza, quella di default (defaultTransitChoice).
 * La posizione conta solo se e' nella nazione della regione: da Como le reti svizzere non sono "vicine".
 */
internal fun effectiveTransitChoices(
    regions: List<RegionManifestEntry>,
    index: TransitIndex?,
    stored: Map<String, Set<String>>,
    place: DevicePlace?,
): TransitChoices {
    if (index == null) return TransitChoices(stored, emptyMap())
    val excluded = mutableMapOf<String, Set<String>>()
    val reasons = mutableMapOf<String, TransitDefaultReason>()
    for (region in regions) {
        val saved = stored[region.regionId]
        if (saved != null) {
            excluded[region.regionId] = saved
            continue
        }
        val here = place?.takeIf { it.countryCode != null && it.countryCode.equals(region.countryCode, ignoreCase = true) }
        val choice = defaultTransitChoice(regionTransitFeeds(index, region.regionId), here?.latitude, here?.longitude)
        excluded[region.regionId] = choice.excluded
        choice.reason?.let { reasons[region.regionId] = it }
    }
    return TransitChoices(excluded, reasons)
}

/** Al download degli orari la scelta di default diventa quella salvata: spostandosi non cambia piu'. */
internal fun TransitNetworkPreferences.rememberChoice(entry: RegionManifestEntry, kinds: Set<PackageKind>) {
    val transit = entry.transit ?: return
    if (PackageKind.TRANSIT !in kinds || excluded.value.containsKey(entry.regionId)) return
    val chosen = transit.feeds.mapTo(mutableSetOf()) { it.id }
    setExcluded(entry.regionId, transit.available.map { it.id }.filterNot { it in chosen }.toSet())
}
