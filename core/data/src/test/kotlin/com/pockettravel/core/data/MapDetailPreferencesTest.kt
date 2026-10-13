package com.pockettravel.core.data

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapDetailPreferencesTest {

    @Test
    fun `senza scelte non c'e' niente`() {
        val preferences = MapDetailPreferences(InMemoryPreferencesFactory())

        assertTrue(preferences.choices.value.isEmpty())
        assertTrue(preferences.installedLight.value.isEmpty())
        assertNull(preferences.choice("italia"))
    }

    @Test
    fun `la scelta resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        MapDetailPreferences(files).setChoice("italia", true)
        MapDetailPreferences(files).setChoice("francia", false)

        val reopened = MapDetailPreferences(files)

        assertEquals(mapOf("italia" to true, "francia" to false), reopened.choices.value)
        assertEquals(false, reopened.choice("francia"))
    }

    @Test
    fun `la scelta cambia subito anche nel flusso`() {
        val preferences = MapDetailPreferences(InMemoryPreferencesFactory())
        preferences.setChoice("italia", true)
        preferences.setChoice("italia", false)

        assertEquals(mapOf("italia" to false), preferences.choices.value)
    }

    @Test
    fun `le regioni con la mappa leggera installata si aggiungono e si tolgono`() {
        val files = InMemoryPreferencesFactory()
        val preferences = MapDetailPreferences(files)
        preferences.setInstalledLight("italia", true)
        preferences.setInstalledLight("francia", true)
        preferences.setInstalledLight("francia", false)

        assertEquals(setOf("italia"), preferences.installedLight.value)
        assertEquals(setOf("italia"), MapDetailPreferences(files).installedLight.value)
    }

    @Test
    fun `legge il file salvato dall'app con lo stesso nome, le stesse chiavi e valori booleani`() {
        val files = InMemoryPreferencesFactory()
        files.open("map_detail").edit {
            putBoolean("choice:italia", false)
            putBoolean("installed:italia", true)
            putBoolean("installed:francia", false)
        }

        val preferences = MapDetailPreferences(files)

        assertEquals(mapOf("italia" to false), preferences.choices.value)
        assertEquals(setOf("italia"), preferences.installedLight.value)
    }

    @Test
    fun `scrive nel file e con le chiavi di sempre`() {
        val files = InMemoryPreferencesFactory()
        MapDetailPreferences(files).apply {
            setChoice("italia", true)
            setInstalledLight("italia", true)
        }

        assertEquals(setOf("choice:italia", "installed:italia"), files.open("map_detail").all.keys)
    }
}
