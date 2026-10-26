package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Testi presi dalle risposte vere delle fonti del 2026-10-26.
class AlertWordingTest {
    @Test
    fun `titolo di Meteoalarm in colore e tipo`() {
        assertEquals(WeatherWarning(WarningColor.YELLOW, WeatherHazard.WIND), weatherWarning("Yellow Wind Warning issued for Italy - Basilicata"))
        assertEquals(WeatherWarning(WarningColor.ORANGE, WeatherHazard.COASTAL_EVENT), weatherWarning("Orange Coastalevent Warning issued for France - Var"))
        assertEquals(WeatherWarning(WarningColor.YELLOW, WeatherHazard.SNOW_ICE), weatherWarning("Yellow Snow-ice Warning issued for Norway - Finnmark"))
        assertEquals(WeatherWarning(WarningColor.RED, WeatherHazard.RAIN_FLOOD), weatherWarning("Red Rain-flood Warning issued for Spain - Valencia"))
    }

    @Test
    fun `titolo di Meteoalarm fuori schema resta originale`() {
        assertNull(weatherWarning("Yellow Tsunami Warning issued for Italy - Calabria"))
        assertNull(weatherWarning("wind gusts"))
    }

    @Test
    fun `nome di ciclone e vulcano GDACS`() {
        assertEquals("ARNAB-26", gdacsEventName(AlertKind.CYCLONE, "Tropical Cyclone ARNAB-26"))
        assertEquals("Taal", gdacsEventName(AlertKind.VOLCANO, "Eruption  Taal"))
        assertNull(gdacsEventName(AlertKind.EARTHQUAKE, "Earthquake in China"))
    }

    @Test
    fun `dettagli GDACS`() {
        assertEquals(GdacsDetail.Quake(7.7, 12.647), gdacsDetail("Magnitude 7.7M, Depth:12.647km"))
        assertEquals(GdacsDetail.Cyclone(CycloneCategory.STORM, 74), gdacsDetail("Tropical Storm (maximum wind speed of 74 km/h)"))
        assertEquals(
            GdacsDetail.Cyclone(CycloneCategory.HURRICANE, 204),
            gdacsDetail("Hurricane/Typhoon > 74 mph (maximum wind speed of 204 km/h)"),
        )
        assertEquals(GdacsDetail.Area(18310, hectares = true), gdacsDetail("Orange impact for forestfire in 18310 ha"))
        assertEquals(GdacsDetail.Area(1587809, hectares = false), gdacsDetail("Medium impact for agricultural drought in 1587809 km2"))
        assertEquals(GdacsDetail.Empty, gdacsDetail("Magnitude 0  "))
        assertNull(gdacsDetail("Population affected: 12000"))
    }

    @Test
    fun `luogo di un terremoto USGS`() {
        assertEquals(QuakePlace(52, 13, "Malfa, Italy"), quakePlace("52 km WNW of Malfa, Italy"))
        assertEquals(QuakePlace(8, 0, "Adak, Alaska"), quakePlace("8 km N of Adak, Alaska"))
        assertNull(quakePlace("Reykjanes Ridge"))
        assertNull(quakePlace("south of the Fiji Islands"))
    }

    @Test
    fun `nazioni scritte dalle fonti`() {
        assertEquals("IT", countryCode("Italy"))
        assertEquals("VN", countryCode("Viet Nam"))
        assertEquals("BA", countryCode("Bosnia  and  Herzegovina"))
        assertEquals("CD", countryCode("Democratic Republic of Congo"))
        assertNull(countryCode("Southern India"))
        assertEquals(listOf("Japan", "China"), gdacsCountries("Japan, China, "))
    }
}
