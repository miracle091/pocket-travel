package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class NwsParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/alerts/$name")) { "Fixture mancante: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun feature(event: String, severity: String, status: String = "Actual", type: String = "Alert"): String =
        """{"id":"x-$event","properties":{"id":"urn:$event","event":"$event","severity":"$severity","status":"$status","messageType":"$type"}}"""

    private fun parse(vararg features: String): List<Alert> = NwsParser.parse("""{"features":[${features.joinToString(",")}]}""")

    @Test
    fun eventsAreParsedAndMinorOnesDropped() {
        val alerts = NwsParser.parse(fixture("nws_events.json"))

        // Small Craft Advisory (Minor), Hydrologic Outlook (Unknown) e Hurricane Force Wind Warning (solo per il mare) non
        // passano.
        assertEquals(
            listOf("Winter Storm Warning", "Flood Warning", "Rip Current Statement", "Winter Weather Advisory"),
            alerts.map { it.title },
        )
        val storm = alerts.first()
        assertEquals("urn:oid:2.49.0.1.840.0.684d8efbc1a1d6ec52056d6380f718012971e163.004.1", storm.id)
        assertEquals(AlertSource.NWS, storm.source)
        assertEquals(AlertKind.WEATHER, storm.kind)
        assertEquals(AlertLevel.HIGH, storm.level)
        assertEquals("Severe", storm.sourceLevel)
        assertEquals("Winter Storm Warning", storm.eventCode)
        assertEquals("NWS Fairbanks AK", storm.details)
        assertEquals("Upper Koyukuk Valley", storm.areaName)
        assertEquals(Instant.parse("2026-10-25T19:51:00Z"), storm.startsAt)
        assertEquals(Instant.parse("2026-10-27T06:00:00Z"), storm.endsAt)
        assertEquals(Instant.parse("2026-10-25T19:51:00Z"), storm.issuedAt)
        assertNull(storm.area)
        assertNull(storm.url)
        assertEquals(
            listOf(AlertLevel.HIGH, AlertLevel.HIGH, AlertLevel.MODERATE, AlertLevel.MODERATE),
            alerts.map { it.level },
        )
    }

    @Test
    fun polygonGivesTheArea() {
        val flood = NwsParser.parse(fixture("nws_events.json")).first { it.title == "Flood Warning" }

        assertEquals(AlertKind.FLOOD, flood.kind)
        assertEquals(AlertBounds(-106.01, 37.42, -105.82, 37.59), flood.area)
    }

    @Test
    fun withoutEventsIsEmpty() {
        assertTrue(NwsParser.parse(fixture("nws_empty.json")).isEmpty())
    }

    @Test
    fun damagedResponseIsRejected() {
        listOf(fixture("nws_truncated.json"), "<html>503</html>", """{"title":"Bad request"}""", "[]").forEach { body ->
            try {
                NwsParser.parse(body)
                fail("Attesa AlertParseException")
            } catch (_: AlertParseException) {
                // Atteso.
            }
        }
    }

    @Test
    fun onlyActualAlertsAndUpdatesAreKept() {
        val alerts = parse(
            feature("Flood Warning", "Severe"),
            feature("Flood Warning", "Severe", status = "Test"),
            feature("Flood Warning", "Severe", type = "Cancel"),
            feature("Flood Warning", "Severe", type = "Update"),
        )

        assertEquals(2, alerts.size)
    }

    @Test
    fun minorIsKeptOnlyForTsunamiAndVolcano() {
        val alerts = parse(feature("Tsunami Advisory", "Minor"), feature("Volcano Warning", "Minor"), feature("Frost Advisory", "Minor"))

        assertEquals(listOf("Tsunami Advisory", "Volcano Warning"), alerts.map { it.title })
        assertEquals(listOf(AlertLevel.LOW, AlertLevel.LOW), alerts.map { it.level })
    }

    @Test
    fun kindFollowsTheEventName() {
        val events = listOf(
            "Tsunami Warning" to AlertKind.TSUNAMI,
            "Ashfall Warning" to AlertKind.VOLCANO,
            "Earthquake Warning" to AlertKind.EARTHQUAKE,
            "Red Flag Warning" to AlertKind.FIRE,
            "Flash Flood Warning" to AlertKind.FLOOD,
            "Storm Surge Warning" to AlertKind.FLOOD,
            "Hurricane Warning" to AlertKind.CYCLONE,
            "Typhoon Watch" to AlertKind.CYCLONE,
            "High Wind Warning" to AlertKind.WEATHER,
            "Tornado Warning" to AlertKind.WEATHER,
        )

        val alerts = parse(*events.map { feature(it.first, "Severe") }.toTypedArray())

        assertEquals(events.map { it.second }, alerts.map { it.kind })
    }

    @Test
    fun longAreaDescriptionIsShortened() {
        val area = (1..30).joinToString("; ") { "County $it" }
        val body = """{"features":[{"properties":{"id":"a","event":"Flood Warning","severity":"Severe","status":"Actual",""" +
            """"messageType":"Alert","areaDesc":"$area"}}]}"""

        val name = NwsParser.parse(body).single().areaName.orEmpty()

        assertEquals("County 1; County 2; County 3; County 4; County 5; County 6; County 7; County 8; County 9...", name)
    }

    @Test
    fun multiPolygonAcrossTheAntimeridian() {
        val body = """{"features":[{"geometry":{"type":"MultiPolygon","coordinates":""" +
            """[[[[179.5,51.0],[179.9,52.0],[179.5,52.0],[179.5,51.0]]],[[[-179.8,51.5],[-179.2,51.9],[-179.8,51.9],[-179.8,51.5]]]]},""" +
            """"properties":{"id":"a","event":"Coastal Flood Warning","severity":"Moderate","status":"Actual","messageType":"Alert"}}]}"""

        assertEquals(AlertBounds(179.5, 51.0, -179.2, 52.0), NwsParser.parse(body).single().area)
    }

    @Test
    fun areaCodesForRegionsAndTerritories() {
        assertEquals("TX", nwsArea("stati-uniti-texas", "us"))
        assertEquals("NC", nwsArea("stati-uniti-carolina-del-nord", null))
        assertEquals("WV", nwsArea("stati-uniti-virginia-occidentale", "us"))
        assertEquals("VA", nwsArea("stati-uniti-virginia", "us"))
        assertEquals("DC", nwsArea("stati-uniti-distretto-di-columbia", "us"))
        assertEquals("PR", nwsArea("portorico", "pr"))
        assertEquals("GU", nwsArea("guam", null))
        assertEquals("VI", nwsArea("isole-vergini-americane", "vi"))
        assertEquals("AS", nwsArea("altra-regione", "AS"))
        assertEquals("MP", nwsArea("altra-regione", "mp"))
        assertNull(nwsArea("italia-lazio", "it"))
        assertNull(nwsArea("canada-ontario", null))
        assertNull(nwsArea("stati-uniti-sconosciuto", "us"))
    }
}
