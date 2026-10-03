package com.pockettravel.feature.guide

import com.pockettravel.core.data.vaccination.SpecialEntryRow
import com.pockettravel.core.data.vaccination.TripPurpose
import com.pockettravel.core.data.vaccination.Vaccine
import com.pockettravel.core.data.vaccination.VaccinationData
import com.pockettravel.core.data.vaccination.VaccinationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Quando la schermata delle vaccinazioni chiede o no il calcolo al motore (dati sintetici, nessun repository). */
class VaccinationRecomputeTest {

    private val data = VaccinationData(
        special = listOf(
            SpecialEntryRow("sa", TripPurpose.HAJJ_UMRAH, Vaccine.MENACWY, 12, 10, 5, "", "", listOf("F7"), "2026-10-03"),
        ),
    )

    private fun state(departure: String?, destination: String?, hajj: Boolean = false) =
        VaccinationUiState(available = true, departure = departure, destination = destination, hajj = hajj)

    @Test
    fun `partenza uguale alla destinazione senza scopo non da' esito`() {
        assertNull(recomputeVaccinations(state("SA", "SA"), data).result)
    }

    @Test
    fun `residente in Arabia Saudita con l'Hajj vede il MenACWY`() {
        val result = recomputeVaccinations(state("SA", "SA", hajj = true), data).result
        assertNotNull(result)
        val item = result?.items?.firstOrNull { it.vaccine == Vaccine.MENACWY }
        assertEquals(VaccinationLevel.REQUIRED, item?.level)
    }

    @Test
    fun `l'Hajj fuori dall'Arabia Saudita non e' uno scopo e senza partenza non c'e' esito`() {
        assertNull(recomputeVaccinations(state("IT", "IT", hajj = true), data).result)
        assertNull(recomputeVaccinations(state(null, "SA", hajj = true), data).result)
    }

    @Test
    fun `senza dati non c'e' esito`() {
        assertNull(recomputeVaccinations(state("IT", "SA"), null).result)
    }
}
