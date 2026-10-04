package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingVariantChoicesTest {

    @Test
    fun `senza una scelta nei Contenuti la regione segue il default`() {
        RoutingVariantChoice.entries.forEach { default ->
            assertEquals(default, RoutingVariantChoices(default = default).choiceFor("italia"))
        }
        assertTrue(RoutingVariantChoices(default = RoutingVariantChoice.CAR).isCarOnly("italia"))
        assertFalse(RoutingVariantChoices(default = RoutingVariantChoice.BIKE_FOOT).isCarOnly("italia"))
    }

    @Test
    fun `una scelta esplicita vince sul default, anche Tutti`() {
        val choices = RoutingVariantChoices(
            carOnly = setOf("italia"),
            bikeFoot = setOf("francia"),
            explicit = setOf("italia", "francia", "spagna"),
            default = RoutingVariantChoice.ALL,
        )
        assertEquals(RoutingVariantChoice.CAR, choices.choiceFor("italia"))
        assertEquals(RoutingVariantChoice.BIKE_FOOT, choices.choiceFor("francia"))
        // Esplicita "Tutti" (chiave scritta a false): non la cambia un default solo auto.
        assertEquals(RoutingVariantChoice.ALL, choices.copy(default = RoutingVariantChoice.CAR).choiceFor("spagna"))
        assertEquals(RoutingVariantChoice.CAR, choices.copy(default = RoutingVariantChoice.BIKE_FOOT).choiceFor("italia"))
        // Un'altra regione, non esplicita, segue il default.
        assertEquals(RoutingVariantChoice.CAR, choices.copy(default = RoutingVariantChoice.CAR).choiceFor("germania"))
    }
}
