package com.pockettravel.core.transitrouting

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitNetworkTest {
    @Test
    fun `passaggi a piedi entro 300 m o nella stessa stazione, con il minuto di cambio`() {
        val stops = listOf(
            TransitStop(45.0, 9.0),
            TransitStop(45.00225, 9.0), // circa 250 m
            TransitStop(45.0036, 9.0), // circa 400 m
            TransitStop(45.0045, 9.0, parent = "stazione"), // circa 500 m, stessa stazione della seguente
            TransitStop(45.0, 9.0, parent = "stazione"),
        )

        val paths = footpaths(stops, TRANSFER_METERS)

        assertEquals(listOf(1, 4), paths[0].map { it.to }.sorted())
        // La 4 sta dove la 0: la 1 e' entro 300 m, la 3 e' della stessa stazione.
        assertEquals(listOf(0, 1, 3), paths[4].map { it.to }.sorted())
        // 250 m in linea d'aria: 325 m a piedi a 75 m al minuto, 5 minuti, piu' uno di cambio.
        assertEquals(6, paths[0].first { it.to == 1 }.minutes)
    }

    @Test
    fun `a longitudini alte il passaggio a piedi tra righe vicine della griglia resta`() {
        // 289 m lungo un meridiano, a cavallo di due righe: con la larghezza della cella presa dalla latitudine
        // di ogni fermata le due colonne distano 2 e la ricerca nelle celle accanto non le trova.
        val stops = listOf(TransitStop(35.00175, 150.0), TransitStop(35.00435, 150.0))

        val paths = footpaths(stops, TRANSFER_METERS)

        assertEquals(listOf(1), paths[0].map { it.to })
        assertEquals(listOf(0), paths[1].map { it.to })
    }

    @Test
    fun `minuti a piedi dalla linea d'aria`() {
        assertEquals(13, walkMinutes(750.0))
        assertEquals(0, walkMinutes(0.0))
    }

    @Test
    fun `pattern per fermata con la posizione`() {
        val network = TransitNetwork(
            listOf(TransitStop(45.0, 9.0), TransitStop(45.01, 9.0), TransitStop(45.02, 9.0)),
            listOf(TransitPattern(1, intArrayOf(0, 1, 2), emptyList()), TransitPattern(2, intArrayOf(2, 1), emptyList())),
        )

        assertEquals(listOf(0 to 1, 1 to 1), network.patternsAt[1])
        assertEquals(listOf(0 to 2, 1 to 0), network.patternsAt[2])
    }
}
