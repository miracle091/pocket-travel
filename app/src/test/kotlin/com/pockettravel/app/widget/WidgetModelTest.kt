package com.pockettravel.app.widget

import com.pockettravel.core.data.EmbassyEntry
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.MissionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetModelTest {

    private fun embassy(name: String, phone: String?) = EmbassyEntry(name, MissionKind.EMBASSY, phone, null, null, null)

    @Test
    fun `un numero unico uguale a tutti gli altri e' una riga sola`() {
        val lines = EmergencyNumbers(general = "112", police = "112", ambulance = "112", fire = "112").widgetLines()

        assertEquals(listOf(EmergencyLine(listOf(EmergencyService.SINGLE), "112")), lines)
    }

    @Test
    fun `numeri diversi hanno una riga ciascuno`() {
        val lines = EmergencyNumbers(general = null, police = "113", ambulance = "118", fire = "115").widgetLines()

        assertEquals(
            listOf(
                EmergencyLine(listOf(EmergencyService.POLICE), "113"),
                EmergencyLine(listOf(EmergencyService.AMBULANCE), "118"),
                EmergencyLine(listOf(EmergencyService.FIRE), "115"),
            ),
            lines,
        )
    }

    @Test
    fun `un numero condiviso compare una volta con tutti i suoi servizi`() {
        val lines = EmergencyNumbers(general = "112", police = "110", ambulance = "119", fire = "119").widgetLines()

        assertEquals(
            listOf(
                EmergencyLine(listOf(EmergencyService.GENERAL), "112"),
                EmergencyLine(listOf(EmergencyService.POLICE), "110"),
                EmergencyLine(listOf(EmergencyService.AMBULANCE, EmergencyService.FIRE), "119"),
            ),
            lines,
        )
    }

    @Test
    fun `il numero unico uguale alla polizia ma non agli altri resta tra i servizi`() {
        val lines = EmergencyNumbers(general = "112", police = "112", ambulance = "118", fire = "115").widgetLines()

        assertEquals(listOf("112", "118", "115"), lines.map { it.number })
        assertEquals(listOf(EmergencyService.GENERAL, EmergencyService.POLICE), lines.first().services)
    }

    @Test
    fun `l'ambasciata e' la prima con un telefono`() {
        val entries = listOf(embassy("Senza telefono", null), embassy("Vuota", " "), embassy("Ambasciata", "+81 3 1234"), embassy("Altra", "+81 3 9999"))

        assertEquals(EmbassyLine("Ambasciata", "+81 3 1234"), entries.widgetEmbassy())
    }

    @Test
    fun `senza rappresentanze con telefono non c'e' ambasciata`() {
        assertNull(emptyList<EmbassyEntry>().widgetEmbassy())
        assertNull(listOf(embassy("Senza telefono", null)).widgetEmbassy())
    }

    @Test
    fun `l'Assistente compare solo con una regione e un motore disponibile`() {
        assertEquals(listOf(Shortcut.ASSISTANT, Shortcut.NAVIGATOR, Shortcut.DOCUMENTS), shortcutsFor("toscana", aiAvailable = true))
        assertEquals(listOf(Shortcut.NAVIGATOR, Shortcut.DOCUMENTS), shortcutsFor("toscana", aiAvailable = false))
    }

    @Test
    fun `senza regioni restano Navigatore e Documenti`() {
        assertEquals(listOf(Shortcut.NAVIGATOR, Shortcut.DOCUMENTS), shortcutsFor(null, aiAvailable = true))
    }

    @Test
    fun `almeno una riga di numeri sta anche nel widget piu' basso`() {
        assertEquals(1, emergencyRowCapacity(40))
        assertEquals(1, emergencyRowCapacity(0))
    }

    @Test
    fun `piu' altezza, piu' righe`() {
        assertEquals(true, emergencyRowCapacity(160) > emergencyRowCapacity(80))
    }

    @Test
    fun `l'ambasciata prende un posto solo se avanza`() {
        val lines = listOf(EmergencyLine(listOf(EmergencyService.POLICE), "113"), EmergencyLine(listOf(EmergencyService.FIRE), "115"))
        val embassy = EmbassyLine("Ambasciata", "+81 3 1234")

        assertEquals(lines.take(1) to null, emergencyRows(lines, embassy, capacity = 1))
        assertEquals(lines to null, emergencyRows(lines, embassy, capacity = 2))
        assertEquals(lines to embassy, emergencyRows(lines, embassy, capacity = 3))
        assertEquals(emptyList<EmergencyLine>() to embassy, emergencyRows(emptyList(), embassy, capacity = 1))
    }
}
