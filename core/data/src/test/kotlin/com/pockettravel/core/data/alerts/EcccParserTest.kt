package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class EcccParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/alerts/$name")) { "Fixture mancante: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun assertRejected(parse: () -> Any) {
        try {
            parse()
            fail("Attesa AlertParseException")
        } catch (_: AlertParseException) {
            // Atteso.
        }
    }

    @Test
    fun alertsAreParsedAndEndedOnesSkipped() {
        val alerts = EcccParser.parse(fixture("eccc_events.json"))

        // La prima feature della risposta e' gia' terminata.
        assertEquals(7, alerts.size)
        val first = alerts.first()
        assertEquals("163551526102965285202610250504_fea1-1102", first.id)
        assertEquals(AlertSource.ECCC, first.source)
        assertEquals(AlertKind.WEATHER, first.kind)
        assertEquals(AlertLevel.MODERATE, first.level)
        assertEquals("yellow", first.sourceLevel)
        assertEquals("Wind warning", first.title)
        assertEquals("Wind warning", first.eventCode)
        assertEquals("Aupaluk", first.areaName)
        assertTrue(first.details.orEmpty().startsWith("What: High northwesterly winds"))
        assertEquals(Instant.parse("2026-10-26T15:00:00Z"), first.startsAt)
        assertEquals(Instant.parse("2026-10-27T10:00:00Z"), first.endsAt)
        assertEquals(Instant.parse("2026-10-26T08:46:21.545Z"), first.issuedAt)
        assertEquals("Blizzard warning", alerts.last().title)
    }

    @Test
    fun areaIsTheGeometryBounds() {
        val area = requireNotNull(EcccParser.parse(fixture("eccc_events.json")).first().area)

        assertTrue(area.minLon < area.maxLon)
        assertTrue(area.minLat < area.maxLat)
        assertTrue(area.contains(lat = (area.minLat + area.maxLat) / 2, lon = (area.minLon + area.maxLon) / 2))
    }

    @Test
    fun colourMapsToLevel() {
        fun level(colour: String): AlertLevel {
            val body = """{"features":[{"id":"a","properties":{"alert_name_en":"rainfall warning","risk_colour_en":"$colour"}}]}"""
            return EcccParser.parse(body).single().level
        }

        assertEquals(AlertLevel.MODERATE, level("yellow"))
        assertEquals(AlertLevel.HIGH, level("orange"))
        assertEquals(AlertLevel.EXTREME, level("red"))
        assertEquals(AlertLevel.LOW, level("none"))
    }

    @Test
    fun cancelledAndIncompleteFeaturesAreSkipped() {
        val body = """{"features":[
            {"id":"a","properties":{"alert_name_en":"fog advisory","status_en":"cancelled"}},
            {"properties":{"alert_name_en":"fog advisory"}},
            {"id":"c","properties":{"status_en":"new"}},
            {"id":"d","properties":{"alert_name_en":"fog advisory","status_en":"new"}}]}"""

        val alerts = EcccParser.parse(body)

        assertEquals(listOf("d"), alerts.map { it.id })
        assertNull(alerts.single().area)
        assertNull(alerts.single().endsAt)
    }

    @Test
    fun withoutAlertsIsEmpty() {
        assertTrue(EcccParser.parse(fixture("eccc_empty.json")).isEmpty())
    }

    @Test
    fun damagedResponseIsRejected() {
        assertRejected { EcccParser.parse(fixture("eccc_truncated.json")) }
        assertRejected { EcccParser.parse("<html>502 Bad Gateway</html>") }
        assertRejected { EcccParser.parse("[]") }
        assertRejected { EcccParser.parse("") }
    }
}
