package com.pockettravel.feature.map

import com.pockettravel.core.data.Poi
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRelevanceTest {

    private fun poi(name: String, category: String, osmTag: String, nameIt: String? = null) =
        Poi(id = 0, regionId = "francia-ile-de-france", name = name, category = category, latitude = 48.86, longitude = 2.33, osmTag = osmTag, phone = null, nameIt = nameIt)

    private val tour = poi("Tour Eiffel", "attraction", "tourism=attraction", nameIt = "Torre Eiffel")
    private val museum = poi("Musée du Louvre", "museum", "tourism=museum")
    private val hotel = poi("Hôtel Louvre Piémont", "hotel", "tourism=hotel")
    private val taxi = poi("Louvre", "taxi", "amenity=taxi")

    @Test
    fun `il posto da visitare col nome uguale viene per primo, anche col nome italiano e senza accenti`() {
        assertEquals(0, searchRelevance(tour, "tour eiffel"))
        assertEquals(0, searchRelevance(tour, "Torre  Eiffel"))
    }

    @Test
    fun `i posti da visitare vengono prima di hotel e taxi col loro nome`() {
        val ranked = listOf(hotel, taxi, museum).sortedBy { searchRelevance(it, "Louvre") }
        assertEquals(listOf(museum, taxi, hotel), ranked)
    }
}
