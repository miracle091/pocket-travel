package com.pockettravel.feature.map

import android.content.Context
import androidx.core.content.edit
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.poi.SIGHT_CATEGORIES
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Categorie di POI nascoste sulla mappa, uguali per tutte le regioni e ricordate tra un'apertura e
 * l'altra. Si salvano le nascoste, non le visibili: una categoria nuova parte visibile.
 */
@Singleton
class MapFilterPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("map_filters", Context.MODE_PRIVATE).apply {
        // "Da vedere" era una sola categoria (ATTRAZIONI): chi l'aveva nascosta la ritrova nascosta
        // anche divisa, invece di vedere comparire le categorie nuove.
        if (!getBoolean(KEY_SIGHTS_SPLIT, false)) {
            val hidden = getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
            edit {
                if (PoiCategory.ATTRAZIONI.name in hidden) putStringSet(KEY_HIDDEN, hidden + SIGHT_CATEGORIES.map { it.name })
                putBoolean(KEY_SIGHTS_SPLIT, true)
            }
        }
        // Zoo e parchi acquatici erano in "Parchi divertimento": stessa regola.
        if (!getBoolean(KEY_AMUSEMENT_SPLIT, false)) {
            val hidden = getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
            edit {
                if (PoiCategory.PARCHI_DIVERTIMENTO.name in hidden) {
                    putStringSet(KEY_HIDDEN, hidden + PoiCategory.ZOO.name + PoiCategory.PARCHI_ACQUATICI.name)
                }
                putBoolean(KEY_AMUSEMENT_SPLIT, true)
            }
        }
    }
    private val _hiddenCategories = MutableStateFlow<Set<PoiCategory>>(
        prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
            .mapNotNullTo(mutableSetOf()) { name -> PoiCategory.entries.firstOrNull { it.name == name } },
    )
    val hiddenCategories: StateFlow<Set<PoiCategory>> = _hiddenCategories.asStateFlow()

    private val _onlyAccessible = MutableStateFlow(prefs.getBoolean(KEY_ONLY_ACCESSIBLE, false))
    /** Con "In sedia a rotelle": solo i posti che OSM segna come accessibili, anche in parte (tag wheelchair). */
    val onlyAccessible: StateFlow<Boolean> = _onlyAccessible.asStateFlow()

    fun setOnlyAccessible(only: Boolean) {
        prefs.edit { putBoolean(KEY_ONLY_ACCESSIBLE, only) }
        _onlyAccessible.value = only
    }

    fun setHidden(categories: Set<PoiCategory>) {
        prefs.edit { putStringSet(KEY_HIDDEN, categories.mapTo(mutableSetOf()) { it.name }) }
        _hiddenCategories.value = categories
    }

    private companion object {
        const val KEY_HIDDEN = "hidden_categories"
        const val KEY_ONLY_ACCESSIBLE = "only_accessible"
        const val KEY_SIGHTS_SPLIT = "sights_split"
        const val KEY_AMUSEMENT_SPLIT = "amusement_split"
    }
}
