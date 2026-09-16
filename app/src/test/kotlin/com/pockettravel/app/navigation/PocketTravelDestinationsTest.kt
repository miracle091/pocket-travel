package com.pockettravel.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketTravelDestinationsTest {

    @Test
    fun `regionHub costruisce la rotta con l'id regione`() {
        assertEquals("region/italia?tab=guide", PocketTravelDestinations.regionHub("italia"))
    }
}
