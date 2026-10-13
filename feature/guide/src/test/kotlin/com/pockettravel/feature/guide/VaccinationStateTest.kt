package com.pockettravel.feature.guide

import com.pockettravel.core.data.vaccination.AgeNote
import com.pockettravel.core.data.vaccination.PolioCategory
import com.pockettravel.core.data.vaccination.PolioStatusRow
import com.pockettravel.core.data.vaccination.StopoverRule
import com.pockettravel.core.data.vaccination.Vaccine
import com.pockettravel.core.data.vaccination.VaccinationData
import com.pockettravel.core.data.vaccination.VaccinationItem
import com.pockettravel.core.data.vaccination.VaccinationLevel
import com.pockettravel.core.data.vaccination.VaccinationReason
import com.pockettravel.core.data.vaccination.YfEntryRow
import com.pockettravel.core.data.vaccination.YfRiskRow
import com.pockettravel.core.data.vaccination.YfRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Come lo stato della schermata delle vaccinazioni diventa un viaggio per il motore (dati sintetici, nessun repository). */
class VaccinationStateTest {

    private val verified = "2026-01-01"

    private fun polio(iso2: String, category: PolioCategory) = PolioStatusRow(iso2, category, "", emptyList(), verified)

    private fun yfEntry(iso2: String, rule: YfRule, stopover: StopoverRule = StopoverRule.NONE, minAgeMonths: Int? = null) =
        YfEntryRow(iso2, rule, minAgeMonths, stopover, emptySet(), false, "", "", emptyList(), verified)

    private val polioData = VaccinationData(
        polioStatus = listOf(
            polio("pk", PolioCategory.WPV1_CVDPV1_CVDPV3),
            polio("ng", PolioCategory.CVDPV2),
        ),
    )

    // Sbarco in Australia: serve il certificato solo a chi viene da un paese a rischio (qui il Brasile), e uno scalo
    // lo conta se dura piu' di 12 ore.
    private val riskData = VaccinationData(
        yfRisk = listOf(YfRiskRow("br", false, "", "", emptyList(), verified)),
        yfEntry = listOf(yfEntry("au", YfRule.FROM_RISK, StopoverRule.GT12H)),
    )

    private fun state(departure: String?, destination: String? = "KE") =
        VaccinationUiState(available = true, departure = departure, destination = destination)

    private fun VaccinationUiState.itemsOf(data: VaccinationData, vaccine: Vaccine, reason: VaccinationReason): List<VaccinationItem> =
        recomputeVaccinations(this, data).result?.items.orEmpty().filter { it.vaccine == vaccine && it.reason == reason }

    @Test
    fun `la polio in uscita interessa chi parte da un paese con poliovirus selvaggio, non da uno con cVDPV2`() {
        assertTrue(recomputeVaccinations(state("PK"), polioData).polioRelevant)
        assertTrue(recomputeVaccinations(state("pk"), polioData).polioRelevant)
        assertFalse(recomputeVaccinations(state("NG"), polioData).polioRelevant)
        assertFalse(recomputeVaccinations(state("IT"), polioData).polioRelevant)
        assertFalse(recomputeVaccinations(state(null), polioData).polioRelevant)
    }

    @Test
    fun `senza dati la polio non interessa e lo stato resta com'e'`() {
        val result = recomputeVaccinations(state("PK").copy(polioRelevant = true), null)

        assertFalse(result.polioRelevant)
        assertNull(result.result)
        assertEquals("PK", result.departure)
    }

    @Test
    fun `oltre quattro settimane da un paese a rischio polio l'obbligo in uscita compare`() {
        val stay = state("PK").copy(stayOverFourWeeks = true)

        assertEquals(1, stay.itemsOf(polioData, Vaccine.POLIO, VaccinationReason.POLIO_EXIT).size)
        assertEquals(VaccinationLevel.REQUIRED_EXIT, stay.itemsOf(polioData, Vaccine.POLIO, VaccinationReason.POLIO_EXIT).single().level)
        assertTrue(state("PK").itemsOf(polioData, Vaccine.POLIO, VaccinationReason.POLIO_EXIT).isEmpty())
    }

    @Test
    fun `la risposta sulle quattro settimane vale solo se la polio interessa dalla partenza`() {
        val stay = state("IT").copy(stayOverFourWeeks = true)

        assertTrue(stay.itemsOf(polioData, Vaccine.POLIO, VaccinationReason.POLIO_EXIT).isEmpty())
        // Da un paese con cVDPV2 la dose resta incoraggiata, mai obbligatoria.
        val encouraged = state("NG").copy(stayOverFourWeeks = true)
        assertTrue(encouraged.itemsOf(polioData, Vaccine.POLIO, VaccinationReason.POLIO_EXIT).isEmpty())
        assertEquals(1, encouraged.itemsOf(polioData, Vaccine.POLIO, VaccinationReason.POLIO_ENCOURAGED).size)
    }

    @Test
    fun `uno scalo oltre la soglia di ore senza uscire dall'aeroporto conta come provenienza`() {
        val withStopover = state("IT", "AU").copy(stopovers = listOf(StopoverInput(0, country = "BR", hours = 13)))

        val item = withStopover.itemsOf(riskData, Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_FROM_RISK).single()
        assertTrue(item.stopover)
        assertEquals(13, item.transitHours)
    }

    @Test
    fun `uno scalo breve non conta`() {
        val short = state("IT", "AU").copy(stopovers = listOf(StopoverInput(0, country = "BR", hours = 5)))

        assertTrue(short.itemsOf(riskData, Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_FROM_RISK).isEmpty())
    }

    @Test
    fun `piu' di 12 ore arriva al motore come durata non indicata, che conta per prudenza`() {
        val long = state("IT", "AU").copy(stopovers = listOf(StopoverInput(0, country = "BR", hours = 5, overTwelveHours = true)))

        val item = long.itemsOf(riskData, Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_FROM_RISK).single()
        assertTrue(item.stopover)
        assertNull(item.transitHours)
    }

    @Test
    fun `uscire dall'aeroporto fa dello scalo un soggiorno, anche breve`() {
        val left = state("IT", "AU").copy(stopovers = listOf(StopoverInput(0, country = "BR", hours = 1, leftAirport = true)))

        val item = left.itemsOf(riskData, Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_FROM_RISK).single()
        assertFalse(item.stopover)
    }

    @Test
    fun `uno scalo senza paese e' ignorato`() {
        val unset = state("IT", "AU").copy(stopovers = listOf(StopoverInput(0, country = null, hours = 20)))

        assertTrue(unset.itemsOf(riskData, Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_FROM_RISK).isEmpty())
    }

    @Test
    fun `un soggiorno recente in un paese a rischio conta come provenienza`() {
        val recent = state("IT", "AU").copy(recentCountries = listOf("BR"))

        val item = recent.itemsOf(riskData, Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_FROM_RISK).single()
        assertEquals("br", item.country?.lowercase())
        assertTrue(state("IT", "AU").itemsOf(riskData, Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_FROM_RISK).isEmpty())
    }

    @Test
    fun `l'eta' del bambino sotto la soglia toglie l'obbligo, e senza il bambino conta un adulto`() {
        val allEntries = VaccinationData(yfEntry = listOf(yfEntry("ke", YfRule.ALL, minAgeMonths = 9)))
        val yellowFever = { s: VaccinationUiState -> s.itemsOf(allEntries, Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_ALL).single() }

        val infant = yellowFever(state("IT").copy(childUnderOne = true, childMonths = 6))
        assertEquals(VaccinationLevel.CONSIDER, infant.level)
        assertEquals(AgeNote.BELOW_AGE, infant.ageNote)
        assertEquals(VaccinationLevel.REQUIRED, yellowFever(state("IT").copy(childUnderOne = true, childMonths = 10)).level)
        // Mesi non indicati: obbligo con la nota "da N mesi in su".
        assertEquals(AgeNote.FROM_AGE, yellowFever(state("IT").copy(childUnderOne = true, childMonths = null)).ageNote)
        // childMonths senza il bambino non conta.
        assertEquals(VaccinationLevel.REQUIRED, yellowFever(state("IT").copy(childUnderOne = false, childMonths = 3)).level)
    }

    @Test
    fun `senza partenza o destinazione non c'e' esito`() {
        assertNull(recomputeVaccinations(state(null), riskData).result)
        assertNull(recomputeVaccinations(state("IT", destination = null), riskData).result)
    }

    @Test
    fun `l'esito esiste per un viaggio valido e lo stato inserito dall'utente non cambia`() {
        val input = state("IT", "AU").copy(
            recentCountries = listOf("BR"),
            stopovers = listOf(StopoverInput(3, country = "SG", hours = 2)),
            childUnderOne = true,
            childMonths = 4,
            stayOverFourWeeks = true,
            hajj = true,
        )

        val output = recomputeVaccinations(input, riskData)

        assertNotNull(output.result)
        assertEquals(input.copy(polioRelevant = output.polioRelevant, result = output.result), output)
        assertEquals(input.recentCountries, output.recentCountries)
        assertEquals(input.stopovers, output.stopovers)
    }
}
