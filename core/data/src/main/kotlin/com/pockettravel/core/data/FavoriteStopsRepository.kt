package com.pockettravel.core.data

import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Fermata preferita: salvata come punto e nome, perche' gli id delle fermate cambiano a ogni ricostruzione degli orari. */
@Serializable
data class FavoriteStop(val name: String, val latitude: Double, val longitude: Double)

/** Le fermate preferite per regione. */
interface FavoriteStopsRepository {
    fun observe(regionId: String): Flow<List<FavoriteStop>>

    /** Senza effetto se la fermata (stesso nome, a meno di un metro) c'e' gia'. */
    suspend fun add(regionId: String, stop: FavoriteStop)

    /** Senza effetto se la fermata non c'e'. */
    suspend fun remove(regionId: String, stop: FavoriteStop)
}

/** [FavoriteStopsRepository] su SharedPreferences: una lista JSON per regione. */
@Singleton
class PreferencesFavoriteStopsRepository @Inject constructor(preferences: PreferencesFactory) : FavoriteStopsRepository {

    private val prefs = preferences.open("favorite_stops")
    private val state = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(KEY_PREFIX) }.mapNotNull { (key, value) ->
            val stops = runCatching { json.decodeFromString(serializer, value as String) }.getOrNull()
            stops?.let { key.removePrefix(KEY_PREFIX) to it }
        }.toMap(),
    )

    override fun observe(regionId: String): Flow<List<FavoriteStop>> =
        state.map { it[regionId].orEmpty() }.distinctUntilChanged()

    override suspend fun add(regionId: String, stop: FavoriteStop) =
        change(regionId) { list -> if (list.any { it.isSameAs(stop) }) list else list + stop }

    override suspend fun remove(regionId: String, stop: FavoriteStop) =
        change(regionId) { list -> list.filterNot { it.isSameAs(stop) } }

    private fun change(regionId: String, transform: (List<FavoriteStop>) -> List<FavoriteStop>) {
        val updated = state.updateAndGet { it + (regionId to transform(it[regionId].orEmpty())) }
        prefs.edit { putString(KEY_PREFIX + regionId, json.encodeToString(serializer, updated.getValue(regionId))) }
    }

    private fun FavoriteStop.isSameAs(other: FavoriteStop) =
        name == other.name && distanceMeters(latitude, longitude, other.latitude, other.longitude) <= SAME_POSITION_M

    private companion object {
        const val KEY_PREFIX = "stops:"
        const val SAME_POSITION_M = 1.0
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(FavoriteStop.serializer())
    }
}
