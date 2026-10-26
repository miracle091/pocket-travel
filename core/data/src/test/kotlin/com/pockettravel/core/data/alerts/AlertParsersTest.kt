package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class AlertParsersTest {
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
    fun gdacsEventsAreParsed() {
        val alerts = JsonAlertParsers.parseGdacs(fixture("gdacs_events.json"))

        assertEquals(listOf("EQ-1573911", "TC-1001344", "WF-1033211"), alerts.map { it.id })
        val quake = alerts.first()
        assertEquals(AlertSource.GDACS, quake.source)
        assertEquals(AlertKind.EARTHQUAKE, quake.kind)
        assertEquals(AlertLevel.LOW, quake.level)
        assertEquals("Green", quake.sourceLevel)
        assertEquals("Earthquake in Indonesia", quake.title)
        assertEquals("Magnitude 5M, Depth:10km", quake.details)
        assertEquals(Instant.parse("2026-10-26T04:53:40Z"), quake.startsAt)
        assertNull(quake.endsAt)
        assertEquals(AlertBounds(121.5729, -8.443, 121.5729, -8.443), quake.area)
        assertEquals("Indonesia", quake.areaName)
        assertEquals("https://www.gdacs.org/report.aspx?eventid=1573911&episodeid=1742739&eventtype=EQ", quake.url)
        assertEquals(listOf(AlertKind.EARTHQUAKE, AlertKind.CYCLONE, AlertKind.FIRE), alerts.map { it.kind })
    }

    @Test
    fun gdacsWithoutEventsIsEmpty() {
        assertTrue(JsonAlertParsers.parseGdacs(fixture("gdacs_empty.json")).isEmpty())
    }

    @Test
    fun gdacsDamagedResponseIsRejected() {
        assertRejected { JsonAlertParsers.parseGdacs(fixture("gdacs_truncated.json")) }
        assertRejected { JsonAlertParsers.parseGdacs("<html>502 Bad Gateway</html>") }
        assertRejected { JsonAlertParsers.parseGdacs("""{"message":"Please specify only 1 eventtype."}""") }
        assertRejected { JsonAlertParsers.parseGdacs("[]") }
    }

    @Test
    fun gdacsSkipsEventsWithoutIdOrTitle() {
        val body = """{"features":[{"properties":{"eventtype":"EQ"}},{"properties":{"eventtype":"FL","eventid":7,"name":"Flood"}}]}"""

        val alerts = JsonAlertParsers.parseGdacs(body)

        assertEquals(listOf("FL-7"), alerts.map { it.id })
        assertNull(alerts.single().area)
    }

    @Test
    fun gdacsEndedEventKeepsItsEnd() {
        val body = """{"features":[{"properties":{"eventtype":"FL","eventid":7,"name":"Flood","iscurrent":"false",""" +
            """"todate":"2026-10-02T12:00:00","alertlevel":"Red"}}]}"""

        val alert = JsonAlertParsers.parseGdacs(body).single()

        assertEquals(Instant.parse("2026-10-02T12:00:00Z"), alert.endsAt)
        assertEquals(AlertLevel.HIGH, alert.level)
        assertFalse(alert.isActiveAt(Instant.parse("2026-10-03T00:00:00Z")))
    }

    @Test
    fun usgsEventsAreParsed() {
        val alerts = JsonAlertParsers.parseUsgs(fixture("usgs_events.json"))

        assertEquals(3, alerts.size)
        val first = alerts.first()
        assertEquals("us6000u4dx", first.id)
        assertEquals(AlertSource.USGS, first.source)
        assertEquals(AlertKind.EARTHQUAKE, first.kind)
        assertEquals(AlertLevel.LOW, first.level)
        assertEquals("M 4.7", first.sourceLevel)
        assertEquals("M 4.7 - 52 km WNW of Malfa, Italy", first.title)
        assertEquals("52 km WNW of Malfa, Italy", first.details)
        assertEquals(Instant.ofEpochMilli(1792769533051), first.startsAt)
        assertEquals(AlertBounds(14.3074, 38.8141, 14.3074, 38.8141), first.area)
        assertEquals("https://earthquake.usgs.gov/earthquakes/eventpage/us6000u4dx", first.url)
        assertEquals(AlertLevel.MODERATE, alerts[1].level)
    }

    @Test
    fun usgsWithoutEventsIsEmpty() {
        assertTrue(JsonAlertParsers.parseUsgs(fixture("usgs_empty.json")).isEmpty())
    }

    @Test
    fun usgsDamagedResponseIsRejected() {
        assertRejected { JsonAlertParsers.parseUsgs(fixture("usgs_truncated.json")) }
        assertRejected { JsonAlertParsers.parseUsgs("") }
    }

    @Test
    fun meteoalarmEntriesAreParsed() {
        val alerts = MeteoalarmParser.parse(fixture("meteoalarm_events.xml"))

        assertEquals(2, alerts.size)
        val first = alerts.first()
        assertEquals("2.49.0.0.380.3.IT.261025103222.015", first.id)
        assertEquals(AlertSource.METEOALARM, first.source)
        assertEquals(AlertKind.WEATHER, first.kind)
        assertEquals(AlertLevel.MODERATE, first.level)
        assertEquals("Moderate", first.sourceLevel)
        assertEquals("Yellow Wind Warning issued for Italy - Basilicata", first.title)
        assertEquals("Basilicata", first.areaName)
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), first.startsAt)
        assertEquals(Instant.parse("2026-10-27T22:59:00Z"), first.endsAt)
        assertNull(first.area)
        assertEquals("https://meteoalarm.org?region=IT", first.url)
        assertEquals("Puglia", alerts[1].areaName)
    }

    @Test
    fun meteoalarmEntryExpires() {
        val alert = MeteoalarmParser.parse(fixture("meteoalarm_events.xml")).first()

        assertTrue(alert.isActiveAt(Instant.parse("2026-10-27T12:00:00Z")))
        assertFalse(alert.isActiveAt(Instant.parse("2026-10-28T00:00:00Z")))
    }

    @Test
    fun meteoalarmWithoutEntriesIsEmpty() {
        assertTrue(MeteoalarmParser.parse(fixture("meteoalarm_empty.xml")).isEmpty())
    }

    @Test
    fun meteoalarmDamagedResponseIsRejected() {
        assertRejected { MeteoalarmParser.parse(fixture("meteoalarm_truncated.xml")) }
        assertRejected { MeteoalarmParser.parse("<html><body>Not found</body></html>") }
        assertRejected { MeteoalarmParser.parse("""{"features":[]}""") }
    }

    @Test
    fun meteoalarmSkipsTestAndCancelledEntries() {
        val xml = fixture("meteoalarm_events.xml")
            .replaceFirst("<cap:status>Actual</cap:status>", "<cap:status>Test</cap:status>")
        val cancelled = xml.replace("<cap:message_type>Update</cap:message_type>", "<cap:message_type>Cancel</cap:message_type>")

        assertEquals(listOf("Puglia"), MeteoalarmParser.parse(xml).map { it.areaName })
        assertTrue(MeteoalarmParser.parse(cancelled).isEmpty())
    }
}
