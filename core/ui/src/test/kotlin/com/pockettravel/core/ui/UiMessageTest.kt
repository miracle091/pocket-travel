package com.pockettravel.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiMessageTest {

    @Test
    fun `un testo semplice si legge cosi' com'e`() {
        assertEquals("Errore", UiMessage.Plain("Errore").text)
    }

    @Test
    fun `una risorsa senza argomenti ha l'elenco vuoto e con argomenti li conserva in ordine`() {
        assertEquals(emptyList<Any>(), UiMessage.Res(1).args)
        assertEquals(listOf("a", 2), UiMessage.Res(1, listOf("a", 2)).args)
    }
}
