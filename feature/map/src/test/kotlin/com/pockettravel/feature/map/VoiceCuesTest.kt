package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCuesTest {
    private val italian = XmlVoiceTexts("it")
    private val english = XmlVoiceTexts("en")

    private val left = TurnInstruction(TurnType.LEFT, 0.0, 1)
    private val right = TurnInstruction(TurnType.RIGHT, 0.0, 2)
    private val roundabout = TurnInstruction(TurnType.ROUNDABOUT, 0.0, 1, roundaboutExit = 2)
    private val arrive = TurnInstruction(TurnType.ARRIVE, 0.0, 3)

    private fun phrases(cue: VoiceCue) = voicePhrase(cue, italian) to voicePhrase(cue, english)

    // --- Frasi IT/EN, dai veri strings.xml ---

    @Test
    fun `il preavviso dice distanza, svolta e strada`() {
        assertEquals(
            "Tra 200 metri, gira a sinistra in Via Roma" to "In 200 meters, turn left onto Via Roma",
            phrases(VoiceCue.Prepare(left, 203.0, "Via Roma")),
        )
    }

    @Test
    fun `alla svolta solo la svolta, con la strada se si sa`() {
        assertEquals("Gira a destra" to "Turn right", phrases(VoiceCue.Now(right, null)))
        assertEquals("Gira a destra in Corso Italia" to "Turn right onto Corso Italia", phrases(VoiceCue.Now(right, "Corso Italia")))
    }

    @Test
    fun `alla rotonda il numero dell'uscita, senza la strada`() {
        assertEquals(
            "Tra 300 metri, alla rotonda prendi l'uscita 2" to "In 300 meters, at the roundabout, take exit 2",
            phrases(VoiceCue.Prepare(roundabout, 310.0, "Via Roma")),
        )
    }

    @Test
    fun `prosegui per i chilometri interi e con la virgola`() {
        assertEquals("Prosegui per 2 chilometri" to "Continue for 2 kilometers", phrases(VoiceCue.Follow(2_040.0)))
        assertEquals("Prosegui per 1,5 chilometri" to "Continue for 1.5 kilometers", phrases(VoiceCue.Follow(1_480.0)))
        assertEquals("Prosegui per 1 chilometro" to "Continue for 1 kilometer", phrases(VoiceCue.Follow(980.0)))
    }

    @Test
    fun `arrivo vicino e destinazione raggiunta`() {
        assertEquals(
            "Tra 100 metri, arrivi a destinazione" to "In 100 meters, you'll arrive at your destination",
            phrases(VoiceCue.Prepare(arrive, 96.0, null)),
        )
        assertEquals("Destinazione raggiunta" to "You have arrived at your destination", phrases(VoiceCue.Arrived))
    }

    @Test
    fun `le distanze si arrotondano come si direbbero`() {
        assertEquals("10 metri", spokenDistance(4.0, italian))
        assertEquals("40 metri", spokenDistance(37.0, italian))
        assertEquals("250 metri", spokenDistance(262.0, italian))
        assertEquals("950 metri", spokenDistance(960.0, italian))
    }

    // --- Quando parlare ---

    private fun progress(instruction: TurnInstruction, index: Int, meters: Double, remaining: Double, offRoute: Boolean = false, arrived: Boolean = false) =
        NavigationProgress(
            nextInstruction = instruction,
            nextInstructionIndex = index,
            distanceToNextMeters = meters,
            remainingMeters = remaining,
            offRoute = offRoute,
            arrived = arrived,
            positionOnRoute = RoutePoint(0.0, 0.0),
            pathToNext = emptyList(),
            offRouteFixes = if (offRoute) 1 else 0,
            remainingSeconds = 0.0,
            segmentIndex = 0,
            bearingDegrees = 0.0,
        )

    private val route = Route(points = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.01)), distanceMeters = 1_000.0, durationSeconds = 700.0, instructions = listOf(left, right, arrive))

    @Test
    fun `a piedi prosegui, preavviso a 50 m, ora a 10 m, una volta sola ciascuno`() {
        val cues = VoiceCues(RouteProfile.WALK)
        val said = listOf(300.0, 120.0, 45.0, 30.0, 8.0, 5.0).mapIndexed { i, meters ->
            cues.next(route, progress(left, 0, meters, 700.0 + meters), null, i * 1_000L)
        }
        assertEquals(listOf(VoiceCue.Follow(300.0), null, VoiceCue.Prepare(left, 45.0, null), null, VoiceCue.Now(left, null), null), said)
    }

    @Test
    fun `la svolta dopo, gia' vicina, ha subito il preavviso con la distanza vera`() {
        val cues = VoiceCues(RouteProfile.WALK)
        cues.next(route, progress(left, 0, 8.0, 108.0), null, 0)
        assertEquals(VoiceCue.Prepare(right, 30.0, "Via Po"), cues.next(route, progress(right, 1, 30.0, 100.0), "Via Po", 1_000))
    }

    @Test
    fun `fuori percorso tace, l'arrivo si dice una volta, un percorso nuovo riparte`() {
        val cues = VoiceCues(RouteProfile.WALK)
        assertNull(cues.next(route, progress(left, 0, 40.0, 500.0, offRoute = true), null, 0))
        val recalculated = route.copy()
        assertEquals(VoiceCue.Prepare(left, 40.0, null), cues.next(recalculated, progress(left, 0, 40.0, 500.0), null, 1_000))
        assertEquals(VoiceCue.Arrived, cues.next(recalculated, progress(arrive, 2, 0.0, 0.0, arrived = true), null, 2_000))
        assertNull(cues.next(recalculated, progress(arrive, 2, 0.0, 0.0, arrived = true), null, 3_000))
    }

    @Test
    fun `in auto il preavviso si allarga con la velocita', circa 20 secondi prima`() {
        val cues = VoiceCues(RouteProfile.CAR)
        // 30 m/s (108 km/h), una posizione al secondo: il preavviso a circa 600 m invece dei 150 m minimi.
        var preparedAt: Double? = null
        for (second in 0..60) {
            val meters = 1_500.0 - second * 30.0
            val cue = cues.next(route, progress(left, 0, meters, 5_000.0 + meters), null, second * 1_000L)
            if (cue is VoiceCue.Prepare) preparedAt = cue.meters
        }
        val at = checkNotNull(preparedAt)
        assertTrue("preavviso a $at m", at in 570.0..600.0)
    }
}
