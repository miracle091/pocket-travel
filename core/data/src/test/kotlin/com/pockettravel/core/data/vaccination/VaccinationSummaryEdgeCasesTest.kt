package com.pockettravel.core.data.vaccination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** toSummaryText su risultati costruiti a mano, per controllare ogni riga senza passare dal motore. */
class VaccinationSummaryEdgeCasesTest {

    private fun result(
        vararg items: VaccinationItem,
        checkedOn: String? = null,
        stale: Boolean = false,
        statement: String? = null,
    ) = VaccinationResult(items.toList(), noCertificateFound = true, checkedOn = checkedOn, polioDataStale = stale, polioStatement = statement)

    private val routine = VaccinationItem(Vaccine.ROUTINE, VaccinationLevel.RECOMMENDED, VaccinationReason.ROUTINE, country = "it")

    private fun yfEntry(country: String = "ke", level: VaccinationLevel = VaccinationLevel.REQUIRED) =
        VaccinationItem(Vaccine.YELLOW_FEVER, level, VaccinationReason.YF_ENTRY_FROM_RISK, country = country)

    private val trip = Trip(departure = "it", destination = "fr")

    private fun lines(text: String) = text.lines()

    @Test
    fun `una lingua diversa da en da' l'italiano, anche se scritta in maiuscolo`() {
        val r = result(routine)
        val italian = r.toSummaryText(trip, "it")
        assertEquals(italian, r.toSummaryText(trip, "fr"))
        assertEquals(italian, r.toSummaryText(trip, "EN"))
        assertEquals(italian, r.toSummaryText(trip))
        assertTrue(italian.startsWith("Vaccinazioni per il viaggio Italia -> Francia:"))
    }

    @Test
    fun `l'intestazione riporta la data solo se nota`() {
        assertEquals("Vaccinazioni per il viaggio Italia -> Francia:", lines(result(routine).toSummaryText(trip, "it")).first())
        assertEquals(
            "Vaccinazioni per il viaggio Italia -> Francia (dati verificati il 2026-10-03):",
            lines(result(routine, checkedOn = "2026-10-03").toSummaryText(trip, "it")).first(),
        )
        assertEquals(
            "Vaccinations for the trip Italy -> France (data checked on 2026-10-03):",
            lines(result(routine, checkedOn = "2026-10-03").toSummaryText(trip, "en")).first(),
        )
    }

    @Test
    fun `il percorso elenca partenza, scali e destinazione, con il codice maiuscolo se il paese e' ignoto`() {
        val long = Trip(departure = " IT ", transits = listOf(TripLeg("ke", 3, false), TripLeg("et", null, true)), destination = "xyz")
        assertTrue(result(routine).toSummaryText(long, "it").startsWith("Vaccinazioni per il viaggio Italia -> Kenya -> Etiopia -> XYZ:"))
        assertTrue(result(routine).toSummaryText(long, "en").startsWith("Vaccinations for the trip Italy -> Kenya -> Ethiopia -> XYZ:"))
    }

    @Test
    fun `senza certificati richiesti ne' altre voci restano la frase prudente, la routine e il rimando`() {
        val text = result(routine).toSummaryText(trip, "it")
        assertTrue(text.contains("Nei nostri dati nessun certificato risulta richiesto"))
        assertFalse(text.contains("Raccomandate per la destinazione"))
        assertFalse(text.contains("Da valutare"))
        assertFalse(text.contains("Fonti:"))
        assertTrue(text.contains("Vaccinazioni di routine"))
        assertTrue(lines(text).last().startsWith("Verifica sempre i requisiti con l'ambasciata"))
    }

    @Test
    fun `senza la voce di routine non c'e' la riga di routine`() {
        assertFalse(result(yfEntry()).toSummaryText(trip, "it").contains("routine"))
    }

    @Test
    fun `l'uscita e' segnata nel nome della voce`() {
        val exit = VaccinationItem(Vaccine.YELLOW_FEVER, VaccinationLevel.REQUIRED_EXIT, VaccinationReason.YF_EXIT, country = "ng")
        assertTrue(result(exit).toSummaryText(trip, "it").contains("- Febbre gialla (in uscita): richiesto all'uscita da Nigeria"))
        assertTrue(result(exit).toSummaryText(trip, "en").contains("- Yellow fever (on exit): required when leaving Nigeria"))
    }

    @Test
    fun `la febbre gialla per tutti non cita il paese di provenienza`() {
        val all = VaccinationItem(Vaccine.YELLOW_FEVER, VaccinationLevel.REQUIRED, VaccinationReason.YF_ENTRY_ALL)
        assertTrue(result(all).toSummaryText(trip, "it").contains("- Febbre gialla: richiesto a tutti i viaggiatori in arrivo\n"))
        assertTrue(result(all).toSummaryText(trip, "en").contains("- Yellow fever: required of all arriving travellers\n"))
    }

    @Test
    fun `lo scalo si descrive con le ore se note, altrimenti senza`() {
        val withHours = yfEntry().copy(transitHours = 14, stopover = true)
        val withoutHours = yfEntry().copy(stopover = true)
        assertTrue(result(withHours).toSummaryText(trip, "it").contains("richiesto per lo scalo di 14 ore in Kenya"))
        assertTrue(result(withHours).toSummaryText(trip, "en").contains("required because of the 14-hour stopover in Kenya"))
        assertTrue(result(withoutHours).toSummaryText(trip, "it").contains("richiesto per lo scalo in Kenya"))
        assertTrue(result(withoutHours).toSummaryText(trip, "en").contains("required because of the stopover in Kenya"))
    }

    @Test
    fun `i dettagli del requisito dell'Hajj seguono l'ordine giorni, validita, eta, nota`() {
        val hajj = VaccinationItem(
            Vaccine.MENACWY, VaccinationLevel.REQUIRED, VaccinationReason.HAJJ_UMRAH, country = "sa",
            minAgeMonths = 12, minDaysBefore = 10, validityYears = 5, noteIt = "  dose recente  ", noteEn = "recent dose",
        )
        assertTrue(
            result(hajj).toSummaryText(trip, "it")
                .contains("- Meningococco ACWY: obbligatorio per Hajj e Umrah; almeno 10 giorni prima; validità 5 anni; da 12 mesi di età; dose recente\n"),
        )
        assertTrue(
            result(hajj).toSummaryText(trip, "en")
                .contains("- Meningococcal ACWY: mandatory for Hajj and Umrah; at least 10 days before; valid for 5 years; from age 12 months; recent dose\n"),
        )
    }

    @Test
    fun `una nota vuota o di soli spazi non aggiunge nulla`() {
        val item = yfEntry().copy(noteIt = "   ")
        assertTrue(result(item).toSummaryText(trip, "it").contains("- Febbre gialla: richiesto perché arrivi da Kenya\n"))
    }

    @Test
    fun `le voci sotto l'eta' minima hanno un blocco proprio e non tra le da valutare`() {
        val below = yfEntry(level = VaccinationLevel.CONSIDER).copy(minAgeMonths = 9, ageNote = AgeNote.BELOW_AGE)
        val text = result(below, routine).toSummaryText(trip, "it")
        assertTrue(text.contains("Nei nostri dati nessun certificato risulta richiesto"))
        assertTrue(text.contains("Non richiesti per l'età indicata:\n- Febbre gialla: richiesto perché arrivi da Kenya; non richiesto sotto i 9 mesi"))
        assertFalse(text.contains("Da valutare con il medico"))
        assertTrue(result(below).toSummaryText(trip, "en").contains("Not required at the age given:\n- Yellow fever: required because you are arriving from Kenya; not required under 9 months"))
    }

    @Test
    fun `la soglia di eta' senza eta' indicata dice da quanti mesi`() {
        val fromAge = yfEntry().copy(minAgeMonths = 9, ageNote = AgeNote.FROM_AGE)
        assertTrue(result(fromAge).toSummaryText(trip, "it").contains("richiesto perché arrivi da Kenya; da 9 mesi di età"))
        assertTrue(result(fromAge).toSummaryText(trip, "en").contains("required because you are arriving from Kenya; from age 9 months"))
    }

    @Test
    fun `le dosi di polio dicono vaccino e finestra`() {
        val encouraged = VaccinationItem(
            Vaccine.POLIO, VaccinationLevel.ENCOURAGED, VaccinationReason.POLIO_ENCOURAGED, country = "ng",
            polioDose = PolioDose(PolioVaccine.BOPV_OR_IPV, PolioWindow.W4_12M),
        )
        val italian = result(encouraged).toSummaryText(trip, "it")
        assertTrue(italian.contains("Incoraggiate (non obbligatorie):\n- Poliomielite: da Nigeria è incoraggiata una dose bOPV/IPV tra 4 settimane e 12 mesi prima di partire"))
        val en = result(encouraged).toSummaryText(trip, "en")
        assertTrue(en.contains("Encouraged (not mandatory):\n- Polio: when leaving Nigeria one dose bOPV/IPV between 4 weeks and 12 months is encouraged"))

        val entry = VaccinationItem(
            Vaccine.POLIO, VaccinationLevel.REQUIRED, VaccinationReason.POLIO_ENTRY, country = "pk",
            polioDose = PolioDose(PolioVaccine.IPV, PolioWindow.ANY, PolioApplies.ALL),
        )
        assertTrue(result(entry).toSummaryText(trip, "it").contains("richiesto all'ingresso per chi arriva da Pakistan (IPV (certificata))"))
        assertTrue(result(entry).toSummaryText(trip, "en").contains("required on entry for those arriving from Pakistan (IPV (certified))"))
        val noDose = entry.copy(polioDose = null)
        assertTrue(result(noDose).toSummaryText(trip, "it").contains("(bOPV/IPV (certificata))"))
    }

    @Test
    fun `le raccomandate e le da valutare seguono l'ordine delle voci e segnalano le aree parziali`() {
        val partial = VaccinationItem(
            Vaccine.YELLOW_FEVER, VaccinationLevel.RECOMMENDED, VaccinationReason.YF_DESTINATION_RISK, country = "br", partialArea = true,
        )
        val hepa = VaccinationItem(Vaccine.HEPA, VaccinationLevel.RECOMMENDED, VaccinationReason.DESTINATION_MOST, country = "br")
        val rabies = VaccinationItem(Vaccine.RABIES, VaccinationLevel.CONSIDER, VaccinationReason.DESTINATION_SOME, country = "br")
        val dengue = VaccinationItem(Vaccine.DENGUE, VaccinationLevel.CONSIDER, VaccinationReason.DESTINATION_SOME, country = "br")
        val text = result(partial, hepa, rabies, dengue, routine).toSummaryText(trip, "it")
        assertTrue(text.contains("Raccomandate per la destinazione: Febbre gialla (solo in alcune aree), Epatite A\n"))
        assertTrue(text.contains("Da valutare con il medico secondo il viaggio: Rabbia, Dengue\n"))
        val en = result(partial, hepa, rabies, dengue, routine).toSummaryText(trip, "en")
        assertTrue(en.contains("Recommended for the destination: Yellow fever (some areas only), Hepatitis A\n"))
        assertTrue(en.contains("To discuss with a doctor depending on the trip: Rabies, Dengue\n"))
    }

    @Test
    fun `l'avviso sui dati polio cita la dichiarazione solo se c'e'`() {
        assertTrue(
            result(routine, stale = true).toSummaryText(trip, "it")
                .contains("Attenzione: i dati sulla polio potrebbero non essere aggiornati.\n"),
        )
        assertTrue(
            result(routine, stale = true, statement = "IHR EC 45").toSummaryText(trip, "en")
                .contains("Warning: the polio data may be out of date (IHR EC 45).\n"),
        )
        assertFalse(result(routine, stale = false, statement = "IHR EC 45").toSummaryText(trip, "it").contains("Attenzione"))
    }

    @Test
    fun `le fonti sono senza doppioni, nell'ordine in cui compaiono, e le sigle ignote restano`() {
        val a = yfEntry().copy(sources = listOf("F3", "X9"))
        val b = yfEntry("et").copy(sources = listOf("F3", "F7"))
        val text = result(a, b).toSummaryText(trip, "it")
        assertTrue(text.contains("Fonti: OMS (Polio IHR Emergency Committee), X9, TravelHealthPro\n"))
        assertTrue(result(a, b).toSummaryText(trip, "en").contains("Sources: OMS (Polio IHR Emergency Committee), X9, TravelHealthPro\n"))
    }

    @Test
    fun `l'ultima riga rimanda sempre ad ambasciata e centro di medicina dei viaggi`() {
        listOf(result(routine), result(yfEntry(), routine, stale = true)).forEach { r ->
            assertTrue(lines(r.toSummaryText(trip, "it")).last().contains("ambasciata"))
            assertTrue(lines(r.toSummaryText(trip, "en")).last().contains("travel health clinic"))
        }
    }
}
