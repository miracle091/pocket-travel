package com.pockettravel.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickFactsTest {

    private val none = QuickFactsExtra(language = null, currency = null, transport = null)

    @Test
    fun `fuso in GMT e niente numeri di emergenza, che hanno la loro scheda`() {
        val body = "Lingua: Giapponese\nElettricità: 100V/50-60Hz\nFuso orario: UTC+9\n" +
            "Numeri di emergenza: Polizia 110, Ambulanza 119, Vigili del fuoco 119"
        assertEquals("Lingua: Giapponese\nElettricità: 100V/50-60Hz\nFuso orario: GMT+9", quickFactsBody(body, none))
    }

    @Test
    fun `valuta, trasporti e lingua mancante aggiunti in ordine fisso`() {
        val body = "Fuso orario: UTC-4:30\nElettricità: 120V/60Hz"
        val extra = QuickFactsExtra(language = "Spagnolo", currency = "bolívar venezuelano (VES)", transport = "autobus (3 autostazioni), aereo (1 aeroporto)")
        assertEquals(
            "Lingua: Spagnolo\nElettricità: 120V/60Hz\nFuso orario: GMT-4:30\nValuta: bolívar venezuelano (VES)\n" +
                "Trasporti: autobus (3 autostazioni), aereo (1 aeroporto)",
            quickFactsBody(body, extra),
        )
    }

    @Test
    fun `lingua e valuta della guida hanno la precedenza`() {
        val body = "Lingua: Italiano, Tedesco (Trentino-Alto Adige)\nValuta: euro"
        val extra = QuickFactsExtra(language = "Italiano", currency = "euro (EUR)", transport = null)
        assertEquals("Lingua: Italiano, Tedesco (Trentino-Alto Adige)\nValuta: euro", quickFactsBody(body, extra))
    }

    @Test
    fun `guide inglesi, etichette inglesi e valori aggiunti in inglese`() {
        val body = "Electricity: 100V/50-60Hz (American plug)\nTime zone: UTC+9"
        val labels = QuickFactsLabels.of(body, "it")
        assertEquals(QuickFactsLabels.ENGLISH, labels)
        val extra = QuickFactsExtra(language = "Japanese", currency = "Japanese Yen (JPY)", transport = null)
        assertEquals(
            "Language: Japanese\nElectricity: 100V/50-60Hz (American plug)\nTime zone: GMT+9\nCurrency: Japanese Yen (JPY)",
            quickFactsBody(body, extra, labels),
        )
    }

    @Test
    fun `senza campi riconoscibili vale la lingua dell'interfaccia`() {
        assertEquals(QuickFactsLabels.ENGLISH, QuickFactsLabels.of("", "en"))
        assertEquals(QuickFactsLabels.ITALIAN, QuickFactsLabels.of("Lingua: Italiano", "en"))
    }
}
