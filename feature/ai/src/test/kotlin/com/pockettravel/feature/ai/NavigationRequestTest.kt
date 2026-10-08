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
    fun `verbi di visita e momenti del giorno non fanno parte della destinazione`() {
        assertEquals(NavigationRequest("Colosseo", NavigationRequestProfile.WALK, "Colosseo domani"), parseNavigationRequest("portami a vedere il Colosseo domani, vado a piedi"))
        assertEquals(NavigationRequest("beach", null, "beach tomorrow morning"), parseNavigationRequest("take me to the beach tomorrow morning"))
        assertEquals(NavigationRequest("Pantheon", null, "Pantheon stasera"), parseNavigationRequest("portami a visitare il Pantheon stasera"))
        assertEquals(NavigationRequest("castello", NavigationRequestProfile.CAR, "castello domani mattina"), parseNavigationRequest("portami al castello domani mattina in macchina"))
        assertEquals(NavigationRequest("Big Ben", null, "Big Ben tonight"), parseNavigationRequest("take me to see Big Ben tonight"))
        assertEquals(NavigationRequest("old harbour", NavigationRequestProfile.BIKE, "old harbour this afternoon"), parseNavigationRequest("take me to visit the old harbour this afternoon by bike"))
        assertEquals(NavigationRequest("station", null, "station now"), parseNavigationRequest("take me to the station now"))
    }

    @Test
    fun `un momento del giorno in minuscolo puo' essere il nome del luogo, che il Navigatore cerca per primo`() {
        assertEquals(NavigationRequest("bar", null, "bar stasera"), parseNavigationRequest("portami al bar stasera"))
        assertEquals(NavigationRequest("Colosseo", null), parseNavigationRequest("portami al Colosseo"))
    }

    @Test
    fun `un momento del giorno con la maiuscola fa parte del nome del luogo`() {
        assertEquals(NavigationRequest("Bar Stasera", null), parseNavigationRequest("portami al Bar Stasera"))
        assertEquals(NavigationRequest("Bar Stasera", NavigationRequestProfile.WALK, "Bar Stasera domani"), parseNavigationRequest("portami al Bar Stasera domani a piedi"))
        assertEquals(NavigationRequest("Café Tomorrow", null), parseNavigationRequest("take me to Café Tomorrow"))
        assertEquals(NavigationRequest("Hotel This Morning", null), parseNavigationRequest("take me to the Hotel This Morning"))
    }

    @Test
    fun `un verbo di visita con la maiuscola fa parte del nome del luogo`() {
        assertEquals(NavigationRequest("See Hotel Lugano", null), parseNavigationRequest("take me to See Hotel Lugano"))
        assertEquals(NavigationRequest("Visit Malta office", null), parseNavigationRequest("take me to the Visit Malta office"))
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
    fun `senza destinazione non e' una richiesta`() {
        assertNull(parseNavigationRequest("Portami"))
        assertNull(parseNavigationRequest("take me to"))
        assertNull(parseNavigationRequest("portami a piedi"))
    }
}
