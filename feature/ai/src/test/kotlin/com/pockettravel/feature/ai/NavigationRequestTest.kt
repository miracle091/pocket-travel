package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRequestTest {

    @Test
    fun `richieste in italiano, con e senza mezzo`() {
        assertEquals(NavigationRequest("Colosseo", NavigationRequestMode.WALK), parseNavigationRequest("Portami al Colosseo a piedi"))
        assertEquals(NavigationRequest("stazione", null), parseNavigationRequest("portami alla stazione"))
        assertEquals(NavigationRequest("aeroporto", NavigationRequestMode.CAR), parseNavigationRequest("Come arrivo all'aeroporto in macchina?"))
        assertEquals(NavigationRequest("Piazza Navona", NavigationRequestMode.BIKE), parseNavigationRequest("Naviga verso Piazza Navona in bici"))
        assertEquals(NavigationRequest("Via Roma 12", null), parseNavigationRequest("Indicazioni per Via Roma 12"))
        assertEquals(NavigationRequest("Duomo", NavigationRequestMode.WALK), parseNavigationRequest("voglio andare a piedi al Duomo, per favore"))
    }

    @Test
    fun `richieste in inglese`() {
        assertEquals(NavigationRequest("station", NavigationRequestMode.BIKE), parseNavigationRequest("Take me to the station by bike"))
        assertEquals(NavigationRequest("Louvre", null), parseNavigationRequest("Directions to the Louvre, please"))
        assertEquals(NavigationRequest("airport", NavigationRequestMode.CAR), parseNavigationRequest("How do I get to the airport by car?"))
        assertEquals(NavigationRequest("12 Rue de Rivoli", NavigationRequestMode.WALK), parseNavigationRequest("navigate to 12 Rue de Rivoli on foot"))
    }

    @Test
    fun `domande sulla guida non sono richieste di navigazione`() {
        assertNull(parseNavigationRequest("Come arrivare a Roma dall'Italia?"))
        assertNull(parseNavigationRequest("Come si arriva in centro?"))
        assertNull(parseNavigationRequest("Quali sono i musei da vedere?"))
        assertNull(parseNavigationRequest("How do I get a visa?"))
        assertNull(parseNavigationRequest("Is it safe to walk at night?"))
    }

    @Test
    fun `senza meta non e' una richiesta`() {
        assertNull(parseNavigationRequest("Portami"))
        assertNull(parseNavigationRequest("take me to"))
        assertNull(parseNavigationRequest("portami a piedi"))
    }
}
