package com.pockettravel.feature.map

import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.poi.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitDeparturesTest {

    @Test
    fun `una stazione ferroviaria cerca treni e metro`() {
        assertEquals(setOf(TransitMode.TRAIN, TransitMode.METRO), transitStationModes("railway=station"))
    }

    @Test
    fun `un'autostazione cerca autobus e filobus`() {
        assertEquals(setOf(TransitMode.BUS, TransitMode.TROLLEYBUS), transitStationModes("amenity=bus_station"))
    }

    @Test
    fun `un terminal dei traghetti cerca i traghetti`() {
        assertEquals(setOf(TransitMode.FERRY), transitStationModes("amenity=ferry_terminal"))
    }

    @Test
    fun `fermate semplici e tag sconosciuti non allargano la ricerca`() {
        assertTrue(transitStationModes("railway=halt").isEmpty())
        assertTrue(transitStationModes("highway=bus_stop").isEmpty())
        assertTrue(transitStationModes("").isEmpty())
        // Il tag si confronta per intero: nessuna corrispondenza parziale.
        assertTrue(transitStationModes("railway=station;subway").isEmpty())
    }

    @Test
    fun `gli orari si propongono per treno, metro, autobus e traghetto`() {
        assertEquals(
            setOf(PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TRAGHETTO),
            TRANSIT_CATEGORIES,
        )
    }

    @Test
    fun `taxi e aeroporto non hanno la sezione delle partenze`() {
        assertTrue(PoiCategory.TAXI !in TRANSIT_CATEGORIES)
        assertTrue(PoiCategory.AEROPORTO !in TRANSIT_CATEGORIES)
    }
}
