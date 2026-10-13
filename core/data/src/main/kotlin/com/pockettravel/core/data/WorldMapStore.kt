package com.pockettravel.core.data

import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mappa del mondo a bassa risoluzione (z0-8), letta da feature/map con richieste range quando la mappa
 * della regione non e' scaricata; url e maxZoom arrivano da `manifest.json` ("worldMap", facoltativo) e
 * core/sync li salva qui ad ogni sync riuscita, azzerandoli se il catalogo non offre piu' la mappa del mondo.
 * feature/map non dipende da core/sync: legge solo questo store.
 */
@Singleton
class WorldMapStore @Inject constructor(preferences: PreferencesFactory) {

    private val prefs = preferences.open("world_map")

    fun worldMapUrl(): String? = prefs.getString(KEY_URL, null)

    fun worldMapMaxZoom(): Int = prefs.getInt(KEY_MAX_ZOOM, DEFAULT_MAX_ZOOM)

    fun save(url: String?, maxZoom: Int) = prefs.edit {
        if (url == null) remove(KEY_URL) else putString(KEY_URL, url)
        putInt(KEY_MAX_ZOOM, maxZoom)
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_MAX_ZOOM = "max_zoom"
        const val DEFAULT_MAX_ZOOM = 8
    }
}
