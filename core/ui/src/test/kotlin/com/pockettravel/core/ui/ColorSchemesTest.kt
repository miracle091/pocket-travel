package com.pockettravel.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSchemesTest {

    private val lights = mapOf(
        "light" to lightScheme,
        "lightMedium" to lightSchemeMediumContrast,
        "lightHigh" to lightSchemeHighContrast,
    )
    private val darks = mapOf(
        "dark" to darkScheme,
        "darkMedium" to darkSchemeMediumContrast,
        "darkHigh" to darkSchemeHighContrast,
    )
    private val all = lights + darks

    @Test
    fun `le coppie colore e testo sopra hanno contrasto di almeno 4,5 a 1`() {
        all.forEach { (name, s) ->
            pairs(s).forEach { (pair, colors) ->
                val ratio = contrast(colors.first, colors.second)
                assertTrue("$name/$pair: $ratio", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `superficie e sfondo hanno contrasto di almeno 7 a 1 negli schemi ad alto contrasto`() {
        listOf(lightSchemeHighContrast, darkSchemeHighContrast).forEach { s ->
            assertTrue(contrast(s.onSurface, s.surface) >= 7.0)
            assertTrue(contrast(s.onBackground, s.background) >= 7.0)
        }
    }

    @Test
    fun `tutti i colori principali sono opachi`() {
        all.forEach { (name, s) ->
            listOf(s.primary, s.secondary, s.tertiary, s.error, s.surface, s.background, s.outline).forEach {
                assertEquals(name, 1f, it.alpha, 0f)
            }
        }
    }

    @Test
    fun `lo scuro e il chiaro hanno sfondi diversi e di luminosita' opposta`() {
        lights.values.forEach { assertTrue(luminance(it.surface) > 0.5) }
        darks.values.forEach { assertTrue(luminance(it.surface) < 0.1) }
    }

    @Test
    fun `i livelli di contrasto cambiano il colore primario`() {
        assertNotEquals(lightScheme.primary, lightSchemeMediumContrast.primary)
        assertNotEquals(lightSchemeMediumContrast.primary, lightSchemeHighContrast.primary)
        assertNotEquals(darkScheme.primary, darkSchemeMediumContrast.primary)
        assertNotEquals(darkSchemeMediumContrast.primary, darkSchemeHighContrast.primary)
    }

    @Test
    fun `il colore primario inverso e' il primario dell'altro tema`() {
        assertEquals(darkScheme.primary, lightScheme.inversePrimary)
        assertEquals(lightScheme.primary, darkScheme.inversePrimary)
    }

    @Test
    fun `lo scrim e' nero in ogni schema`() {
        all.values.forEach { assertEquals(Color(0xFF000000), it.scrim) }
    }

    private fun pairs(s: ColorScheme): Map<String, Pair<Color, Color>> = mapOf(
        "primary" to (s.onPrimary to s.primary),
        "primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "secondary" to (s.onSecondary to s.secondary),
        "secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "tertiary" to (s.onTertiary to s.tertiary),
        "tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "error" to (s.onError to s.error),
        "errorContainer" to (s.onErrorContainer to s.errorContainer),
        "surface" to (s.onSurface to s.surface),
        "background" to (s.onBackground to s.background),
        "inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
    )

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
