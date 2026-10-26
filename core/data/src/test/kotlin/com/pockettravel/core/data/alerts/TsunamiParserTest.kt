package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class TsunamiParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/alerts/$name")) { "Fixture mancante: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun cap(status: String = "Actual", msgType: String = "Alert", info: String): String =
        """<alert xmlns="urn:oasis:names:tc:emergency:cap:1.2"><identifier>T-1</identifier><sent>2026-10-25T02:26:53-00:00</sent>""" +
            """<status>$status</status><msgType>$msgType</msgType>$info</alert>"""

    private val watchInfo = """<info><event>Tsunami Watch</event><severity>Moderate</severity>""" +
        """<parameter><valueName>EventLatLon</valueName><value>28.000,139.900 0.000</value></parameter></info>"""

    @Test
    fun warningAdvisoryAndWatchAreParsed() {
        val alerts = TsunamiParser.parse(fixture("tsunami_events.xml"))

        assertEquals(listOf("Tsunami Warning", "Tsunami Advisory", "Tsunami Watch"), alerts.map { it.title })
        assertEquals(listOf(AlertLevel.EXTREME, AlertLevel.MODERATE, AlertLevel.MODERATE), alerts.map { it.level })
        val warning = alerts.first()
        assertEquals("PAAQ-2-maiqdx-warning", warning.id)
        assertEquals(AlertSource.TSUNAMI, warning.source)
        assertEquals(AlertKind.TSUNAMI, warning.kind)
        assertEquals("Extreme", warning.sourceLevel)
        assertEquals("Tsunami Warning", warning.eventCode)
        assertEquals(Instant.parse("2012-09-18T00:59:00Z"), warning.startsAt)
        assertEquals(Instant.parse("2012-09-18T02:48:00Z"), warning.endsAt)
        assertEquals(Instant.parse("2012-09-18T00:59:00Z"), warning.issuedAt)
        assertEquals(AlertBounds(-117.266998, 32.776, -115.633, 32.866001), warning.area)
        assertEquals(
            "Coastal areas between and including the California-Mexico border to Ragged Point, California (45 miles NW of San Luis Obispo)",
            warning.areaName,
        )
        assertEquals("https://ntwc.arh.noaa.gov/events/PAAQ/2012/09/17/maiqdx/2/WEPA41/WEPA41.txt", warning.url)
        assertTrue(warning.details.orEmpty().startsWith("The tsunami Warning continues in effect"))
    }

    @Test
    fun informationStatementIsDropped() {
        assertTrue(TsunamiParser.parse(fixture("tsunami_empty.xml")).isEmpty())
    }

    @Test
    fun damagedMessageIsRejected() {
        val bodies = listOf(fixture("tsunami_truncated.xml"), "<html>502</html>", """<feed xmlns="http://www.w3.org/2005/Atom"></feed>""", "")
        bodies.forEach { body ->
            try {
                TsunamiParser.parse(body)
                fail("Attesa AlertParseException")
            } catch (_: AlertParseException) {
                // Atteso.
            }
        }
    }

    @Test
    fun categoryCanComeFromTheEventNameAndAreaFromTheEpicenter() {
        val alert = TsunamiParser.parse(cap(info = watchInfo)).single()

        assertEquals("T-1-watch", alert.id)
        assertEquals(AlertBounds(139.9, 28.0, 139.9, 28.0), alert.area)
        assertEquals(Instant.parse("2026-10-25T02:26:53Z"), alert.startsAt)
        assertNull(alert.endsAt)
        assertNull(alert.url)
    }

    @Test
    fun threatCounts() {
        val alert = TsunamiParser.parse(cap(info = "<info><event>Tsunami Threat</event></info>")).single()

        assertEquals("Tsunami Threat", alert.title)
        assertEquals(AlertLevel.HIGH, alert.level)
        assertNull(alert.area)
    }

    @Test
    fun cancelledAndNonActualMessagesAreDropped() {
        assertTrue(TsunamiParser.parse(cap(msgType = "Cancel", info = watchInfo)).isEmpty())
        assertTrue(TsunamiParser.parse(cap(status = "Test", info = watchInfo)).isEmpty())
        assertTrue(TsunamiParser.parse(cap(info = "<info><event>Tsunami Information</event></info>")).isEmpty())
    }

    @Test
    fun warningAreaAcrossTheAntimeridian() {
        val info = """<info><event>Tsunami Warning</event><area><circle>51.3,179.3 0.0</circle></area>""" +
            """<area><circle>51.8,-176.6 0.0</circle></area></info>"""

        assertEquals(AlertBounds(179.3, 51.3, -176.6, 51.8), TsunamiParser.parse(cap(info = info)).single().area)
    }
}
