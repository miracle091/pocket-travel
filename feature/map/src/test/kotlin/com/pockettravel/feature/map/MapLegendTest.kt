package com.pockettravel.feature.map

import com.pockettravel.core.poi.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLegendTest {

    @Test
    fun `ogni categoria tranne ALTRO sta in uno e un solo gruppo della legenda`() {
        val grouped = LegendGroup.entries.flatMap { it.categories }

        // ALTRO non e' mai sulla mappa (isPoiHiddenOnMap): niente filtro.
        assertEquals((PoiCategory.entries - PoiCategory.ALTRO).sorted(), grouped.sorted())
    }
}
