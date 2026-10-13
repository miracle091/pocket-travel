package com.pockettravel.feature.map

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.poi.SIGHT_CATEGORIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFilterPreferencesTest {

    @Test
    fun `senza scelte nessuna categoria e' nascosta`() {
        val preferences = MapFilterPreferences(InMemoryPreferencesFactory())

        assertEquals(emptySet<PoiCategory>(), preferences.hiddenCategories.value)
        assertFalse(preferences.onlyAccessible.value)
    }

    @Test
    fun `le categorie nascoste restano per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        MapFilterPreferences(files).setHidden(setOf(PoiCategory.ZOO, PoiCategory.MUSEI_ARTE))

        assertEquals(setOf(PoiCategory.ZOO, PoiCategory.MUSEI_ARTE), MapFilterPreferences(files).hiddenCategories.value)
    }

    @Test
    fun `solo accessibili resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        MapFilterPreferences(files).setOnlyAccessible(true)

        assertTrue(MapFilterPreferences(files).onlyAccessible.value)
    }

    @Test
    fun `i nomi salvati di categorie che non esistono piu' si ignorano`() {
        val files = InMemoryPreferencesFactory()
        files.open("map_filters").edit { putStringSet("hidden_categories", setOf("ZOO", "CATEGORIA_VECCHIA")) }

        assertEquals(setOf(PoiCategory.ZOO), MapFilterPreferences(files).hiddenCategories.value)
    }

    @Test
    fun `Da vedere nascosto quando era una sola categoria si ritrova nascosto diviso`() {
        val files = InMemoryPreferencesFactory()
        files.open("map_filters").edit { putStringSet("hidden_categories", setOf(PoiCategory.ATTRAZIONI.name)) }

        assertEquals(SIGHT_CATEGORIES, MapFilterPreferences(files).hiddenCategories.value)
    }

    @Test
    fun `parchi divertimento nascosti nascondono anche zoo e parchi acquatici, una volta sola`() {
        val files = InMemoryPreferencesFactory()
        files.open("map_filters").edit {
            putBoolean("sights_split", true)
            putStringSet("hidden_categories", setOf(PoiCategory.PARCHI_DIVERTIMENTO.name))
        }

        val migrated = MapFilterPreferences(files).hiddenCategories.value
        assertEquals(setOf(PoiCategory.PARCHI_DIVERTIMENTO, PoiCategory.ZOO, PoiCategory.PARCHI_ACQUATICI), migrated)

        // Lo zoo tornato visibile dall'utente non viene nascosto di nuovo alla apertura successiva.
        MapFilterPreferences(files).setHidden(setOf(PoiCategory.PARCHI_DIVERTIMENTO))
        assertEquals(setOf(PoiCategory.PARCHI_DIVERTIMENTO), MapFilterPreferences(files).hiddenCategories.value)
    }
}
