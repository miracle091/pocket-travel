package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeoNetParserTest {
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

    private fun volcano(level: Int) =
        """{"features":[{"geometry":{"type":"Point","coordinates":[175.5,-39.1]},""" +
            """"properties":{"level":$level,"volcanoID":"v","volcanoTitle":"V"}}]}"""

    @Test
    fun onlyVolcanoesFromLevelTwoAreShown() {
        val alerts = GeoNetParser.parse(fixture("geonet_val.json"))

        // Nella risposta ci sono 11 vulcani: solo White Island e' sopra il livello 1.
        val alert = alerts.single()
        assertEquals("VAL-whiteisland", alert.id)
        assertEquals(AlertSource.GEONET, alert.source)
        assertEquals(AlertKind.VOLCANO, alert.kind)
        assertEquals(AlertLevel.MODERATE, alert.level)
        assertEquals("Orange", alert.sourceLevel)
        assertEquals("White Island", alert.title)
        assertEquals("VAL3", alert.eventCode)
        assertEquals("Minor volcanic eruption.", alert.details)
        assertEquals(AlertBounds(177.183, -37.521, 177.183, -37.521), alert.area)
        assertNull(alert.endsAt)
    }

    @Test
    fun levelMapsToAlertLevel() {
        assertTrue(GeoNetParser.parse(volcano(1)).isEmpty())
        assertEquals(AlertLevel.LOW, GeoNetParser.parse(volcano(2)).single().level)
        assertEquals(AlertLevel.HIGH, GeoNetParser.parse(volcano(4)).single().level)
        assertEquals(AlertLevel.EXTREME, GeoNetParser.parse(volcano(5)).single().level)
    }

    @Test
    fun withoutVolcanoesIsEmpty() {
        assertTrue(GeoNetParser.parse("""{"type":"FeatureCollection","features":[]}""").isEmpty())
    }

    @Test
    fun damagedResponseIsRejected() {
        assertRejected { GeoNetParser.parse(fixture("geonet_truncated.json")) }
        assertRejected { GeoNetParser.parse("<html>Not found</html>") }
        assertRejected { GeoNetParser.parse("""{"message":"error"}""") }
    }
}
