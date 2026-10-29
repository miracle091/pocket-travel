package com.pockettravel.feature.ai

import com.pockettravel.core.data.alerts.Alert
import com.pockettravel.core.data.alerts.AlertKind
import com.pockettravel.core.data.alerts.AlertLevel
import com.pockettravel.core.data.alerts.AlertSource
import com.pockettravel.core.data.alerts.RegionAlerts
import com.pockettravel.core.ui.AlertText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

// Allerte nel contesto dell'assistente: testo del blocco e citazione, in italiano e in inglese.
class AlertsSectionsTest {

    private val now = Instant.parse("2026-10-29T10:00:00Z")
    private val zone = ZoneOffset.UTC

    private fun alert(id: String, source: AlertSource, level: AlertLevel, title: String, endsAt: Instant? = null) = Alert(
        id = id, source = source, kind = AlertKind.WEATHER, level = level, sourceLevel = null, title = title, details = null,
        startsAt = null, endsAt = endsAt, area = null, areaName = null, url = null,
    )

    private val wind = alert(
        "m1", AlertSource.METEOALARM, AlertLevel.MODERATE, "Yellow Wind Warning issued for Italy - Puglia",
        endsAt = Instant.parse("2026-10-29T22:00:00Z"),
    )
    private val quake = alert("u1", AlertSource.USGS, AlertLevel.HIGH, "M 4.8 - 10 km S of Foggia, Italy")

    // Il titolo scritto dall'app: qui una versione italiana fissa, nell'app alertText di core/ui.
    private val italian = { alert: Alert ->
        when (alert.id) {
            "m1" -> AlertText("Allerta gialla per vento", listOf("Puglia"))
            else -> AlertText("Terremoto di magnitudo 4,8", listOf("10 km a S di Foggia"))
        }
    }
    private val original = { alert: Alert -> AlertText(alert.title, emptyList()) }

    private fun regionAlerts(vararg alerts: Alert, fresh: Boolean = true, updated: Instant = Instant.parse("2026-10-29T09:15:00Z")) =
        RegionAlerts(alerts.toList(), updated.toEpochMilli(), fresh)

    @Test
    fun `blocco italiano con fonti, ora, livello, fine e frase finale`() {
        val text = alertsContext(regionAlerts(quake, wind), italian, "Informazioni non ufficiali", "it", zone, now, "Ci sono allerte?")
        assertEquals(
            "Allerte in corso nella regione (GDACS, USGS, Meteoalarm, aggiornate alle 09:15):\n" +
                "- Terremoto di magnitudo 4,8 (10 km a S di Foggia, livello alto)\n" +
                "- Allerta gialla per vento (Puglia, livello moderato, fino alle 22:00)\n" +
                "Informazioni non ufficiali.",
            text,
        )
    }

    @Test
    fun `blocco inglese con il titolo della fonte`() {
        val text = alertsContext(regionAlerts(wind), original, "Unofficial", "en", zone, now, "Ci sono allerte?")
        assertEquals(
            "Active alerts in this region (GDACS, USGS, Meteoalarm, updated at 09:15):\n" +
                "- Yellow Wind Warning issued for Italy - Puglia (moderate level, until 22:00)\n" +
                "Unofficial.",
            text,
        )
    }

    @Test
    fun `senza allerte il blocco lo dice, cosi' il modello non ne inventa`() {
        assertEquals(
            "Nessuna allerta in corso nella regione (GDACS, USGS, aggiornate alle 09:15).\nInformazioni non ufficiali.",
            alertsContext(regionAlerts(), italian, "Informazioni non ufficiali", "it", zone, now, "Ci sono allerte?"),
        )
        assertEquals(
            "No active alerts in this region (GDACS, USGS, updated at 09:15).\nUnofficial.",
            alertsContext(regionAlerts(), original, "Unofficial", "en", zone, now, "Ci sono allerte?"),
        )
    }

    @Test
    fun `senza rete l'ora e' quella dell'ultimo download, con il giorno se non e' oggi`() {
        val yesterday = Instant.parse("2026-10-28T18:40:00Z")
        val it = alertsContext(regionAlerts(quake, fresh = false, updated = yesterday), italian, "X", "it", zone, now, "Ci sono allerte?")
        assertTrue(it, it.startsWith("Allerte in corso nella regione (GDACS, USGS, ultime scaricate il 28/10 alle 18:40):"))
        val en = alertsContext(regionAlerts(quake, fresh = false, updated = yesterday), original, "X", "en", zone, now, "Ci sono allerte?")
        assertTrue(en, en.startsWith("Active alerts in this region (GDACS, USGS, last downloaded on 28/10 at 18:40):"))
    }

    @Test
    fun `oltre il limite le allerte in piu' sono solo contate`() {
        val many = (1..ALERTS_IN_CONTEXT + 3).map { alert("g$it", AlertSource.GDACS, AlertLevel.LOW, "Flood $it") }
        val it = alertsContext(regionAlerts(*many.toTypedArray()), original, "X", "it", zone, now, "Ci sono allerte?")
        assertEquals(ALERTS_IN_CONTEXT, it.lines().count { line -> line.startsWith("- Flood") })
        assertTrue(it, it.contains("\n- e altre 3\n"))
        assertFalse(it, it.contains("Flood ${ALERTS_IN_CONTEXT + 1}"))
        val en = alertsContext(regionAlerts(*many.toTypedArray()), original, "X", "en", zone, now, "Ci sono allerte?")
        assertTrue(en, en.contains("\n- and 3 more\n"))
    }

    @Test
    fun `una domanda che nomina un'area elenca solo le sue allerte`() {
        val calabria = alert("m2", AlertSource.METEOALARM, AlertLevel.MODERATE, "Yellow Thunderstorm Warning issued for Italy - Calabria")
        val areas = { alert: Alert -> AlertText("Allerta gialla", listOf(alert.title.substringAfterLast("- "))) }
        val it = alertsContext(regionAlerts(calabria, wind), areas, "X", "it", zone, now, "Ci sono allerte meteo in Puglia?")
        assertEquals(
            "Allerte in corso nella regione (GDACS, USGS, Meteoalarm, aggiornate alle 09:15):\n" +
                "- Allerta gialla (Puglia, livello moderato, fino alle 22:00)\n" +
                "- e altre 1 nel resto della regione\nX.",
            it,
        )
        val en = alertsContext(regionAlerts(calabria, wind), areas, "X", "en", zone, now, "Any weather warnings in Puglia?")
        assertTrue(en, en.contains("\n- and 1 more elsewhere in the region\n"))
        // Senza un'area nominata restano tutte.
        val all = alertsContext(regionAlerts(calabria, wind), areas, "X", "it", zone, now, "Ci sono allerte meteo?")
        assertTrue(all, all.contains("Calabria") && all.contains("Puglia"))
    }

    @Test
    fun `citazione con GDACS e USGS sempre e le altre fonti solo con allerte`() {
        assertEquals("Fonte: allerte di GDACS, USGS", alertsCitation(regionAlerts(), "it"))
        assertEquals("Source: alerts from GDACS, USGS, Meteoalarm", alertsCitation(regionAlerts(wind, quake), "en"))
    }

    @Test
    fun `la domanda di prova chiede le allerte`() {
        assertTrue(isDisasterQuestion("Ci sono allerte meteo in Puglia?"))
        assertTrue(isDisasterQuestion("Are there any weather warnings in Apulia?"))
        assertFalse(isDisasterQuestion("Che tempo fa domani a Bari?"))
    }
}
