package com.pockettravel.core.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FlagsTest {

    @Test
    fun `flagRes ignora le maiuscole`() {
        assertNotNull(flagRes("it"))
        assertEquals(flagRes("it"), flagRes("IT"))
        assertEquals(flagRes("gb"), flagRes("Gb"))
    }

    @Test
    fun `flagRes da' risorse diverse a paesi diversi`() {
        assertNotEquals(flagRes("it"), flagRes("fr"))
        assertNotEquals(flagRes("us"), flagRes("gb"))
    }

    @Test
    fun `flagRes ritorna null per codici sconosciuti o malformati`() {
        assertNull(flagRes(""))
        assertNull(flagRes("xx"))
        // Il codice ISO del Regno Unito e' "gb", non "uk".
        assertNull(flagRes("uk"))
        assertNull(flagRes("ita"))
        assertNull(flagRes(" it"))
        assertNull(flagRes("i"))
    }

    @Test
    fun `flagCropAlignment ritaglia dal lato del segno distintivo`() {
        assertEquals(Alignment.CenterStart, flagCropAlignment("ES"))
        assertEquals(Alignment.CenterEnd, flagCropAlignment("AU"))
        assertEquals(BiasAlignment(-0.5f, 0f), flagCropAlignment("US"))
        assertEquals(BiasAlignment(0.5f, 0f), flagCropAlignment("BT"))
    }

    @Test
    fun `flagCropAlignment ignora le maiuscole e centra gli altri paesi`() {
        assertEquals(flagCropAlignment("ES"), flagCropAlignment("es"))
        assertEquals(Alignment.Center, flagCropAlignment("IT"))
        assertEquals(Alignment.Center, flagCropAlignment(""))
        assertEquals(Alignment.Center, flagCropAlignment("xx"))
    }
}
