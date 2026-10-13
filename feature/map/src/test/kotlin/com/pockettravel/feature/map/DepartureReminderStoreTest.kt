package com.pockettravel.feature.map

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepartureReminderStoreTest {

    private val files = InMemoryPreferencesFactory()
    private val store = DepartureReminderStore(files.open(DepartureReminderStore.PREFS))

    @Test
    fun `senza un avviso salvato load da' null`() {
        assertNull(store.load())
    }

    @Test
    fun `un avviso salvato si rilegge con ora e destinazione`() {
        store.save(1_700_000_000_000L, "Stazione")

        val saved = store.load()

        assertEquals(1_700_000_000_000L, saved?.triggerAt)
        assertEquals("Stazione", saved?.destination)
    }

    @Test
    fun `il nuovo avviso sostituisce il precedente`() {
        store.save(1_000L, "Stazione")
        store.save(2_000L, "Porto")

        assertEquals(2_000L, store.load()?.triggerAt)
        assertEquals("Porto", store.load()?.destination)
    }

    @Test
    fun `clear toglie l'avviso`() {
        store.save(1_000L, "Stazione")

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `un'ora non positiva vale come nessun avviso`() {
        files.open(DepartureReminderStore.PREFS).edit().putLong("trigger_at", 0L).putString("destination", "Stazione").apply()

        assertNull(store.load())
    }

    @Test
    fun `senza nome di destinazione il nome e' vuoto`() {
        files.open(DepartureReminderStore.PREFS).edit().putLong("trigger_at", 5L).apply()

        assertEquals("", store.load()?.destination)
    }

    @Test
    fun `file, chiavi e tipi sono quelli dell'app installata`() {
        store.save(42L, "Stazione")

        val raw = files.open("navigation_departure_reminder")
        assertEquals(42L, raw.getLong("trigger_at", 0L))
        assertEquals("Stazione", raw.getString("destination", null))
    }
}
