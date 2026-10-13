package com.pockettravel.core.data

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldMapStoreTest {

    @Test
    fun `senza sincronizzazione non c'e' indirizzo e lo zoom e' 8`() {
        val store = WorldMapStore(InMemoryPreferencesFactory())

        assertNull(store.worldMapUrl())
        assertEquals(8, store.worldMapMaxZoom())
    }

    @Test
    fun `indirizzo e zoom salvati restano per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        WorldMapStore(files).save("https://github.com/o/r/world.pmtiles", 6)

        val reopened = WorldMapStore(files)

        assertEquals("https://github.com/o/r/world.pmtiles", reopened.worldMapUrl())
        assertEquals(6, reopened.worldMapMaxZoom())
    }

    @Test
    fun `un indirizzo null azzera la mappa del mondo ma lo zoom resta quello dato`() {
        val store = WorldMapStore(InMemoryPreferencesFactory())
        store.save("https://github.com/o/r/world.pmtiles", 6)
        store.save(null, 7)

        assertNull(store.worldMapUrl())
        assertEquals(7, store.worldMapMaxZoom())
    }

    @Test
    fun `legge il file world_map salvato dall'app`() {
        val files = InMemoryPreferencesFactory()
        files.open("world_map").edit {
            putString("url", "https://github.com/o/r/world.pmtiles")
            putInt("max_zoom", 5)
        }

        val store = WorldMapStore(files)

        assertEquals("https://github.com/o/r/world.pmtiles", store.worldMapUrl())
        assertEquals(5, store.worldMapMaxZoom())
    }
}
