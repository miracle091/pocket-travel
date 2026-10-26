package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Nomi presi dalle risposte vere del 2026-10-26 e dall'elenco dei tipi di api.weather.gov.
class NationalEventWordingTest {
    @Test
    fun `nomi NWS`() {
        assertEquals(NationalEvent(NationalHazard.WINTER_STORM, NationalEventType.WARNING), nationalEvent("Winter Storm Warning"))
        assertEquals(NationalEvent(NationalHazard.FLASH_FLOOD, NationalEventType.WATCH), nationalEvent("Flash Flood Watch"))
        assertEquals(NationalEvent(NationalHazard.RIP_CURRENT, NationalEventType.STATEMENT), nationalEvent("Rip Current Statement"))
        assertEquals(NationalEvent(NationalHazard.FIRE_DANGER, NationalEventType.WARNING), nationalEvent("Red Flag Warning"))
        assertEquals(NationalEvent(NationalHazard.HIGH_WIND, NationalEventType.WATCH), nationalEvent("High Wind Watch"))
    }

    @Test
    fun `nomi ECCC, JMA e dei centri tsunami`() {
        assertEquals(NationalEvent(NationalHazard.HEAVY_RAIN, NationalEventType.WARNING), nationalEvent("Rainfall warning"))
        assertEquals(NationalEvent(NationalHazard.SPECIAL_WEATHER, NationalEventType.STATEMENT), nationalEvent("Special weather statement"))
        assertEquals(
            NationalEvent(NationalHazard.HEAVY_RAIN, NationalEventType.EMERGENCY_WARNING),
            nationalEvent("Heavy rain emergency warning"),
        )
        assertEquals(NationalEvent(NationalHazard.LANDSLIDE, NationalEventType.URGENT_WARNING), nationalEvent("Landslide urgent warning"))
        assertEquals(NationalEvent(NationalHazard.TSUNAMI, NationalEventType.THREAT), nationalEvent("Tsunami Threat"))
    }

    @Test
    fun `nomi sconosciuti restano originali`() {
        assertNull(nationalEvent("Child Abduction Emergency"))
        assertNull(nationalEvent("Nuclear Power Plant Warning"))
        assertNull(nationalEvent("Warning"))
    }
}
