package com.pockettravel.core.data.vaccination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VaccinationModelTest {

    @Test
    fun `le sigle delle fonti note hanno un nome leggibile, le altre restano com'e'`() {
        assertEquals("OMS (Polio IHR Emergency Committee)", vaccinationSourceName("F3"))
        assertEquals("Travel.gc.ca", vaccinationSourceName("F6"))
        assertEquals("TravelHealthPro", vaccinationSourceName("F7"))
        assertEquals("F99", vaccinationSourceName("F99"))
        assertEquals("", vaccinationSourceName(""))
        // Le sigle sono esatte: minuscolo non corrisponde.
        assertEquals("f6", vaccinationSourceName("f6"))
    }

    @Test
    fun `parseIsoDate legge le date ISO e scarta tutto il resto senza eccezioni`() {
        assertEquals(LocalDate.of(2026, 10, 3), parseIsoDate("2026-10-03"))
        assertNull(parseIsoDate(null))
        assertNull(parseIsoDate(""))
        assertNull(parseIsoDate("03/10/2026"))
        assertNull(parseIsoDate("2026-13-01"))
        assertNull(parseIsoDate("2026-02-30"))
        assertNull(parseIsoDate(" 2026-10-03"))
    }

    @Test
    fun `il 29 febbraio si legge solo negli anni bisestili`() {
        assertEquals(LocalDate.of(2028, 2, 29), parseIsoDate("2028-02-29"))
        assertNull(parseIsoDate("2027-02-29"))
    }

    @Test
    fun `le soglie dei transiti crescono e NONE e ANY non ne hanno`() {
        assertNull(TransitRule.NONE.hours)
        assertNull(TransitRule.ANY.hours)
        assertEquals(listOf(4, 12, 24), listOf(TransitRule.GT4H, TransitRule.GT12H, TransitRule.GT24H).map { it.hours })
    }

    @Test
    fun `VaccinationData e' vuoto solo se lo sono tutte le tabelle, la meta non conta`() {
        assertTrue(VaccinationData().isEmpty)
        assertTrue(VaccinationData(meta = VaccinationMeta("s", "2026-10-03", "2026-10-03")).isEmpty)

        val risk = YfRiskRow("ke", false, "", "", emptyList(), "2026-10-03")
        assertFalse(VaccinationData(yfRisk = listOf(risk)).isEmpty)
        assertFalse(
            VaccinationData(
                recommended = listOf(RecommendedRow("eg", Vaccine.HEPA, RecommendedLevel.MOST, "", "", emptyList(), "2026-10-03")),
            ).isEmpty,
        )
        assertFalse(
            VaccinationData(
                special = listOf(SpecialEntryRow("sa", TripPurpose.HAJJ_UMRAH, Vaccine.MENACWY, null, null, null, "", "", emptyList(), "2026-10-03")),
            ).isEmpty,
        )
    }
}
