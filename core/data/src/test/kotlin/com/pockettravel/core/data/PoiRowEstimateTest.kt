package com.pockettravel.core.data

import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.estimatedStorageBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiRowEstimateTest {
    private fun row(name: String, address: String? = null) =
        PoiRow(regionRef = 1, code = 1, latE6 = 45_000_000, lonE6 = 9_000_000, name = name, address = address)

    @Test
    fun `una riga tipica pesa circa quanto in region_db`() {
        // Italia: 93,6 MB per 956 mila POI con 24,6 byte di stringhe a riga, cioe' circa 98 byte.
        val bytes = row("a".repeat(25)).estimatedStorageBytes()
        assertTrue("$bytes", bytes in 90..105)
    }

    @Test
    fun `le stringhe contano in byte UTF-8 e le colonne vuote niente`() {
        val ascii = row("Roma").estimatedStorageBytes()
        // "è" sono 2 byte: 4 caratteri, 5 byte contro 4.
        assertEquals(1L, row("Romè").estimatedStorageBytes() - ascii)
        assertTrue(row("Roma", address = "Via Roma 1").estimatedStorageBytes() > ascii)
    }
}
