package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamedCitiesTest {
    @Test
    fun `namedCities riconosce le citta' con l'articolo dopo una preposizione articolata`() {
        val cities = listOf("La Valletta", "L'Aquila", "La Spezia", "Sliema")

        assertEquals(listOf("La Valletta"), namedCities("Storia della Valletta?", cities))
        assertEquals(listOf("L'Aquila"), namedCities("Musei dell'Aquila", cities))
        assertEquals(listOf("La Spezia"), namedCities("Come arrivare alla Spezia?", cities))
        assertEquals(listOf("La Valletta"), namedCities("Cosa vedere a La Valletta?", cities))
        // Senza preposizione articolata il nome senza articolo non basta; in inglese non vale.
        assertTrue(namedCities("Una valletta verde", cities).isEmpty())
        assertTrue(namedCities("History of the Valletta harbour", cities, "en").isEmpty())
    }

    @Test
    fun `un elenco di citta' diverso o cambiato non usa i nomi normalizzati di quello di prima`() {
        val cities = mutableListOf("Roma (Lazio)", "Forlì")
        assertEquals(listOf("Forlì"), namedCities("Treni per Forli", cities))

        cities[1] = "Pisa"
        assertEquals(listOf("Pisa"), namedCities("Treni per Pisa", cities))
        assertTrue(namedCities("Treni per Forli", cities).isEmpty())
        assertEquals(listOf("Roma (Lazio)"), namedCities("Cosa vedere a Roma?", listOf("Roma (Lazio)")))
    }
}
