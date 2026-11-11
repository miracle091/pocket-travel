package com.pockettravel.core.sync

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingUpdatesTest {
    private val france = RegionUpdateItem("fr", "Francia", "2026-11-10", redownload = false)
    private val italy = RegionUpdateItem("it", "Italia", "2026-11-09", redownload = true)

    @Test
    fun `l'elenco salvato si rilegge uguale`() {
        val factory = InMemoryPreferencesFactory()
        val pending = PendingUpdates(listOf(france, italy), appVersion = "0.15.0", aiModelVersion = "3")
        PendingUpdatesStore(factory).save(pending)
        assertEquals(pending, PendingUpdatesStore(factory).load())
    }

    @Test
    fun `il conteggio comprende nazioni, app e modello`() {
        val pending = PendingUpdates(listOf(france, italy), appVersion = "0.15.0", aiModelVersion = "3")
        assertEquals(4, pending.count)
    }

    @Test
    fun `suona solo per un aggiornamento nuovo`() {
        val before = PendingUpdates(listOf(france))
        val after = PendingUpdates(listOf(france, italy))
        assertEquals(UpdatesNotificationAction.Post(alert = true), updatesNotificationAction(after, before.keys, showing = true))
        assertEquals(UpdatesNotificationAction.Post(alert = true), updatesNotificationAction(after, before.keys, showing = false))
    }

    @Test
    fun `la stessa nazione ripubblicata e' una novita'`() {
        val republished = PendingUpdates(listOf(france.copy(updatedAt = "2026-11-11")))
        val action = updatesNotificationAction(republished, PendingUpdates(listOf(france)).keys, showing = false)
        assertEquals(UpdatesNotificationAction.Post(alert = true), action)
    }

    @Test
    fun `senza novita' si aggiorna in silenzio se visibile, altrimenti non ricompare`() {
        val alerted = PendingUpdates(listOf(france, italy)).keys
        val fewer = PendingUpdates(listOf(france))
        assertEquals(UpdatesNotificationAction.Post(alert = false), updatesNotificationAction(fewer, alerted, showing = true))
        assertEquals(UpdatesNotificationAction.Skip, updatesNotificationAction(fewer, alerted, showing = false))
    }

    @Test
    fun `senza aggiornamenti la notifica si toglie`() {
        assertEquals(UpdatesNotificationAction.Cancel, updatesNotificationAction(PendingUpdates(), setOf("app:0.15.0"), showing = true))
    }
}
