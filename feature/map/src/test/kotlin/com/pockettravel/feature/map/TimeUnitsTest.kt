package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TimeUnitsTest {
    @Test
    fun `il minuto dall'epoca e la data e ora si convertono nei due sensi`() {
        val time = LocalDateTime.of(2026, 11, 24, 18, 30)
        assertEquals(time, minuteToDateTime(dateTimeToMinute(time)))
        assertEquals(0L, dateTimeToMinute(LocalDateTime.of(1970, 1, 1, 0, 0)))
        assertEquals(LocalDateTime.of(1970, 1, 1, 1, 1), minuteToDateTime(61))
    }

    @Test
    fun `i secondi si scartano`() {
        assertEquals(1L, dateTimeToMinute(LocalDateTime.of(1970, 1, 1, 0, 1, 59)))
    }
}
