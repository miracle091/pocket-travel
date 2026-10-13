package com.pockettravel.core.data

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NationalityPreferencesTest {

    @Test
    fun `senza scelta vale il paese del telefono`() {
        val preferences = NationalityPreferences(InMemoryPreferencesFactory()) { "IT" }

        assertEquals("IT", preferences.nationality.value)
    }

    @Test
    fun `senza scelta e senza paese del telefono non c'e' nazionalita'`() {
        assertNull(NationalityPreferences(InMemoryPreferencesFactory()) { null }.nationality.value)
    }

    @Test
    fun `la nazionalita' scelta vince sul paese del telefono e resta`() {
        val files = InMemoryPreferencesFactory()
        NationalityPreferences(files) { "IT" }.setNationality("JP")

        assertEquals("JP", NationalityPreferences(files) { "IT" }.nationality.value)
    }

    @Test
    fun `la scelta cambia subito nel flusso`() {
        val preferences = NationalityPreferences(InMemoryPreferencesFactory()) { null }
        preferences.setNationality("FR")

        assertEquals("FR", preferences.nationality.value)
    }

    @Test
    fun `legge il file profile salvato dall'app`() {
        val files = InMemoryPreferencesFactory()
        files.open("profile").edit { putString("nationality", "DE") }

        assertEquals("DE", NationalityPreferences(files) { "IT" }.nationality.value)
    }
}
