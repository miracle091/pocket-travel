package com.pockettravel.feature.map

import com.pockettravel.core.poi.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLegendTest {

    @Test
    fun `ogni categoria sta in uno e un solo gruppo della legenda`() {
        val grouped = LegendGroup.entries.flatMap { it.categories }

        assertEquals(PoiCategory.entries.sorted(), grouped.sorted())
    }
}
