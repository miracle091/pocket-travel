package com.pockettravel.feature.map

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPreferencesTest {

    private val place = NavigationPlace("Piazza Grande", 44.4938, 11.3426, "emilia-romagna")

    private fun navigation(startedAtMillis: Long = 1_000L, arriveBy: Long? = null) =
        ActiveNavigation("emilia-romagna", place, RouteProfile.BIKE, arriveBy, startedAtMillis)

    @Test
    fun `di default la guida si ferma all'arrivo e la vibrazione a piedi e' accesa`() {
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
    fun `senza guida salvata non c'e' nulla da riprendere`() {
        assertNull(NavigationPreferences(InMemoryPreferencesFactory()).activeNavigation(now = 0L))
    }

    @Test
    fun `la guida salvata torna uguale, con l'ora di arrivo`() {
        val files = InMemoryPreferencesFactory()
        val saved = navigation(startedAtMillis = 5_000L, arriveBy = 29_000_000L)
        NavigationPreferences(files).saveActiveNavigation(saved)

        assertEquals(saved, NavigationPreferences(files).activeNavigation(now = 6_000L))
    }

    @Test
    fun `senza ora di arrivo scelta resta null`() {
        val files = InMemoryPreferencesFactory()
        NavigationPreferences(files).saveActiveNavigation(navigation())

        assertNull(NavigationPreferences(files).activeNavigation(now = 2_000L)?.arriveByEpochMinute)
    }

    @Test
    fun `una guida piu' vecchia di dodici ore non si riprende e si cancella`() {
        val files = InMemoryPreferencesFactory()
        val preferences = NavigationPreferences(files)
        preferences.saveActiveNavigation(navigation(startedAtMillis = 0L))

        assertNull(preferences.activeNavigation(now = 12 * 60 * 60 * 1000L + 1))
        assertNull(preferences.activeNavigation(now = 1_000L))
    }

    @Test
    fun `chiudere la guida toglie lo stato salvato`() {
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
