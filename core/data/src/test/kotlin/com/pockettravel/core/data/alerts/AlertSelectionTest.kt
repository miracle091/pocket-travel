package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

// Regole di scelta delle allerte di una regione (scelte dell'utente del 2026-10-26).
class AlertSelectionTest {

    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val italy = AlertBounds(6.6, 35.5, 18.5, 47.1)
    private val inItaly = AlertBounds(13.0, 42.0, 13.0, 42.0)

    private fun quake(
        id: String,
        magnitude: Double,
        hoursAgo: Long = 2,
        felt: Int? = null,
        pager: String? = null,
        source: AlertSource = AlertSource.USGS,
        sourceLevel: String? = null,
    ) = Alert(
        id = id, source = source, kind = AlertKind.EARTHQUAKE, level = AlertLevel.MODERATE, sourceLevel = sourceLevel,
        title = id, details = null, startsAt = now - Duration.ofHours(hoursAgo), endsAt = null, area = inItaly,
        areaName = null, url = null, magnitude = magnitude, feltReports = felt, impactAlert = pager,
    )

    private fun event(id: String, kind: AlertKind, level: AlertLevel, area: AlertBounds? = inItaly, endsAt: Instant? = null) = Alert(
        id = id, source = AlertSource.GDACS, kind = kind, level = level, sourceLevel = null, title = id, details = null,
        startsAt = now - Duration.ofDays(1), endsAt = endsAt, area = area, areaName = null, url = null,
    )

    private fun ids(alerts: List<Alert>, country: String = "it", bounds: AlertBounds? = italy) =
        selectAlerts(alerts, bounds, country, now).map { it.id }

    @Test
    fun quakeThresholdDependsOnTheCountry() {
        val alerts = listOf(quake("m44", 4.4), quake("m47", 4.7), quake("m52", 5.2))
        assertEquals(listOf("m47", "m52").sorted(), ids(alerts).sorted())
        // Giappone: nazione molto sismica, soglia 5,0.
        assertEquals(listOf("m52"), ids(alerts, country = "JP"))
    }

    @Test
    fun smallQuakesStayWhenFeltOrWithPager() {
        val alerts = listOf(quake("sentito", 3.1, felt = 250), quake("pager", 4.0, pager = "yellow"), quake("poco", 3.1, felt = 20))
        assertEquals(listOf("pager", "sentito").sorted(), ids(alerts).sorted())
    }

    @Test
    fun quakesOlderThanThreeDaysAreDropped() {
        assertEquals(listOf("recente"), ids(listOf(quake("recente", 5.0, hoursAgo = 70), quake("vecchio", 6.0, hoursAgo = 80))))
    }

    @Test
    fun greenGdacsQuakesAndDuplicatesAreDropped() {
        val alerts = listOf(
            quake("gdacs-verde", 5.0, source = AlertSource.GDACS, sourceLevel = "Green", hoursAgo = 30),
            quake("gdacs-arancione", 6.0, source = AlertSource.GDACS, sourceLevel = "Orange", hoursAgo = 2),
            quake("usgs", 6.0, hoursAgo = 2),
        )
        // Lo stesso terremoto in GDACS e in USGS resta una volta sola, da USGS.
        assertEquals(listOf("usgs"), ids(alerts))
    }

    @Test
    fun onlyAlertsInsideTheRegionAndStillValid() {
        val alerts = listOf(
            event("dentro", AlertKind.FLOOD, AlertLevel.LOW),
            event("fuori", AlertKind.FLOOD, AlertLevel.LOW, area = AlertBounds(30.0, 10.0, 31.0, 11.0)),
            event("finita", AlertKind.CYCLONE, AlertLevel.HIGH, endsAt = now - Duration.ofHours(1)),
            event("senza-area", AlertKind.WEATHER, AlertLevel.MODERATE, area = null),
        )
        assertEquals(listOf("senza-area", "dentro"), ids(alerts))
        // Senza mappa installata il rettangolo non si conosce: restano solo le allerte senza coordinate (Meteoalarm).
        assertEquals(listOf("senza-area"), ids(alerts, bounds = null))
    }

    @Test
    fun mostSevereFirst() {
        val alerts = listOf(event("bassa", AlertKind.FLOOD, AlertLevel.LOW), event("estrema", AlertKind.CYCLONE, AlertLevel.EXTREME))
        assertEquals(listOf("estrema", "bassa"), ids(alerts))
    }

    @Test
    fun meteoalarmCountries() {
        assertEquals("italy", meteoalarmCountry("it"))
        assertEquals("republic-of-north-macedonia", meteoalarmCountry("MK"))
        assertNull(meteoalarmCountry("jp"))
        assertNull(meteoalarmCountry(null))
    }

    @Test
    fun inJapanGdacsWeatherEventsGiveWayToJma() {
        val cyclone = event("cyclone", AlertKind.CYCLONE, AlertLevel.HIGH, area = null)
        val flood = event("flood", AlertKind.FLOOD, AlertLevel.HIGH, area = null)
        val volcano = event("volcano", AlertKind.VOLCANO, AlertLevel.HIGH, area = null)
        val jma = event("jma", AlertKind.WEATHER, AlertLevel.HIGH, area = null).copy(source = AlertSource.JMA)

        assertEquals(setOf("volcano", "jma"), ids(listOf(cyclone, flood, volcano, jma), country = "jp", bounds = null).toSet())
        assertEquals(4, ids(listOf(cyclone, flood, volcano, jma), country = "ph", bounds = null).size)
    }
}
