package com.pockettravel.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacingTest {

    @Test
    fun `la scala degli spazi cresce senza ripetizioni`() {
        val scale = listOf(Spacing.xs, Spacing.s, Spacing.m, Spacing.l, Spacing.xl, Spacing.xxl)
        scale.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", a < b) }
    }

    @Test
    fun `gli spazi sono multipli di 4 dp`() {
        listOf(Spacing.xs, Spacing.s, Spacing.m, Spacing.l, Spacing.xl, Spacing.xxl).forEach {
            assertEquals(0f, it.value % 4f, 0f)
        }
    }

    @Test
    fun `i valori di riferimento non cambiano`() {
        assertEquals(4.dp, Spacing.xs)
        assertEquals(8.dp, Spacing.s)
        assertEquals(12.dp, Spacing.m)
        assertEquals(16.dp, Spacing.l)
        assertEquals(24.dp, Spacing.xl)
        assertEquals(32.dp, Spacing.xxl)
    }

    @Test
    fun `la scheda informativa usa la scala degli spazi e un cerchio che contiene l'icona`() {
        assertEquals(Spacing.l, InfoCardDefaults.CardPadding)
        assertEquals(Spacing.l, InfoCardDefaults.CardGap)
        assertTrue(InfoCardDefaults.HeaderIconCircle > InfoCardDefaults.HeaderIconSize)
    }
}
