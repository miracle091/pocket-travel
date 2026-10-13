package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gli spazi non ASCII fra via e civico valgono come uno spazio, come in [streetKey]. */
class AddressQuerySpacesTest {

    @Test
    fun `lo spazio unificatore fra via e civico divide come uno spazio`() {
        assertEquals(AddressQuery("Via Roma", "12"), parseAddressQuery("Via Roma 12"))
        assertEquals(AddressQuery("Via Roma", "12"), parseAddressQuery("Via Roma 12"))
        assertEquals(AddressQuery("Via Roma", "12"), parseAddressQuery("Via Roma 12"))
        assertEquals(AddressQuery("Via Roma", "12"), parseAddressQuery("Via Roma 12"))
    }

    @Test
    fun `il civico davanti alla via si riconosce anche con lo spazio unificatore`() {
        assertEquals(AddressQuery("Rue de Rivoli", "12"), parseAddressQuery("12 Rue de Rivoli"))
    }
}
