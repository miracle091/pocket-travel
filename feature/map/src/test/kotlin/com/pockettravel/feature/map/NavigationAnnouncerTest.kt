package com.pockettravel.feature.map

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import com.pockettravel.feature.map.testing.FakeNavigationVibration
import com.pockettravel.feature.map.testing.FakeNavigationVoice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationAnnouncerTest {
    private val voice = FakeNavigationVoice()
    private val vibration = FakeNavigationVibration()
    private val preferences = NavigationPreferences(InMemoryPreferencesFactory())
    private val announcer = NavigationAnnouncer(voice, vibration, XmlVoiceTexts("it"), preferences)

    private val left = TurnInstruction(TurnType.LEFT, 400.0, 1)
    private val arrive = TurnInstruction(TurnType.ARRIVE, 0.0, 2)
    private val route = Route(points = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.01)), distanceMeters = 500.0, durationSeconds = 400.0, instructions = listOf(left, arrive))

    private fun progress(instruction: TurnInstruction, index: Int, meters: Double, arrived: Boolean = false) = NavigationProgress(
        nextInstruction = instruction,
        nextInstructionIndex = index,
        distanceToNextMeters = meters,
        remainingMeters = meters + index * 100.0,
        offRoute = false,
        arrived = arrived,
        positionOnRoute = RoutePoint(0.0, 0.0),
        pathToNext = emptyList(),
        offRouteFixes = 0,
        remainingSeconds = 0.0,
        segmentIndex = 0,
        bearingDegrees = 0.0,
    )

    private fun walkToArrival() {
        listOf(progress(left, 0, 40.0), progress(left, 0, 6.0), progress(arrive, 1, 0.0, arrived = true)).forEachIndexed { i, p ->
            announcer.onProgress(route, p, "Via Roma", i * 1_000L)
        }
    }

    @Test
    fun `a piedi la voce dice preavviso, svolta e arrivo, e la svolta e l'arrivo vibrano`() = runTest {
        announcer.startVoice()
        announcer.begin(RouteProfile.WALK)
        walkToArrival()

        assertEquals(listOf("Tra 40 metri, gira a sinistra in Via Roma", "Gira a sinistra in Via Roma", "Destinazione raggiunta"), voice.said)
        assertEquals(listOf("LEFT", "ARRIVED"), vibration.events)
    }

    @Test
    fun `con la guida vocale spenta restano le vibrazioni, senza vibrazione a piedi resta la voce`() = runTest {
        announcer.startVoice()
        preferences.setVoiceGuidance(false)
        announcer.begin(RouteProfile.WALK)
        walkToArrival()
        assertEquals(emptyList<String>(), voice.said)
        assertEquals(listOf("LEFT", "ARRIVED"), vibration.events)

        preferences.setVoiceGuidance(true)
        preferences.setWalkingHaptics(false)
        vibration.events.clear()
        announcer.begin(RouteProfile.WALK)
        walkToArrival()
        assertEquals(3, voice.said.size)
        assertEquals(emptyList<String>(), vibration.events)
    }

    @Test
    fun `in auto nessuna vibrazione e, dopo la fine della navigazione, nessuna frase`() = runTest {
        announcer.startVoice()
        announcer.begin(RouteProfile.CAR)
        announcer.onProgress(route, progress(left, 0, 6.0), null, 0)
        assertEquals(listOf("Gira a sinistra"), voice.said)
        assertEquals(emptyList<String>(), vibration.events)

        announcer.end()
        announcer.onProgress(route, progress(arrive, 1, 0.0, arrived = true), null, 1_000)
        assertEquals(1, voice.said.size)
    }
}
