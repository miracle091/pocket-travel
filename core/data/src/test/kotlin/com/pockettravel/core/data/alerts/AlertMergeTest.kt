package com.pockettravel.core.data.alerts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AlertMergeTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/alerts/$name")).readText()

    @Test
    fun `le zone ECCC con lo stesso testo diventano un'allerta`() {
        val merged = EcccParser.parse(fixture("eccc_events.json")).mergeZones()

        assertEquals(listOf("Wind warning", "Blizzard warning"), merged.map { it.title })
        assertEquals("Aupaluk, Kangiqsujuaq - Raglan Lake, Kangirsuk, Quaqtaq, Tasiujaq", merged.first().areaName)
        assertEquals("Ivujivik, Salluit", merged.last().areaName)
    }

    @Test
    fun `i tsunami di NWS lasciano il posto a quelli dei centri tsunami`() {
        val nws = alert("nws-1", AlertSource.NWS, AlertKind.TSUNAMI)
        val center = alert("ptwc-1", AlertSource.TSUNAMI, AlertKind.TSUNAMI)
        val storm = alert("nws-2", AlertSource.NWS, AlertKind.WEATHER)

        assertEquals(listOf("ptwc-1", "nws-2"), listOf(nws, center, storm).mergeZones().map { it.id })
        assertEquals(listOf("nws-1"), listOf(nws).mergeZones().map { it.id })
    }

    @Test
    fun `l'allerta unita copre tutte le zone e dura quanto la piu' lunga`() {
        val west = alert("a", AlertSource.NWS, AlertKind.WEATHER).copy(
            area = AlertBounds(-106.0, 37.0, -105.0, 38.0),
            endsAt = Instant.parse("2026-10-27T06:00:00Z"),
        )
        val east = west.copy(id = "b", area = AlertBounds(-104.0, 36.0, -103.0, 37.5), endsAt = Instant.parse("2026-10-27T12:00:00Z"))

        val merged = listOf(west, east).mergeZones().single()

        assertEquals(AlertBounds(-106.0, 36.0, -103.0, 38.0), merged.area)
        assertEquals(Instant.parse("2026-10-27T12:00:00Z"), merged.endsAt)
    }

    private fun alert(id: String, source: AlertSource, kind: AlertKind) = Alert(
        id = id, source = source, kind = kind, level = AlertLevel.HIGH, sourceLevel = null, title = "Event $kind",
        details = "NWS Honolulu HI", startsAt = null, endsAt = null, area = null, areaName = null, url = null,
    )
}
