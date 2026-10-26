package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class JmaParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/alerts/$name")) { "Fixture mancante: $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun assertRejected(body: String) {
        try {
            JmaParser.parse(body)
            fail("Attesa AlertParseException")
        } catch (_: AlertParseException) {
            // Atteso.
        }
    }

    @Test
    fun warningsAreGroupedByOfficeAndHazard() {
        val alerts = JmaParser.parse(fixture("jma_warnings.json"))

        assertEquals(
            listOf("jma-014100-49", "jma-016000-03", "jma-016000-02", "jma-130000-33", "jma-130000-43", "jma-471000-05"),
            alerts.map { it.id },
        )
        val landslide = alerts.first()
        assertEquals(AlertSource.JMA, landslide.source)
        assertEquals(AlertKind.WEATHER, landslide.kind)
        assertEquals(AlertLevel.HIGH, landslide.level)
        assertEquals("Urgent Warning", landslide.sourceLevel)
        assertEquals("URGENT:49", landslide.eventCode)
        assertEquals(Instant.parse("2026-05-27T06:19:00Z"), landslide.issuedAt)
        assertNull(landslide.startsAt)
        assertNull(landslide.endsAt)
        assertNull(landslide.area)
        assertEquals("https://www.jma.go.jp/bosai/warning/#area_type=offices&lang=en&area_code=014100", landslide.url)
    }

    @Test
    fun namesAreEnglishAndNeverJapanese() {
        val alerts = JmaParser.parse(fixture("jma_warnings.json"))

        val rain = alerts.first { it.id == "jma-016000-03" }
        assertEquals("Heavy rain warning", rain.title)
        assertEquals("Ishikari Sorachi Shiribeshi", rain.areaName)
        assertEquals("Ishikari Region, Sorachi Region", rain.details)
        assertEquals("WARNING:03", rain.eventCode)
        val emergency = alerts.first { it.id == "jma-130000-33" }
        assertEquals("Heavy rain emergency warning", emergency.title)
        assertEquals(AlertLevel.EXTREME, emergency.level)
        assertEquals("Tokyo", emergency.areaName)
        assertEquals("Tokyo Region", emergency.details)
        assertEquals("Kushiro Nemuro", alerts.first().areaName)
        assertEquals("Kushiro Region", alerts.first().details)
        val texts = alerts.flatMap { listOfNotNull(it.title, it.details, it.areaName, it.sourceLevel) }
        assertTrue(texts.all { text -> text.all { it.code < ASCII_END } })
    }

    @Test
    fun alertCoveringTheWholeOfficeHasNoDetails() {
        val storm = JmaParser.parse(fixture("jma_warnings.json")).first { it.id == "jma-471000-05" }

        assertEquals("Storm warning", storm.title)
        assertEquals("Okinawa Main Island", storm.areaName)
        assertNull(storm.details)
    }

    @Test
    fun advisoriesAndCancelledWarningsAreSkipped() {
        val ids = JmaParser.parse(fixture("jma_warnings.json")).map { it.id }

        // La fonte ha segnalazioni (14, 20...) in quasi ogni ufficio e una allerta 03 annullata a Daito.
        assertTrue(ids.none { it.startsWith("jma-472000") || it.startsWith("jma-460040") || it.endsWith("-20") })
    }

    @Test
    fun noWarningsGivesEmptyList() {
        assertTrue(JmaParser.parse(fixture("jma_empty.json")).isEmpty())
        assertTrue(JmaParser.parse("[]").isEmpty())
    }

    @Test
    fun damagedResponseIsRejected() {
        assertRejected(fixture("jma_truncated.json"))
        assertRejected("<html>502 Bad Gateway</html>")
        assertRejected("""{"reportDatetime":"2026-05-28T10:16:00+09:00"}""")
        assertRejected("")
    }

    @Test
    fun everyHazardHasAUniqueCodeAndEventCode() {
        assertEquals(JmaHazard.entries.size, JmaHazard.entries.map { it.code }.toSet().size)
        assertEquals(JmaHazard.entries.size, JmaHazard.entries.map { it.eventCode }.toSet().size)
        assertEquals(JmaHazard.WARNING_HEAVY_RAIN, JmaHazard.fromCode("03"))
        assertNull(JmaHazard.fromCode("40"))
    }

    private companion object {
        const val ASCII_END = 128
    }
}
