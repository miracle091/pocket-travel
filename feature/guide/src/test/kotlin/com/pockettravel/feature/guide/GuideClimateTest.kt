package com.pockettravel.feature.guide

import com.pockettravel.core.data.CityClimate
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideClimateTest {

    private fun month(max: Double? = null, min: Double? = null, mm: Double? = null, days: Double? = null) =
        CityClimate(10, max, min, mm, days, "https://it.wikipedia.org/wiki/Roma")

    @Test
    fun `le voci del mese sono arrotondate e in ordine`() {
        val facts = climateFacts(month(max = 21.6, min = 12.5, mm = 112.7, days = 7.6))

        assertEquals(
            listOf(
                ClimateFactValue(ClimateFact.HIGH, 22),
                ClimateFactValue(ClimateFact.LOW, 13),
                ClimateFactValue(ClimateFact.RAIN_AND_DAYS, 113, 8),
            ),
            facts,
        )
    }

    @Test
    fun `meno di mezzo giorno di pioggia non diventa "in 0 giorni"`() {
        assertEquals(listOf(ClimateFactValue(ClimateFact.RAIN, 3)), climateFacts(month(mm = 3.0, days = 0.4)))
    }

    @Test
    fun `le voci assenti non compaiono`() {
        assertEquals(listOf(ClimateFactValue(ClimateFact.HIGH, 5)), climateFacts(month(max = 5.0)))
        assertEquals(listOf(ClimateFactValue(ClimateFact.RAIN, 40)), climateFacts(month(mm = 40.0)))
        assertEquals(listOf(ClimateFactValue(ClimateFact.DAYS, 6)), climateFacts(month(days = 6.0)))
        assertEquals(emptyList<ClimateFactValue>(), climateFacts(month()))
    }

    @Test
    fun `la tabella ha solo le colonne con almeno un valore`() {
        val months = listOf(month(max = 20.0, min = 10.0), month(max = 22.0, days = 5.0))

        assertEquals(listOf(ClimateColumn.HIGH, ClimateColumn.LOW, ClimateColumn.DAYS), climateColumns(months))
        assertEquals(emptyList<ClimateColumn>(), climateColumns(listOf(month())))
    }
}
