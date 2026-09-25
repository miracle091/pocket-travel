package com.pockettravel.feature.map

import android.content.Context
import androidx.core.content.edit
import com.pockettravel.core.poi.PoiCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Categorie di POI nascoste sulla mappa, uguali per tutte le regioni e ricordate tra un'apertura e
 * l'altra. Si salvano le nascoste, non le visibili: una categoria nuova parte visibile.
 */
@Singleton
class MapFilterPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("map_filters", Context.MODE_PRIVATE)
    private val _hiddenCategories = MutableStateFlow<Set<PoiCategory>>(
        prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
            .mapNotNullTo(mutableSetOf()) { name -> PoiCategory.entries.firstOrNull { it.name == name } },
    )
    val hiddenCategories: StateFlow<Set<PoiCategory>> = _hiddenCategories.asStateFlow()

    fun setHidden(categories: Set<PoiCategory>) {
        prefs.edit { putStringSet(KEY_HIDDEN, categories.mapTo(mutableSetOf()) { it.name }) }
        _hiddenCategories.value = categories
    }

    private companion object {
        const val KEY_HIDDEN = "hidden_categories"
    }
}
