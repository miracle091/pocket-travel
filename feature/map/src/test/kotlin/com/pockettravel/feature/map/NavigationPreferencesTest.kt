package com.pockettravel.feature.map

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NavigationPreferencesTest {

    private val place = NavigationPlace("Piazza Grande", 44.4938, 11.3426, "emilia-romagna")

    private fun navigation(startedAtMillis: Long = 1_000L, arriveBy: Long? = null) =
        ActiveNavigation("emilia-romagna", place, RouteProfile.BIKE, arriveBy, startedAtMillis)

    @Test
    fun `di default la navigazione si ferma all'arrivo e la vibrazione a piedi e' accesa`() {
        val preferences = NavigationPreferences(InMemoryPreferencesFactory())

        assertTrue(preferences.stopGpsOnArrival.value)
        assertTrue(preferences.walkingHaptics.value)
    }

    @Test
    fun `le impostazioni restano per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        NavigationPreferences(files).apply {
            setStopGpsOnArrival(false)
            setWalkingHaptics(false)
        }

        val reopened = NavigationPreferences(files)
        assertFalse(reopened.stopGpsOnArrival.value)
        assertFalse(reopened.walkingHaptics.value)
    }

    @Test
    fun `senza navigazione salvata non c'e' nulla da riprendere`() {
        assertNull(NavigationPreferences(InMemoryPreferencesFactory()).activeNavigation(now = 0L))
    }

    @Test
    fun `la navigazione salvata torna uguale, con l'ora di arrivo`() {
        val files = InMemoryPreferencesFactory()
        val saved = navigation(startedAtMillis = 5_000L, arriveBy = 29_000_000L)
        NavigationPreferences(files).saveActiveNavigation(saved)

        assertEquals(saved, NavigationPreferences(files).activeNavigation(now = 6_000L))
    }

    @Test
    fun `la corsa da prendere torna uguale, anche con tabulazioni nei nomi e senza colori`() {
        val files = InMemoryPreferencesFactory()
        val departures = listOf(LocalDateTime.of(2026, 11, 14, 14, 7), LocalDateTime.of(2026, 11, 14, 14, 27))
        val trip = TripToCatch("Carenes", 43.62, 1.47, "83", 0xFFE6007E.toInt(), null, "St-Orens Lycee", departures)
        NavigationPreferences(files).saveActiveNavigation(navigation().copy(toCatch = trip))
        assertEquals(trip, NavigationPreferences(files).activeNavigation(now = 2_000L)?.toCatch)

        val tabs = trip.copy(stopName = "Piazza	Grande", headsign = null)
        NavigationPreferences(files).saveActiveNavigation(navigation().copy(toCatch = tabs))
        assertEquals(tabs.copy(stopName = "Piazza Grande"), NavigationPreferences(files).activeNavigation(now = 2_000L)?.toCatch)
        assertNull(TripToCatch.decode("rotta"))
    }

    @Test
    fun `senza ora di arrivo scelta resta null`() {
        val files = InMemoryPreferencesFactory()
        NavigationPreferences(files).saveActiveNavigation(navigation())

        assertNull(NavigationPreferences(files).activeNavigation(now = 2_000L)?.arriveByEpochMinute)
    }

    @Test
    fun `una navigazione piu' vecchia di dodici ore non si riprende e si cancella`() {
        val files = InMemoryPreferencesFactory()
        val preferences = NavigationPreferences(files)
        preferences.saveActiveNavigation(navigation(startedAtMillis = 0L))

        assertNull(preferences.activeNavigation(now = 12 * 60 * 60 * 1000L + 1))
        assertNull(preferences.activeNavigation(now = 1_000L))
    }

    @Test
    fun `chiudere la navigazione toglie lo stato salvato`() {
        val preferences = NavigationPreferences(InMemoryPreferencesFactory())
        preferences.saveActiveNavigation(navigation())
        preferences.clearActiveNavigation()

        assertNull(preferences.activeNavigation(now = 2_000L))
    }

    @Test
    fun `un modo salvato sconosciuto ripiega su a piedi`() {
        val files = InMemoryPreferencesFactory()
        NavigationPreferences(files).saveActiveNavigation(navigation())
        files.open("navigation").edit().putString("guidance_mode", "TELETRASPORTO").apply()

        assertEquals(RouteProfile.WALK, NavigationPreferences(files).activeNavigation(now = 2_000L)?.mode)
    }
}
