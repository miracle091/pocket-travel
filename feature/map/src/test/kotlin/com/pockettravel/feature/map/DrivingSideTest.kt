package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivingSideTest {
    @Test
    fun `un italiano nel Regno Unito o in Giappone guida a sinistra`() {
        assertEquals(DrivingSide.LEFT, drivingSideWarning(listOf("gb"), "IT"))
        assertEquals(DrivingSide.LEFT, drivingSideWarning(listOf("JP"), "it"))
    }

    @Test
    fun `un inglese in Francia guida a destra`() {
        assertEquals(DrivingSide.RIGHT, drivingSideWarning(listOf("fr"), "GB"))
    }

    @Test
    fun `Italia e Francia guidano dallo stesso lato, in entrambe le direzioni`() {
        assertNull(drivingSideWarning(listOf("fr"), "IT"))
        assertNull(drivingSideWarning(listOf("it"), "FR"))
    }

    @Test
    fun `Italia e Inghilterra no, in entrambe le direzioni`() {
        assertEquals(DrivingSide.LEFT, drivingSideWarning(listOf("gb"), "IT"))
        assertEquals(DrivingSide.RIGHT, drivingSideWarning(listOf("it"), "GB"))
    }

    @Test
    fun `stesso lato di casa, nessun avviso`() {
        assertNull(drivingSideWarning(listOf("lv"), "IT"))
        assertNull(drivingSideWarning(listOf("ie"), "GB"))
    }

    @Test
    fun `senza nazionalita' si avvisa solo dove si guida a sinistra`() {
        assertEquals(DrivingSide.LEFT, drivingSideWarning(listOf("ai"), null))
        assertNull(drivingSideWarning(listOf("es"), null))
    }

    @Test
    fun `un percorso che entra in un paese con l'altro lato avvisa`() {
        // Francia -> Regno Unito (tunnel della Manica) per un italiano.
        assertEquals(DrivingSide.LEFT, drivingSideWarning(listOf("fr", "gb"), "IT"))
    }
}
