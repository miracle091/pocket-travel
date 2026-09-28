package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningHoursTest {

    private fun rows(raw: String, today: Int = 0) = parseOpeningHours(raw, today)?.map { "${it.days} ${it.hours}" + if (it.includesToday) " *" else "" }

    @Test
    fun `giorni consecutivi con lo stesso orario in una riga, giorni non citati chiusi`() {
        assertEquals(
            listOf("lun–sab 12:00–15:00, 19:00–23:00 *", "dom chiuso"),
            rows("Mo-Sa 12:00-15:00,19:00-23:00; Su off"),
        )
        assertEquals(
            listOf("lun–ven 09:00–18:00", "sab 09:00–13:00 *", "dom chiuso"),
            rows("Mo-Fr 9:00-18:00; Sa 09:00-13:00", today = 5),
        )
    }

    @Test
    fun `regola per tutta la settimana corretta da quelle dopo, festivi a parte`() {
        assertEquals(
            listOf("lun–mar 08:00–20:00", "mer chiuso", "gio–dom 08:00–20:00", "festivi chiuso"),
            rows("08:00-20:00; We off; PH closed", today = 9),
        )
        assertEquals(listOf("lun–dom sempre aperto *"), rows("24/7"))
    }

    @Test
    fun `elenchi e intervalli che attraversano la domenica`() {
        assertEquals(
            listOf("lun 18:00–23:00", "mar–gio chiuso", "ven–dom 18:00–23:00"),
            rows("Fr-Mo 18:00-23:00", today = 9),
        )
        assertEquals(
            listOf("lun 10:00–12:00", "mar chiuso", "mer 10:00–12:00", "gio–dom chiuso"),
            rows("Mo,We 10:00-12:00", today = 9),
        )
    }

    @Test
    fun `sintassi che non interpreta torna null`() {
        assertNull(parseOpeningHours("Mo-Fr 08:00-12:00; Jan off", 0))
        assertNull(parseOpeningHours("sunrise-sunset", 0))
        assertNull(parseOpeningHours("Mo-Fr 08:00-18:00 \"su appuntamento\"", 0))
    }

    @Test
    fun `ripiego testuale in italiano`() {
        assertEquals("lun-ven 08:00-12:00\nJan chiuso", formatOpeningHours("Mo-Fr 08:00-12:00; Jan off"))
        assertEquals("sunrise-sunset", formatOpeningHours("sunrise-sunset"))
    }
}
