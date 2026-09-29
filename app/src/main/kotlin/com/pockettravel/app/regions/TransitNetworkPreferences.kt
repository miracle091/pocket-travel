package com.pockettravel.app.regions

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reti dei mezzi pubblici tolte dall'utente, per regione (id della regione -> id delle reti): di default
 * si scaricano tutte quelle della regione, e nel foglio Contenuti si tolgono quelle che non servono (il
 * Regno Unito ne ha 12, una per area).
 */
@Singleton
class TransitNetworkPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("transit_networks", Context.MODE_PRIVATE)
    private val _excluded = MutableStateFlow(read())
    val excluded: StateFlow<Map<String, Set<String>>> = _excluded.asStateFlow()

    fun setIncluded(regionId: String, feedId: String, included: Boolean) {
        val current = _excluded.value[regionId].orEmpty()
        val updated = if (included) current - feedId else current + feedId
        prefs.edit { if (updated.isEmpty()) remove(regionId) else putStringSet(regionId, updated) }
        _excluded.value = _excluded.value + (regionId to updated)
    }

    private fun read(): Map<String, Set<String>> =
        prefs.all.mapNotNull { (regionId, value) -> (value as? Set<*>)?.filterIsInstance<String>()?.toSet()?.let { regionId to it } }.toMap()
}
