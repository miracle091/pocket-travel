package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentDestinationsTest {

    @Test
    fun `le mete tornano uguali dopo il salvataggio, anche con tab e a capo nel nome`() {
        val places = listOf(
            NavigationPlace("Rīgas Centrālā stacija", 56.9466, 24.1206, "lettonia"),
            NavigationPlace("Bar\tcon\na capo", 43.93, 12.45, "san-marino"),
        )

        val decoded = RecentDestinations.decode(RecentDestinations.encode(places))

        assertEquals(places[0], decoded[0])
        assertEquals("Bar con a capo", decoded[1].name)
        assertEquals(places[1].latitude, decoded[1].latitude, 0.0)
    }

    @Test
    fun `righe rovinate o vuote si saltano`() {
        assertEquals(emptyList<NavigationPlace>(), RecentDestinations.decode(null))
        assertEquals(1, RecentDestinations.decode("x\ty\tz\tw\n1.0\t2.0\tit\tRoma\nsolo testo").size)
    }
}
