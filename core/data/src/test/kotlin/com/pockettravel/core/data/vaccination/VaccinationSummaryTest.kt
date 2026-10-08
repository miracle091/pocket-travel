package com.pockettravel.core.data.vaccination

import com.pockettravel.core.data.db.VaccMetaEntity
import com.pockettravel.core.data.db.VaccPolioEntryEntity
import com.pockettravel.core.data.db.VaccPolioStatusEntity
import com.pockettravel.core.data.db.VaccRecommendedEntity
import com.pockettravel.core.data.db.VaccSpecialEntity
import com.pockettravel.core.data.db.VaccYfEntryEntity
import com.pockettravel.core.data.db.VaccYfRiskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VaccinationSummaryTest {

    private val data = VaccinationData(
        yfRisk = listOf(YfRiskRow("ke", false, "", "", listOf("F6", "F7"), "2026-10-03")),
        yfEntry = listOf(
            YfEntryRow("eg", YfRule.FROM_RISK, 9, StopoverRule.GT12H, emptySet(), false, "", "", listOf("F6", "F7"), "2026-10-03"),
            YfEntryRow("it", YfRule.NONE, null, StopoverRule.NONE, emptySet(), false, "", "", listOf("F6"), "2026-10-03"),
        ),
        polioStatus = listOf(PolioStatusRow("af", PolioCategory.WPV1_CVDPV1_CVDPV3, "IHR EC 45", listOf("F3"), "2026-10-03")),
        recommended = listOf(
            RecommendedRow("eg", Vaccine.HEPA, RecommendedLevel.MOST, "", "", listOf("F7"), "2026-10-03"),
            RecommendedRow("eg", Vaccine.RABIES, RecommendedLevel.SOME, "", "", listOf("F7"), "2026-10-03"),
        ),
        meta = VaccinationMeta("IHR EC 45", "2026-10-03", "2026-10-03"),
    )

    private val today = LocalDate.parse("2026-10-03")

    @Test
    fun `riassunto italiano con obbligo, raccomandate, fonti e rimando a ambasciata e medico`() {
        val trip = Trip(departure = "ke", destination = "eg", transits = listOf(TripLeg("et", 3, false)))
        val text = evaluateVaccinations(trip, data, today).toSummaryText(trip, "it")
        println(text)
        assertTrue(text.contains("Kenya -> Etiopia -> Egitto"))
        assertTrue(text.contains("verificati il 2026-10-03"))
        assertTrue(text.contains("Certificati richiesti:"))
        assertTrue(text.contains("Febbre gialla: richiesto perché arrivi da Kenya; da 9 mesi di età"))
        assertTrue(text.contains("Raccomandate per la destinazione: Epatite A"))
        assertTrue(text.contains("Da valutare con il medico secondo il viaggio: Rabbia"))
        assertTrue(text.contains("Fonti: Travel.gc.ca, TravelHealthPro"))
        assertTrue(text.contains("ambasciata"))
        assertTrue(text.contains("centro di medicina dei viaggi"))
    }

    @Test
    fun `polio all'ingresso senza elenco di provenienze, a parte e non tra le voci da valutare`() {
        val unlisted = data.copy(
            polioEntry = listOf(
                PolioEntryRow("eg", null, emptySet(), PolioVaccine.BOPV_OR_IPV, PolioWindow.ANY, PolioApplies.ALL, "", "", listOf("F9"), "2026-10-08", originUnlisted = true),
            ),
        )
        val trip = Trip(departure = "it", destination = "eg")
        val result = evaluateVaccinations(trip, unlisted, today)
        val it = result.toSummaryText(trip, "it")
        assertTrue(it.contains("Poliomielite: Egitto può chiedere la prova della vaccinazione a chi arriva da paesi in cui circola la polio"))
        assertTrue(it.contains("Da valutare con il medico secondo il viaggio: Rabbia\n"))
        assertTrue(it.contains("France Diplomatie"))
        assertTrue(result.toSummaryText(trip, "en").contains("Polio: Egypt may ask for proof of vaccination"))
    }

    @Test
    fun `riassunto inglese`() {
        val trip = Trip(departure = "ke", destination = "eg")
        val text = evaluateVaccinations(trip, data, today).toSummaryText(trip, "en")
        println(text)
        assertTrue(text.contains("Vaccinations for the trip Kenya -> Egypt (data checked on 2026-10-03):"))
        assertTrue(text.contains("Yellow fever: required because you are arriving from Kenya; from age 9 months"))
        assertTrue(text.contains("Recommended for the destination: Hepatitis A"))
        assertTrue(text.contains("embassy"))
        assertTrue(text.contains("travel health clinic"))
    }

    @Test
    fun `senza obblighi dice nei nostri dati e mai che non serve nulla`() {
        val trip = Trip(departure = "it", destination = "it")
        val it = evaluateVaccinations(trip, data, today).toSummaryText(trip, "it")
        assertTrue(it.contains("Nei nostri dati nessun certificato risulta richiesto"))
        assertFalse(it.contains("Certificati richiesti"))
        assertFalse(it.contains("non serve"))
        val en = evaluateVaccinations(trip, data, today).toSummaryText(trip, "en")
        assertTrue(en.contains("In our data no certificate is listed as required"))
    }

    @Test
    fun `riassunto con uscita polio, bambino, avviso dati vecchi`() {
        val trip = Trip(departure = "af", destination = "eg", stayOverFourWeeksInDeparture = true, travellerAgeMonths = 5)
        val result = evaluateVaccinations(trip, data, today.plusDays(200))
        val text = result.toSummaryText(trip, "it")
        println(text)
        assertTrue(text.contains("Poliomielite (in uscita): una dose bOPV/IPV tra 4 settimane e 12 mesi prima di partire da Afghanistan"))
        assertTrue(text.contains("i dati sulla polio potrebbero non essere aggiornati (IHR EC 45)"))
        assertTrue(text.contains("Fonti: OMS (Polio IHR Emergency Committee)"))
    }

    @Test
    fun `i dati del repository si mappano e le righe con codici sconosciuti si saltano`() {
        val mapped = vaccinationDataFrom(
            yfRisk = listOf(
                VaccYfRiskEntity(iso2 = "br", scope = "PARTIAL", areasIt = "a", areasEn = "b", sources = "F6+F7", verified = "2026-10-03"),
                VaccYfRiskEntity(iso2 = "xx", scope = "NUOVO", areasIt = "", areasEn = "", sources = "", verified = "2026-10-03"),
            ),
            yfEntry = listOf(
                VaccYfEntryEntity(iso2 = "in", rule = "FROM_LIST", minAgeMonths = null, transit = "GT12H", fromList = "ke, UG", exitRequired = true, noteIt = "", noteEn = "", sources = "F7", verified = "2026-10-03"),
                VaccYfEntryEntity(iso2 = "yy", rule = "DOMANI", minAgeMonths = 9, transit = "NONE", fromList = "", exitRequired = false, noteIt = "", noteEn = "", sources = "", verified = "2026-10-03"),
            ),
            polioStatus = listOf(VaccPolioStatusEntity(iso2 = "af", category = "WPV1_CVDPV1_CVDPV3", statement = "s", sources = "F3", verified = "2026-10-03")),
            polioEntry = listOf(
                VaccPolioEntryEntity(iso2 = "sa", origin = "CAT:CVDPV2", vaccine = "BOPV_OR_IPV", timeWindow = "ANY", applies = "HAJJ_UMRAH", noteIt = "", noteEn = "", sources = "F7", verified = "2026-10-03"),
                VaccPolioEntryEntity(iso2 = "eg", origin = "pk,af", vaccine = "IPV", timeWindow = "4W_12M", applies = "ALL", noteIt = "", noteEn = "", sources = "F7", verified = "2026-10-03"),
                VaccPolioEntryEntity(iso2 = "eg", origin = "CAT:BOH", vaccine = "IPV", timeWindow = "4W_12M", applies = "ALL", noteIt = "", noteEn = "", sources = "F7", verified = "2026-10-03"),
            ),
            special = listOf(VaccSpecialEntity(iso2 = "sa", purpose = "HAJJ_UMRAH", vaccine = "MENACWY", minAgeMonths = 12, minDaysBefore = 10, validityYears = 5, noteIt = "", noteEn = "", sources = "F7", verified = "2026-10-03")),
            recommended = listOf(VaccRecommendedEntity(iso2 = "ke", vaccine = "YF", level = "MOST", conditionIt = "", conditionEn = "", sources = "F7", verified = "2026-10-03")),
            meta = listOf(VaccMetaEntity("last_review", "2026-10-03"), VaccMetaEntity("polio_statement", "s")),
        )
        assertEquals(listOf("br"), mapped.yfRisk.map { it.iso2 })
        assertTrue(mapped.yfRisk.single().partial)
        assertEquals(listOf("F6", "F7"), mapped.yfRisk.single().sources)
        assertEquals(listOf("in"), mapped.yfEntry.map { it.iso2 })
        assertEquals(setOf("ke", "ug"), mapped.yfEntry.single().fromList)
        assertEquals(StopoverRule.GT12H, mapped.yfEntry.single().transit)
        assertEquals(PolioCategory.CVDPV2, mapped.polioEntry.first().originCategory)
        assertEquals(setOf("pk", "af"), mapped.polioEntry[1].originCountries)
        assertEquals(2, mapped.polioEntry.size)
        assertEquals(Vaccine.MENACWY, mapped.special.single().vaccine)
        assertEquals(Vaccine.YELLOW_FEVER, mapped.recommended.single().vaccine)
        assertEquals("2026-10-03", mapped.meta.lastReview)
        assertNull(mapped.meta.polioVerifiedAt)
        assertFalse(mapped.isEmpty)
    }

    @Test
    fun `tabelle vuote danno dati vuoti`() {
        assertTrue(vaccinationDataFrom(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()).isEmpty)
    }
}
