package com.pockettravel.app.settings

import com.pockettravel.core.data.testing.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun `le lingue sono italiano e inglese, in questo ordine`() {
        assertEquals(listOf(AppLanguage.ITALIAN, AppLanguage.ENGLISH), AppLanguage.entries)
    }

    @Test
    fun `i codici sono quelli delle risorse e delle guide`() {
        assertEquals("it", AppLanguage.ITALIAN.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
    }

    @Test
    fun `ogni codice e' una lingua valida e diversa dalle altre`() {
        val tags = AppLanguage.entries.map { it.tag }

        assertEquals(tags.size, tags.toSet().size)
        tags.forEach { assertEquals(it, Locale.forLanguageTag(it).language) }
    }

    @Test
    fun `ogni lingua si chiama nella propria lingua`() {
        assertEquals("Italiano", AppLanguage.ITALIAN.label())
        assertEquals("English", AppLanguage.ENGLISH.label())
    }

    @Test
    fun `i nomi mostrati sono tutti diversi`() {
        val labels = AppLanguage.entries.map { it.label() }

        assertNotEquals(labels[0], labels[1])
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `il nome della lingua e' indipendente dalla lingua del dispositivo`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            assertEquals("Italiano", AppLanguage.ITALIAN.label())
            assertEquals("English", AppLanguage.ENGLISH.label())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `senza una lingua scelta non c'e' un codice salvato`() {
        assertNull(AppLanguage.savedTag(InMemorySharedPreferences()))
    }

    @Test
    fun `il codice salvato si rilegge`() {
        val prefs = InMemorySharedPreferences()

        AppLanguage.saveTag(prefs, AppLanguage.ENGLISH)

        assertEquals("en", AppLanguage.savedTag(prefs))
    }

    @Test
    fun `una nuova scelta sostituisce la precedente`() {
        val prefs = InMemorySharedPreferences()
        AppLanguage.saveTag(prefs, AppLanguage.ENGLISH)

        AppLanguage.saveTag(prefs, AppLanguage.ITALIAN)

        assertEquals("it", AppLanguage.savedTag(prefs))
    }

    @Test
    fun `la lingua e' una stringa sotto la chiave tag`() {
        val prefs = InMemorySharedPreferences()

        AppLanguage.saveTag(prefs, AppLanguage.ITALIAN)

        assertEquals("it", prefs.getString("tag", null))
        assertEquals(1, prefs.all.size)
    }

    @Test
    fun `con il dispositivo in italiano o in inglese non si imposta nessuna lingua`() {
        assertNull(AppLanguage.defaultLocaleChange(current = null, appliedDefault = null, deviceLanguage = "it"))
        assertNull(AppLanguage.defaultLocaleChange(current = null, appliedDefault = null, deviceLanguage = "en"))
    }

    @Test
    fun `con il dispositivo in un'altra lingua si imposta l'inglese e si ricorda`() {
        assertEquals(
            DefaultLocaleChange("en"),
            AppLanguage.defaultLocaleChange(current = null, appliedDefault = null, deviceLanguage = "fr"),
        )
        assertNull(AppLanguage.defaultLocaleChange(current = "en", appliedDefault = "en", deviceLanguage = "fr"))
    }

    @Test
    fun `la lingua di default segue il dispositivo quando cambia`() {
        assertEquals(
            DefaultLocaleChange(null),
            AppLanguage.defaultLocaleChange(current = "en", appliedDefault = "en", deviceLanguage = "it"),
        )
    }

    @Test
    fun `la lingua scelta dall'utente non si tocca`() {
        assertNull(AppLanguage.defaultLocaleChange(current = "it", appliedDefault = null, deviceLanguage = "fr"))
        assertNull(AppLanguage.defaultLocaleChange(current = "it", appliedDefault = "en", deviceLanguage = "fr"))
    }
}
