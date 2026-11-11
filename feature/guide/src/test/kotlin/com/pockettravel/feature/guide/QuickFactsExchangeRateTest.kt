package com.pockettravel.feature.guide

import com.pockettravel.core.data.EuroRate
import com.pockettravel.core.data.ExchangeRateTable
import com.pockettravel.core.data.IndicativeRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class QuickFactsExchangeRateTest {

    private val day = LocalDate.of(2026, 11, 11)

    private val table = ExchangeRateTable(
        mapOf("THB" to EuroRate(37.5, day), "USD" to EuroRate(1.25, day), "ARS" to EuroRate(1733.0, day)),
    )

    @Test
    fun `tasso con la data e indicativo nella lingua della guida`() {
        val rate = IndicativeRate("EUR", "THB", 37.5, day)
        assertEquals("1 EUR ≈ 37,5 THB (Frankfurter, 11 nov 2026), indicativo", exchangeRateText(rate, Locale.ITALIAN))
        assertEquals("1 EUR ≈ 37.5 THB (Frankfurter, Nov 11, 2026), indicative", exchangeRateText(rate, Locale.ENGLISH))
        assertNull(exchangeRateText(null, Locale.ITALIAN))
    }

    @Test
    fun `quattro cifre significative`() {
        assertEquals("1 EUR ≈ 2.141.000 IRR", exchangeRateText(IndicativeRate("EUR", "IRR", 2140720.0, day), Locale.ITALIAN)?.substringBefore(" ("))
        assertEquals("1 EUR ≈ 2,141,000 IRR", exchangeRateText(IndicativeRate("EUR", "IRR", 2140720.0, day), Locale.ENGLISH)?.substringBefore(" ("))
        assertEquals("1 EUR ≈ 0,3467 KWD", exchangeRateText(IndicativeRate("EUR", "KWD", 0.34667, day), Locale.ITALIAN)?.substringBefore(" ("))
        assertEquals("1 JPY ≈ 0,005714 EUR", exchangeRateText(IndicativeRate("JPY", "EUR", 1 / 175.0, day), Locale.ITALIAN)?.substringBefore(" ("))
        assertEquals("1 EUR ≈ 1.733 ARS", exchangeRateText(IndicativeRate("EUR", "ARS", 1733.0, day), Locale.ITALIAN)?.substringBefore(" ("))
    }

    @Test
    fun `nota sul cambio reale per le valute con un mercato parallelo e per il peso cubano`() {
        val toCuba = IndicativeRate("EUR", "CUP", 908.85, day)
        assertEquals(
            "1 EUR ≈ 908,9 CUP (Frankfurter, 11 nov 2026), indicativo; tasso della Banca Centrale di Cuba, può differire dal cambio reale",
            exchangeRateText(toCuba, Locale.ITALIAN),
        )
        assertEquals(
            "1 EUR ≈ 908.9 CUP (Frankfurter, Nov 11, 2026), indicative; Central Bank of Cuba rate, it can differ from the actual exchange rate",
            exchangeRateText(toCuba, Locale.ENGLISH),
        )
        // Anche quando la valuta con il mercato parallelo e' quella di chi viaggia.
        val fromArgentina = exchangeRateText(IndicativeRate("ARS", "EUR", 1 / 1733.0, day), Locale.ITALIAN)
        assertEquals("tasso ufficiale, al cambio reale può differire molto", fromArgentina?.substringAfter("; "))
        PARALLEL_MARKET_CURRENCIES.forEach { code ->
            assertEquals(code, true, exchangeRateText(IndicativeRate("EUR", code, 100.0, day), Locale.ENGLISH)?.endsWith("differ greatly"))
        }
    }

    @Test
    fun `dalla nazionalita' alla valuta della nazione`() {
        assertEquals(IndicativeRate("EUR", "THB", 37.5, day), table.rateFor("IT", "TH", day))
        assertEquals(IndicativeRate("USD", "THB", 30.0, day), table.rateFor("US", "TH", day))
        assertEquals(IndicativeRate("EUR", "ARS", 1733.0, day), table.rateFor("IT", "AR", day))
    }

    @Test
    fun `nessun tasso senza nazionalita', con la stessa valuta o con un tasso vecchio`() {
        assertNull(table.rateFor(null, "TH", day))
        assertNull(table.rateFor("IT", null, day))
        assertNull(table.rateFor("IT", "FR", day))
        assertNull(table.rateFor("IT", "JP", day))
        assertNull(table.rateFor("IT", "TH", day.plusDays(15)))
        assertEquals(37.5, table.rateFor("IT", "TH", day.plusDays(14))?.rate)
    }

    @Test
    fun `il tasso va sotto la valuta`() {
        val rate = "1 EUR ≈ 37,5 THB (Frankfurter, 11 nov 2026), indicativo"
        assertEquals(
            "Lingua: Thai\nValuta:\nbaht thailandese (THB)\n$rate",
            quickFactsBody("Lingua: Thai", QuickFactsExtra(language = null, currency = "baht thailandese (THB)", transport = null, currencyRate = rate)),
        )
        assertEquals(
            "Valuta:\nbaht (THB)\n$rate",
            quickFactsBody("Valuta: baht (THB)", QuickFactsExtra(language = null, currency = "baht thailandese (THB)", transport = null, currencyRate = rate)),
        )
        // Senza valuta il tasso non compare.
        assertEquals("Lingua: Thai", quickFactsBody("Lingua: Thai", QuickFactsExtra(language = null, currency = null, transport = null, currencyRate = rate)))
    }
}
