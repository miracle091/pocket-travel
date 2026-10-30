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
}
