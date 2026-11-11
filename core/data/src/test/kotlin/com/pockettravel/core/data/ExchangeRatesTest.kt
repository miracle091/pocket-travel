package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class ExchangeRatesTest {

    private val today = LocalDate.of(2026, 11, 11)

    private fun json(rates: String, base: String = "EUR") = """{"publishedAt":1794400000,"base":"$base","rates":{$rates}}""".toByteArray()

    private val table = ExchangeRateTable(
        mapOf(
            "THB" to EuroRate(37.5, LocalDate.of(2026, 11, 11)),
            "USD" to EuroRate(1.25, LocalDate.of(2026, 11, 10)),
        ),
    )

    @Test
    fun `legge i tassi con la loro data e ignora i campi in piu'`() {
        val parsed = parseExchangeRates(json(""""THB":{"rate":37.5,"date":"2026-11-11","x":1},"USD":{"rate":1.25,"date":"2026-11-10"}"""))
        assertEquals(table, parsed)
    }

    @Test
    fun `file non valido rifiutato`() {
        listOf(
            json(""""THB":{"rate":37.5,"date":"2026-11-11"}""", base = "USD"),
            json(""""thb":{"rate":37.5,"date":"2026-11-11"}"""),
            json(""""EUR":{"rate":1,"date":"2026-11-11"}"""),
            json(""""THB":{"rate":0,"date":"2026-11-11"}"""),
            json(""""THB":{"rate":-2,"date":"2026-11-11"}"""),
            json(""""THB":{"rate":37.5,"date":"11/11/2026"}"""),
            json(""""THB":{"rate":37.5}"""),
            "[]".toByteArray(),
        ).forEach { bytes ->
            assertThrows(bytes.decodeToString(), IllegalArgumentException::class.java) { parseExchangeRates(bytes) }
        }
    }

    @Test
    fun `dall'euro e verso l'euro`() {
        assertEquals(IndicativeRate("EUR", "THB", 37.5, LocalDate.of(2026, 11, 11)), table.indicative("EUR", "THB", today))
        assertEquals(IndicativeRate("USD", "EUR", 0.8, LocalDate.of(2026, 11, 10)), table.indicative("USD", "EUR", today))
    }

    @Test
    fun `tasso incrociato con la data piu' vecchia`() {
        assertEquals(IndicativeRate("USD", "THB", 30.0, LocalDate.of(2026, 11, 10)), table.indicative("USD", "THB", today))
    }

    @Test
    fun `stessa valuta o valuta assente danno null`() {
        assertNull(table.indicative("EUR", "EUR", today))
        assertNull(table.indicative("THB", "THB", today))
        assertNull(table.indicative("EUR", "JPY", today))
        assertNull(table.indicative("JPY", "THB", today))
    }

    @Test
    fun `nascosto dopo 14 giorni`() {
        val rateDay = LocalDate.of(2026, 11, 11)
        assertEquals(37.5, table.indicative("EUR", "THB", rateDay.plusDays(EXCHANGE_RATE_MAX_AGE_DAYS))?.rate)
        assertNull(table.indicative("EUR", "THB", rateDay.plusDays(EXCHANGE_RATE_MAX_AGE_DAYS + 1)))
        // Il tasso incrociato vale quanto il piu' vecchio dei due: USD e' del 10 novembre.
        assertNull(table.indicative("USD", "THB", rateDay.plusDays(EXCHANGE_RATE_MAX_AGE_DAYS)))
    }
}
