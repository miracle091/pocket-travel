package com.pockettravel.core.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mondo online a bassa risoluzione (z0-8), letto da feature/map con richieste range quando la mappa
 * della regione non e' scaricata; url e maxZoom arrivano dal manifest ("worldMap", facoltativo) e
 * core/sync li salva qui ad ogni sync riuscita, azzerandoli se il manifest non offre piu' il mondo.
 * feature/map non dipende da core/sync: legge solo questo store.
 */
@Singleton
class WorldMapStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("world_map", Context.MODE_PRIVATE)

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
