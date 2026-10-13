package com.pockettravel.core.sync

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPrefsPublishedAtStoreTest {

    @Test
    fun `un file mai visto non ha data`() {
        assertNull(SharedPrefsPublishedAtStore(InMemoryPreferencesFactory()).last("manifest.json"))
    }

    @Test
    fun `la data salvata resta per la prossima apertura, anche se e' zero`() {
        val files = InMemoryPreferencesFactory()
        SharedPrefsPublishedAtStore(files).save("manifest.json", 0L)
        SharedPrefsPublishedAtStore(files).save("transit.json", 1_700_000_000L)

        val reopened = SharedPrefsPublishedAtStore(files)

        assertEquals(0L, reopened.last("manifest.json"))
        assertEquals(1_700_000_000L, reopened.last("transit.json"))
    }

    @Test
    fun `forgetAllExcept tiene solo il file indicato`() {
        val store = SharedPrefsPublishedAtStore(InMemoryPreferencesFactory())
        store.save("manifest.json", 1L)
        store.save("transit.json", 2L)
        store.save("app-status.json", 3L)

        store.forgetAllExcept("app-status.json")

        assertNull(store.last("manifest.json"))
        assertNull(store.last("transit.json"))
        assertEquals(3L, store.last("app-status.json"))
    }

    @Test
    fun `legge il file published_at salvato dall'app`() {
        val files = InMemoryPreferencesFactory()
        files.open("published_at").edit { putLong("manifest.json", 42L) }

        assertEquals(42L, SharedPrefsPublishedAtStore(files).last("manifest.json"))
    }
}
