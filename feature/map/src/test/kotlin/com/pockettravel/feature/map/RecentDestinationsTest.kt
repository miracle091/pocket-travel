package com.pockettravel.feature.map

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentDestinationsTest {

    @Test
    fun `le destinazioni tornano uguali dopo il salvataggio, anche con tab e a capo nel nome`() {
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

    private fun place(n: Int) = NavigationPlace("Destinazione $n", 40.0 + n, 10.0, "italia")

    @Test
    fun `senza scelte la lista e' vuota`() {
        assertEquals(emptyList<NavigationPlace>(), RecentDestinations(InMemoryPreferencesFactory()).places.value)
    }

    @Test
    fun `la destinazione nuova va in cima e resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        RecentDestinations(files).apply {
            add(place(1))
            add(place(2))
        }

        assertEquals(listOf(place(2), place(1)), RecentDestinations(files).places.value)
    }

    @Test
    fun `la stessa destinazione con le stesse coordinate non si ripete`() {
        val recent = RecentDestinations(InMemoryPreferencesFactory())
        recent.add(place(1))
        recent.add(place(2))
        recent.add(place(1).copy(name = "Altro nome"))

        assertEquals(listOf(place(1).copy(name = "Altro nome"), place(2)), recent.places.value)
    }

    @Test
    fun `la lista tiene al massimo dieci destinazioni`() {
        val recent = RecentDestinations(InMemoryPreferencesFactory())
        (1..12).forEach { recent.add(place(it)) }

        assertEquals(10, recent.places.value.size)
        assertEquals(place(12), recent.places.value.first())
        assertEquals(place(3), recent.places.value.last())
    }

    @Test
    fun `togliere una destinazione e svuotare la lista si salvano`() {
        val files = InMemoryPreferencesFactory()
        val recent = RecentDestinations(files)
        recent.add(place(1))
        recent.add(place(2))
        recent.remove(place(1))
        assertEquals(listOf(place(2)), RecentDestinations(files).places.value)

        recent.clear()
        assertEquals(emptyList<NavigationPlace>(), RecentDestinations(files).places.value)
    }
}
