package com.pockettravel.core.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Riquadro in gradi (WGS84) di una zona scelta dall'utente dentro una regione. */
data class RegionZone(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    init {
        require(minLon < maxLon && minLat < maxLat) { "Zona non valida" }
    }

    internal fun encode(): String = "$minLon,$minLat,$maxLon,$maxLat"

    internal companion object {
        fun decode(value: String): RegionZone? = value.split(',').mapNotNull { it.toDoubleOrNull() }
            .takeIf { it.size == 4 }
            ?.let { (minLon, minLat, maxLon, maxLat) -> runCatching { RegionZone(minLon, minLat, maxLon, maxLat) }.getOrNull() }
    }
}

/**
 * Zona di ogni regione da scaricare (solo per le nazioni grandi): mappa, rete stradale e civici si limitano al riquadro,
 * punti di interesse e guide restano interi. Assente = tutta la regione.
 */
@Singleton
class RegionZonePreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("region_zone", Context.MODE_PRIVATE)
    private val _zones = MutableStateFlow(
        prefs.all.mapNotNull { (regionId, value) -> (value as? String)?.let(RegionZone::decode)?.let { regionId to it } }.toMap(),
    )
    val zones: StateFlow<Map<String, RegionZone>> = _zones.asStateFlow()

    fun zone(regionId: String): RegionZone? = _zones.value[regionId]

    /** null torna a tutta la regione. */
    fun setZone(regionId: String, zone: RegionZone?) {
        prefs.edit { if (zone == null) remove(regionId) else putString(regionId, zone.encode()) }
        _zones.value = if (zone == null) _zones.value - regionId else _zones.value + (regionId to zone)
    }
}
