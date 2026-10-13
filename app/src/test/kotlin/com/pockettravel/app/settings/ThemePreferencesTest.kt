package com.pockettravel.app.settings

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferencesTest {

    @Test
    fun `di default i colori dinamici sono accesi e il tema scuro forzato spento`() {
        val preferences = ThemePreferences(InMemoryPreferencesFactory())

        assertTrue(preferences.useDynamicColor.value)
        assertFalse(preferences.forceDark.value)
    }

    @Test
    fun `i colori dinamici cambiano subito e restano per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        val preferences = ThemePreferences(files)
        preferences.setUseDynamicColor(false)

        assertFalse(preferences.useDynamicColor.value)
        assertFalse(ThemePreferences(files).useDynamicColor.value)
    }

    @Test
    fun `il tema scuro forzato cambia subito e resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        val preferences = ThemePreferences(files)
        preferences.setForceDark(true)

        assertTrue(preferences.forceDark.value)
        assertTrue(ThemePreferences(files).forceDark.value)
    }

    @Test
    fun `le due scelte stanno nel file theme con le chiavi dynamic_color e force_dark`() {
        val files = InMemoryPreferencesFactory()
        ThemePreferences(files).apply {
            setUseDynamicColor(false)
            setForceDark(true)
        }
        val saved = files.open("theme")

        assertEquals(false, saved.getBoolean("dynamic_color", true))
        assertEquals(true, saved.getBoolean("force_dark", false))
    }
}
