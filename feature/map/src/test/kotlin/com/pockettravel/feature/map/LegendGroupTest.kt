package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegendGroupTest {

    @Test
    fun `ogni gruppo ha almeno una categoria`() {
        LegendGroup.entries.forEach { assertTrue(it.name, it.categories.isNotEmpty()) }
    }

    @Test
    fun `dentro un gruppo le categorie non si ripetono`() {
        LegendGroup.entries.forEach { assertEquals(it.name, it.categories.size, it.categories.toSet().size) }
    }

    @Test
    fun `ogni gruppo ha un titolo suo`() {
        assertEquals(LegendGroup.entries.size, LegendGroup.entries.map { it.label }.toSet().size)
    }
}
