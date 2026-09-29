package com.pockettravel.app.regions

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class InstalledRegionNameTest {
    @Test
    fun `in inglese il paese intero prende il nome dal codice`() {
        assertEquals("France", localizedInstalledName("Francia", "fr", Locale.ENGLISH))
        assertEquals("Sint Maarten", localizedInstalledName("Sint Maarten (Paesi Bassi)", "sx", Locale.ENGLISH))
    }

    @Test
    fun `regione di un paese diviso, il paese tradotto davanti all'etichetta`() {
        assertEquals("France - Bretagna", localizedInstalledName("Francia - Bretagna", "fr", Locale.ENGLISH))
    }

    @Test
    fun `in italiano o senza codice resta il nome salvato`() {
        assertEquals("Francia", localizedInstalledName("Francia", "fr", Locale.ITALIAN))
        assertEquals("Isole remote", localizedInstalledName("Isole remote", null, Locale.ENGLISH))
    }
}
