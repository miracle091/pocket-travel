package com.pockettravel.feature.guide

import com.pockettravel.core.data.EmergencyNumbers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class GuideFormattingTest {
    private lateinit var previous: Locale

    @Before
    fun setUp() {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.ITALIAN)
    }

    @After
    fun tearDown() = Locale.setDefault(previous)

    @Test
    fun `la distanza e' in metri sotto il chilometro, con un decimale sotto i 10 km, intera oltre`() {
        assertEquals("800 m", distanceText(0.8))
        assertEquals("2,3 km", distanceText(2.34))
        assertEquals("45 km", distanceText(45.4))
    }

    @Test
    fun `il decimale segue la lingua`() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("2.3 km", distanceText(2.34))
    }

    @Test
    fun `un numero unico per tutto diventa una riga sola`() {
        val entries = EmergencyNumbers(general = "112", police = "112", ambulance = "112", fire = "112").entries()
        assertEquals(listOf(listOf(R.string.emergency_single) to "112"), entries)
    }

    @Test
    fun `i servizi con lo stesso numero stanno nella stessa riga`() {
        val entries = EmergencyNumbers(general = null, police = "110", ambulance = "119", fire = "119").entries()
        assertEquals(
            listOf(
                listOf(R.string.emergency_police) to "110",
                listOf(R.string.emergency_ambulance, R.string.emergency_fire) to "119",
            ),
            entries,
        )
    }

    @Test
    fun `il numero generale diverso dagli altri ha la sua riga in cima`() {
        val entries = EmergencyNumbers(general = "112", police = "113", ambulance = "118", fire = "115").entries()
        assertEquals(listOf("112", "113", "118", "115"), entries.map { it.second })
        assertEquals(listOf(R.string.emergency_general), entries.first().first)
    }

    @Test
    fun `gli abitanti si arrotondano alle migliaia solo da 10_000 in su`() {
        assertEquals(9_499L, roundedPopulation(9_499))
        assertEquals(10_000L, roundedPopulation(10_000))
        assertEquals(361_000L, roundedPopulation(360_500))
        assertEquals(360_000L, roundedPopulation(360_499))
    }
}
