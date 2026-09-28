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
}
