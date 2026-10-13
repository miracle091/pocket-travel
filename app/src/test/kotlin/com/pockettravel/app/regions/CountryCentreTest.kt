package com.pockettravel.app.regions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryCentreTest {

    // Anello rettangolare come lon, lat alternati.
    private fun box(west: Double, south: Double, east: Double, north: Double) =
        doubleArrayOf(west, south, east, south, east, north, west, north, west, south)

    @Test
    fun `un paese senza poligoni non ha centro`() {
        assertNull(countryCentre(emptyList()))
    }

    @Test
    fun `un poligono senza anelli e' saltato`() {
        val centre = countryCentre(listOf(emptyList(), listOf(box(0.0, 0.0, 10.0, 20.0))))

        assertEquals(10.0, centre?.latitude ?: Double.NaN, 1e-9)
        assertEquals(5.0, centre?.longitude ?: Double.NaN, 1e-9)
    }

    @Test
    fun `solo poligoni vuoti non danno centro`() {
        assertNull(countryCentre(listOf(emptyList(), emptyList())))
    }

    @Test
    fun `la parte piu' grande decide il centro`() {
        val centre = countryCentre(listOf(listOf(box(0.0, 0.0, 1.0, 1.0)), listOf(box(10.0, 10.0, 20.0, 30.0))))

        assertEquals(20.0, centre?.latitude ?: Double.NaN, 1e-9)
        assertEquals(15.0, centre?.longitude ?: Double.NaN, 1e-9)
    }

    @Test
    fun `un poligono a cavallo dell'antimeridiano ha il centro vicino a 180 gradi`() {
        // Vertici fra 177 e -179 (come le Figi): srotolati vanno da 177 a 182, centro 179,5.
        val ring = doubleArrayOf(177.0, -18.0, 180.0, -17.0, -178.0, -16.0, -179.0, -19.0, 177.0, -18.0)
        val centre = countryCentre(listOf(listOf(ring)))

        assertEquals(-17.5, centre?.latitude ?: Double.NaN, 1e-9)
        assertEquals(179.5, centre?.longitude ?: Double.NaN, 1e-9)
    }

    @Test
    fun `un poligono a cavallo dell'antimeridiano con centro a est resta in -180 180`() {
        val ring = doubleArrayOf(170.0, 60.0, 180.0, 65.0, -170.0, 70.0, 170.0, 60.0)
        val centre = countryCentre(listOf(listOf(ring)))

        // Longitudini srotolate: 170..190, centro 180 che si normalizza a -180 o 180.
        assertEquals(180.0, Math.abs(centre?.longitude ?: Double.NaN), 1e-9)
    }
}
