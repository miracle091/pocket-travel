package com.pockettravel.feature.map

import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import com.pockettravel.core.data.PreferencesFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Una destinazione della navigazione: un punto di interesse o un punto qualsiasi con un nome. */
data class NavigationPlace(val name: String, val latitude: Double, val longitude: Double, val regionId: String) {
    val point: RoutePoint get() = RoutePoint(latitude, longitude)
}

// Per lo stato salvato dei ViewModel del Navigatore (SavedStateHandle): la stessa riga dei Recenti.
internal fun NavigationPlace.toSavedState(): String = RecentDestinations.encode(listOf(this))

internal fun String.toPlace(): NavigationPlace? = RecentDestinations.decode(this).firstOrNull()

// Le versioni precedenti salvavano un Bundle: dopo un aggiornamento con il processo ucciso quel valore si ignora.
internal fun SavedStateHandle.savedPlace(key: String): NavigationPlace? = (get<Any?>(key) as? String)?.toPlace()

/**
 * Ultime destinazioni scelte nella scheda Navigatore, la piu' recente per prima, di tutte le regioni
 * (la scheda mostra quelle installate). Restano sul dispositivo, come ogni altra preferenza.
 */
@Singleton
class RecentDestinations @Inject constructor(preferences: PreferencesFactory) {
    private val prefs = preferences.open("navigation_recent")
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
        private const val FIELD_COUNT = 4
        private const val NAME_FIELD = 3

        // Una riga per destinazione, campi separati da tab: niente libreria di serializzazione in feature:map per una lista corta.
        fun encode(places: List<NavigationPlace>): String = places.joinToString("\n") { place ->
            listOf(place.latitude, place.longitude, place.regionId, place.name.replace('\t', ' ').replace('\n', ' ')).joinToString("\t")
        }

        fun decode(text: String?): List<NavigationPlace> = text.orEmpty().lines().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != FIELD_COUNT) return@mapNotNull null
            val latitude = fields[0].toDoubleOrNull() ?: return@mapNotNull null
            val longitude = fields[1].toDoubleOrNull() ?: return@mapNotNull null
            NavigationPlace(fields[NAME_FIELD], latitude, longitude, fields[2])
        }
    }
}
