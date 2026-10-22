package com.pockettravel.feature.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryEmptiedTest {
    @Test
    fun `emette quando il conteggio scende a zero con una conversazione mostrata`() = runTest {
        val emissions = historyEmptied(flowOf(2, 0)) { true }.toList()
        assertEquals(1, emissions.size)
    }

    @Test
    fun `non emette per lo zero iniziale`() = runTest {
        assertEquals(emptyList<Unit>(), historyEmptied(flowOf(0, 0)) { true }.toList())
    }

    @Test
    fun `non emette senza conversazione salvata sulla schermata`() = runTest {
        assertEquals(emptyList<Unit>(), historyEmptied(flowOf(3, 0)) { false }.toList())
    }

    @Test
    fun `non emette se il conteggio cala ma resta sopra zero`() = runTest {
        assertEquals(emptyList<Unit>(), historyEmptied(flowOf(3, 1, 2)) { true }.toList())
    }

    @Test
    fun `una conversazione appena cominciata non ancora scritta non fa emettere`() = runTest {
        // Conteggio 0 -> 0 -> 1: la riga arriva dopo la conversazione mostrata.
        assertEquals(emptyList<Unit>(), historyEmptied(flowOf(0, 0, 1)) { true }.toList())
    }
}
