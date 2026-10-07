package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationRequestTest {

    @Test
    fun `richieste in italiano, con e senza mezzo`() {
        assertEquals(NavigationRequest("Colosseo", NavigationRequestProfile.WALK), parseNavigationRequest("Portami al Colosseo a piedi"))
        assertEquals(NavigationRequest("stazione", null), parseNavigationRequest("portami alla stazione"))
        assertEquals(NavigationRequest("aeroporto", NavigationRequestProfile.CAR), parseNavigationRequest("Come arrivo all'aeroporto in macchina?"))
        assertEquals(NavigationRequest("Piazza Navona", NavigationRequestProfile.BIKE), parseNavigationRequest("Naviga verso Piazza Navona in bici"))
        assertEquals(NavigationRequest("Via Roma 12", null), parseNavigationRequest("Indicazioni per Via Roma 12"))
        assertEquals(NavigationRequest("Duomo", NavigationRequestProfile.WALK), parseNavigationRequest("voglio andare a piedi al Duomo, per favore"))
    }

    @Test
    fun `richieste in inglese`() {
        assertEquals(NavigationRequest("station", NavigationRequestProfile.BIKE), parseNavigationRequest("Take me to the station by bike"))
        assertEquals(NavigationRequest("Louvre", null), parseNavigationRequest("Directions to the Louvre, please"))
        assertEquals(NavigationRequest("airport", NavigationRequestProfile.CAR), parseNavigationRequest("How do I get to the airport by car?"))
        assertEquals(NavigationRequest("12 Rue de Rivoli", NavigationRequestProfile.WALK), parseNavigationRequest("navigate to 12 Rue de Rivoli on foot"))
    }

    @Test
    fun `altri modi di dire il mezzo e parole di contorno`() {
        assertEquals(NavigationRequest("Torre Eiffel", NavigationRequestProfile.WALK), parseNavigationRequest("voglio andare alla Torre Eiffel camminando"))
        assertEquals(NavigationRequest("spiaggia di Jurmala", NavigationRequestProfile.CAR), parseNavigationRequest("andiamo alla spiaggia di Jurmala con la macchina"))
        assertEquals(NavigationRequest("Basilica di San Pietro", NavigationRequestProfile.BIKE), parseNavigationRequest("come vado alla Basilica di San Pietro con la bici"))
        assertEquals(NavigationRequest("Colosseo", NavigationRequestProfile.WALK), parseNavigationRequest("portami al Colosseo, vado a piedi"))
        assertEquals(NavigationRequest("British Museum", NavigationRequestProfile.WALK), parseNavigationRequest("guide me to the British Museum, I'm walking"))
        assertEquals(NavigationRequest("Piazza del Campo", NavigationRequestProfile.CAR), parseNavigationRequest("indicazioni stradali per Piazza del Campo in auto"))
        assertEquals(NavigationRequest("farmacia", null), parseNavigationRequest("portami subito alla farmacia"))
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
