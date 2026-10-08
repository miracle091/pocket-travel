package com.pockettravel.feature.map

import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Una meta della navigazione: un punto di interesse o un punto qualsiasi con un nome. */
data class NavigationPlace(val name: String, val latitude: Double, val longitude: Double, val regionId: String) {
    val point: RoutePoint get() = RoutePoint(latitude, longitude)
}

// Per lo stato salvato dei ViewModel del Navigatore (SavedStateHandle).
internal fun NavigationPlace.toBundle() = Bundle().apply {
    putString("name", name)
    putDouble("latitude", latitude)
    putDouble("longitude", longitude)
    putString("regionId", regionId)
}

internal fun Bundle.toPlace(): NavigationPlace? =
    getString("name")?.let { NavigationPlace(it, getDouble("latitude"), getDouble("longitude"), getString("regionId").orEmpty()) }

/**
 * Ultime destinazioni scelte nella tab Navigazione, la piu' recente per prima, di tutte le regioni
 * (la tab mostra quelle installate). Restano sul telefono, come ogni altra preferenza.
 */
@Singleton
class RecentDestinations @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("navigation_recent", Context.MODE_PRIVATE)
    private val _places = MutableStateFlow(decode(prefs.getString(KEY_PLACES, null)))
    val places: StateFlow<List<NavigationPlace>> = _places.asStateFlow()

    /** In cima alla lista; la stessa destinazione (stesse coordinate) non si ripete. */
    fun add(place: NavigationPlace) {
        val updated = (listOf(place) + _places.value.filterNot { it.latitude == place.latitude && it.longitude == place.longitude })
            .take(MAX_PLACES)
        prefs.edit { putString(KEY_PLACES, encode(updated)) }
        _places.value = updated
    }

    fun remove(place: NavigationPlace) {
        val updated = _places.value - place
        prefs.edit { putString(KEY_PLACES, encode(updated)) }
        _places.value = updated
    }

    fun clear() {
        prefs.edit { remove(KEY_PLACES) }
        _places.value = emptyList()
    }

    internal companion object {
        const val KEY_PLACES = "places"
        const val MAX_PLACES = 10

        // Una riga per destinazione, campi separati da tab: niente libreria di serializzazione in feature:map per una lista corta.
        fun encode(places: List<NavigationPlace>): String = places.joinToString("\n") { place ->
            listOf(place.latitude, place.longitude, place.regionId, place.name.replace('\t', ' ').replace('\n', ' ')).joinToString("\t")
        }

        fun decode(text: String?): List<NavigationPlace> = text.orEmpty().lines().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 4) return@mapNotNull null
            val latitude = fields[0].toDoubleOrNull() ?: return@mapNotNull null
            val longitude = fields[1].toDoubleOrNull() ?: return@mapNotNull null
            NavigationPlace(fields[3], latitude, longitude, fields[2])
        }
    }
}
