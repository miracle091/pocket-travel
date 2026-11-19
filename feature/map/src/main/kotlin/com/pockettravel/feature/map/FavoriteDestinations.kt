package com.pockettravel.feature.map

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Destinazioni preferite, con il nome scelto da chi le salva ("Alloggio", "Da Marco"): la piu' recente per prima, di tutte
 * le regioni (la scheda mostra quelle installate). Restano sul dispositivo, come i recenti.
 */
@Singleton
class FavoriteDestinations @Inject constructor(preferences: PreferencesFactory) {
    private val prefs = preferences.open("navigation_favorites")
    private val _places = MutableStateFlow(RecentDestinations.decode(prefs.getString(KEY_PLACES, null)))
    val places: StateFlow<List<NavigationPlace>> = _places.asStateFlow()

    /** [place] col nome [name]; un preferito con le stesse coordinate prende il nome nuovo. Un nome vuoto non si salva. */
    fun save(place: NavigationPlace, name: String) {
        val clean = name.trim().take(MAX_NAME_LENGTH)
        if (clean.isEmpty()) return
        write(listOf(place.copy(name = clean)) + _places.value.filterNot { it.isSamePoint(place) })
    }

    fun remove(place: NavigationPlace) = write(_places.value.filterNot { it.isSamePoint(place) })

    fun contains(place: NavigationPlace): Boolean = _places.value.any { it.isSamePoint(place) }

    private fun write(updated: List<NavigationPlace>) {
        val limited = updated.take(MAX_PLACES)
        prefs.edit { putString(KEY_PLACES, RecentDestinations.encode(limited)) }
        _places.value = limited
    }

    internal companion object {
        const val KEY_PLACES = "places"
        const val MAX_PLACES = 50
        const val MAX_NAME_LENGTH = 60
    }
}

/** Stesso punto: i recenti e i preferiti si riconoscono dalle coordinate, non dal nome. */
internal fun NavigationPlace.isSamePoint(other: NavigationPlace): Boolean = latitude == other.latitude && longitude == other.longitude
