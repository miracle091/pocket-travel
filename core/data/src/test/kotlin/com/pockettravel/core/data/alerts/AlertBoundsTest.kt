package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class AlertBoundsTest {
    // Italia centro-sud: lon 12..19, lat 37..42.
    private val italy = AlertBounds(minLon = 12.0, minLat = 37.0, maxLon = 19.0, maxLat = 42.0)

    // Fiji: attraversa l'antimeridiano, da 176 est a 178 ovest.
    private val fiji = AlertBounds(minLon = 176.0, minLat = -20.0, maxLon = -178.0, maxLat = -15.0)

    private fun alert(id: String, area: AlertBounds?) = Alert(
        id = id,
        source = AlertSource.GDACS,
        kind = AlertKind.EARTHQUAKE,
        level = AlertLevel.LOW,
        sourceLevel = null,
        title = id,
        details = null,
        startsAt = null,
        endsAt = null,
        area = area,
        areaName = null,
        url = null,
    )

    private fun point(lon: Double, lat: Double) = AlertBounds(lon, lat, lon, lat)

    @Test
    fun pointInsideAndOutside() {
        assertTrue(italy.contains(lat = 40.0, lon = 15.0))
        assertFalse(italy.contains(lat = 40.0, lon = 25.0))
        assertFalse(italy.contains(lat = 50.0, lon = 15.0))
    }

    @Test
    fun edgesAndCornersBelongToTheRectangle() {
        assertTrue(italy.contains(lat = 37.0, lon = 12.0))
        assertTrue(italy.contains(lat = 42.0, lon = 19.0))
        assertTrue(italy.contains(lat = 40.0, lon = 19.0))
        assertFalse(italy.contains(lat = 42.0001, lon = 19.0))
    }

    @Test
    fun areaIntersectsWhenItOverlapsOrContains() {
        assertTrue(italy.intersects(AlertBounds(18.0, 41.0, 25.0, 45.0)))
        assertTrue(italy.intersects(AlertBounds(0.0, 0.0, 40.0, 60.0)))
        assertTrue(italy.intersects(AlertBounds(19.0, 42.0, 20.0, 43.0)))
        assertFalse(italy.intersects(AlertBounds(20.0, 41.0, 25.0, 45.0)))
        assertFalse(italy.intersects(AlertBounds(13.0, 43.0, 14.0, 45.0)))
    }

    @Test
    fun rectangleAcrossTheAntimeridian() {
        assertTrue(fiji.contains(lat = -17.0, lon = 179.0))
        assertTrue(fiji.contains(lat = -17.0, lon = -179.5))
        assertTrue(fiji.contains(lat = -17.0, lon = 180.0))
        assertTrue(fiji.contains(lat = -17.0, lon = -180.0))
        assertFalse(fiji.contains(lat = -17.0, lon = 0.0))
        assertFalse(fiji.contains(lat = -17.0, lon = 170.0))
        assertFalse(fiji.contains(lat = -17.0, lon = -170.0))
    }

    @Test
    fun areaAcrossTheAntimeridianMeetsBothSides() {
        assertTrue(fiji.intersects(AlertBounds(177.0, -18.0, 179.0, -17.0)))
        assertTrue(fiji.intersects(AlertBounds(-179.0, -18.0, -175.0, -17.0)))
        assertTrue(fiji.intersects(AlertBounds(179.0, -18.0, -179.0, -17.0)))
        assertFalse(fiji.intersects(AlertBounds(-170.0, -18.0, -160.0, -17.0)))
        assertFalse(fiji.intersects(AlertBounds(100.0, -18.0, 170.0, -17.0)))
        assertFalse(fiji.intersects(AlertBounds(177.0, 10.0, 179.0, 20.0)))
    }

    @Test
    fun filterKeepsEventsInsideAndThoseWithoutCoordinates() {
        val alerts = listOf(
            alert("dentro", point(15.0, 40.0)),
            alert("fuori", point(121.5, -8.4)),
            alert("bordo", point(12.0, 37.0)),
            alert("area", AlertBounds(18.0, 41.0, 30.0, 50.0)),
            alert("senza-coordinate", null),
        )

        assertEquals(listOf("dentro", "bordo", "area", "senza-coordinate"), alerts.inside(italy).map { it.id })
    }

    @Test
    fun filterAcrossTheAntimeridian() {
        val alerts = listOf(
            alert("est", point(179.5, -17.0)),
            alert("ovest", point(-178.5, -16.0)),
            alert("lontano", point(-150.0, -16.0)),
        )

        assertEquals(listOf("est", "ovest"), alerts.inside(fiji).map { it.id })
    }

    @Test
    fun urlsCarryNoLocation() {
        val gdacs = AlertUrls.gdacs(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 26))

        assertEquals(
            "https://www.gdacs.org/gdacsapi/api/events/geteventlist/SEARCH?eventlist=EQ%3BTC%3BFL%3BVO%3BWF%3BDR" +
                "&fromdate=2026-10-01&todate=2026-10-26&alertlevel=Orange%3BRed",
            gdacs,
        )
        assertEquals("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson", AlertUrls.usgs())
        assertEquals("https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-italy", AlertUrls.meteoalarm("italy"))
        assertEquals(
            "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-bosnia-herzegovina",
            AlertUrls.meteoalarm("bosnia-herzegovina"),
        )
    }

    @Test
    fun meteoalarmRejectsInvalidCountry() {
        for (slug in listOf("", "Italy", "../x", "italy?x=1", "italy-", "ital y")) {
            try {
                AlertUrls.meteoalarm(slug)
                fail("Paese accettato: $slug")
            } catch (_: IllegalArgumentException) {
                // Atteso.
            }
        }
    }
}
