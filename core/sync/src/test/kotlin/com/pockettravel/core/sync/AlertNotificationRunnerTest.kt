package com.pockettravel.core.sync

import com.pockettravel.core.data.InstalledRegion
import com.pockettravel.core.data.alerts.Alert
import com.pockettravel.core.data.alerts.AlertKind
import com.pockettravel.core.data.alerts.AlertLevel
import com.pockettravel.core.data.alerts.AlertSource
import com.pockettravel.core.data.alerts.AlertsRepository
import com.pockettravel.core.data.alerts.RegionAlerts
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class AlertNotificationRunnerTest {

    private class FakeAlerts : AlertsRepository {
        val byRegion = mutableMapOf<String, List<Alert>>()
        override suspend fun alerts(regionId: String): RegionAlerts? =
            byRegion[regionId]?.let { RegionAlerts(it, updatedAtMillis = 0, fresh = true) }
    }

    private val files = InMemoryPreferencesFactory()
    private val preferences = AlertNotificationPreferences(files).apply { setEnabled(true) }
    private val regions = FakeRegionRepository(listOf(region("it-sicilia", "Sicilia"), region("jp-honshu", "Honshu")))
    private val alerts = FakeAlerts()
    private val shown = mutableListOf<RegionAlertNotification>()
    private var now = Instant.parse("2026-10-26T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant = now
    }

    private fun runner() = AlertNotificationRunner(regions, alerts, preferences, { shown += it }, clock)

    private fun region(id: String, name: String) = InstalledRegion(
        regionId = id, displayName = name, countryCode = null, mapVersion = null, routingVersion = null,
        poiVersion = null, poiExtraVersion = null, addressesVersion = null, poiSizeBytes = null,
        poiExtraSizeBytes = null, sizeBytes = 0,
    )

    private fun alert(
        id: String,
        level: AlertLevel = AlertLevel.HIGH,
        kind: AlertKind = AlertKind.WEATHER,
        source: AlertSource = AlertSource.METEOALARM,
    ) = Alert(
        id = id, source = source, kind = kind, level = level, sourceLevel = null, title = "Titolo $id", details = null,
        startsAt = null, endsAt = null, area = null, areaName = null, url = null,
    )

    @Test
    fun `notificano le allerte HIGH e EXTREME e gli tsunami, non le altre`() {
        assertTrue(alert("a", AlertLevel.HIGH).isNotifiable())
        assertTrue(alert("b", AlertLevel.EXTREME).isNotifiable())
        assertTrue(alert("c", AlertLevel.LOW, AlertKind.TSUNAMI).isNotifiable())
        assertFalse(alert("d", AlertLevel.MODERATE).isNotifiable())
        assertFalse(alert("e", AlertLevel.LOW).isNotifiable())
    }

    @Test
    fun `una notifica per regione con la prima allerta e il numero delle altre`() = runTest {
        alerts.byRegion["it-sicilia"] = listOf(alert("1"), alert("2", AlertLevel.EXTREME), alert("3", AlertLevel.MODERATE))

        runner().run()

        assertEquals(listOf(RegionAlertNotification("it-sicilia", "Sicilia", alert("1"), 1)), shown)
    }

    @Test
    fun `un'allerta si notifica una sola volta, anche in un giro successivo`() = runTest {
        alerts.byRegion["it-sicilia"] = listOf(alert("1"))
        runner().run()
        runner().run()
        assertEquals(1, shown.size)

        alerts.byRegion["it-sicilia"] = listOf(alert("1"), alert("2"))
        runner().run()
        assertEquals("Titolo 2", shown.last().firstAlert.title)
        assertEquals(0, shown.last().otherAlerts)
    }

    @Test
    fun `lo stesso id di fonti diverse e' un'allerta diversa`() = runTest {
        alerts.byRegion["it-sicilia"] = listOf(alert("1", source = AlertSource.GDACS))
        runner().run()
        alerts.byRegion["it-sicilia"] = listOf(alert("1", source = AlertSource.USGS))
        runner().run()
        assertEquals(2, shown.size)
    }

    @Test
    fun `dopo sette giorni la memoria si svuota e l'allerta ancora in corso torna a notificare`() = runTest {
        alerts.byRegion["jp-honshu"] = listOf(alert("1"))
        runner().run()

        now = now.plusMillis(TimeUnit.DAYS.toMillis(8))
        runner().run()

        assertEquals(2, shown.size)
    }

    @Test
    fun `con l'interruttore spento non notifica niente`() = runTest {
        preferences.setEnabled(false)
        alerts.byRegion["it-sicilia"] = listOf(alert("1"))

        runner().run()

        assertTrue(shown.isEmpty())
    }

    @Test
    fun `una regione senza allerte scaricate non notifica`() = runTest {
        runner().run()
        assertTrue(shown.isEmpty())
    }
}
