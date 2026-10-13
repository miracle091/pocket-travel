package com.pockettravel.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiColorsTest {

    private val categoryColors = mapOf(
        "Lodging" to PoiColors.Lodging,
        "FoodDrink" to PoiColors.FoodDrink,
        "Shopping" to PoiColors.Shopping,
        "Attractions" to PoiColors.Attractions,
        "Entertainment" to PoiColors.Entertainment,
        "Transport" to PoiColors.Transport,
        "PublicServices" to PoiColors.PublicServices,
        "Other" to PoiColors.Other,
    )

    @Test
    fun `il glifo e' bianco opaco`() {
        assertEquals(Color(0xFFFFFFFF), PoiColors.Glyph)
        assertEquals(1f, PoiColors.Glyph.alpha, 0f)
    }

    @Test
    fun `i colori delle categorie sono opachi`() {
        categoryColors.forEach { (name, color) -> assertEquals(name, 1f, color.alpha, 0f) }
    }

    @Test
    fun `ogni famiglia ha un colore diverso`() {
        assertEquals(categoryColors.size, categoryColors.values.toSet().size)
    }

    @Test
    fun `nessuna categoria usa il colore del glifo`() {
        categoryColors.forEach { (name, color) -> assertTrue(name, color != PoiColors.Glyph) }
    }

    @Test
    fun `il glifo bianco ha contrasto di almeno 3 a 1 su ogni colore`() {
        categoryColors.forEach { (name, color) ->
            val ratio = contrast(PoiColors.Glyph, color)
            assertTrue("$name: $ratio", ratio >= 3.0)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
