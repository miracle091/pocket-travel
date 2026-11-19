package com.pockettravel.feature.map

import com.pockettravel.core.poi.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapPinTest {

    private fun pin(name: String?, nameEn: String? = null, nameIt: String? = null) =
        MapPin("1", name, 0.0, 0.0, PoiCategory.ATTRAZIONI, "tourism=attraction", null, nameEn = nameEn, nameIt = nameIt)

    @Test
    fun `nome nella lingua dell'interfaccia, altrimenti quello locale`() {
        assertEquals("Kiyomizu-dera", pin("清水寺", nameEn = "Kiyomizu-dera").displayName("en"))
        // In italiano, senza name:it, meglio la romanizzazione inglese dei caratteri locali.
        assertEquals("Kiyomizu-dera", pin("清水寺", nameEn = "Kiyomizu-dera").displayName("it"))
        assertEquals("Acropoli", pin("Ακρόπολη", nameEn = "Acropolis", nameIt = "Acropoli").displayName("it"))
        assertEquals("Da Mario", pin("Da Mario").displayName("en"))
        assertNull(pin(null).displayName("en"))
    }

    @Test
    fun `in una regione di lingua italiana, senza name it, in italiano vale il nome locale`() {
        val trevi = MapPin(
            "1", "Fontana di Trevi", 0.0, 0.0, PoiCategory.ATTRAZIONI, "tourism=attraction", null,
            nameEn = "Trevi Fountain", localNameIsItalian = true,
        )
        assertEquals("Fontana di Trevi", trevi.displayName("it"))
        assertEquals("Trevi Fountain", trevi.displayName("en"))
        assertEquals("Fontana", trevi.copy(nameIt = "Fontana").displayName("it"))
        // Altrove l'inglese resta il ripiego.
        assertEquals("Trevi Fountain", trevi.copy(localNameIsItalian = false).displayName("it"))
    }

    @Test
    fun `distintivo pieno per i posti accessibili, vuoto per quelli in parte, nessuno altrimenti`() {
        assertEquals(AccessibilityBadge.YES, accessibilityBadgeOf("yes"))
        assertEquals(AccessibilityBadge.YES, accessibilityBadgeOf("designated"))
        assertEquals(AccessibilityBadge.LIMITED, accessibilityBadgeOf("limited"))
        assertEquals(null, accessibilityBadgeOf("no"))
        assertEquals(null, accessibilityBadgeOf(null))
    }
}
