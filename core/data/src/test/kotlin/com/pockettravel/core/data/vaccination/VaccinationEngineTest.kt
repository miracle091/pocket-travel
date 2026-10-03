package com.pockettravel.core.data.vaccination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Scenari del motore su dati sintetici (non i TSV reali), cosi' un aggiornamento dei dati non rompe i test. */
class VaccinationEngineTest {

    private val today = LocalDate.parse("2026-10-03")

    private fun yf(
        iso2: String,
        rule: YfRule,
        minAge: Int? = 9,
        transit: TransitRule = TransitRule.NONE,
        fromList: Set<String> = emptySet(),
        exit: Boolean = false,
    ) = YfEntryRow(iso2, rule, minAge, transit, fromList, exit, "", "", listOf("F6", "F7"), "2026-10-03")

    private fun risk(iso2: String, partial: Boolean = false) =
        YfRiskRow(iso2, partial, if (partial) "alcune aree" else "", if (partial) "some areas" else "", listOf("F7"), "2026-10-03")

    private fun polioStatus(iso2: String, category: PolioCategory) =
        PolioStatusRow(iso2, category, "IHR EC 45", listOf("F3"), "2026-10-03")

    private val data = VaccinationData(
        yfRisk = listOf(risk("ke"), risk("br", partial = true), risk("sn"), risk("et")),
        yfEntry = listOf(
            yf("eg", YfRule.FROM_RISK, transit = TransitRule.GT12H),
            yf("ao", YfRule.ALL),
            yf("sn", YfRule.FROM_RISK, transit = TransitRule.ANY),
            yf("np", YfRule.FROM_RISK, transit = TransitRule.GT4H),
            yf("in", YfRule.FROM_LIST, transit = TransitRule.GT12H, fromList = setOf("ke", "ug")),
            yf("it", YfRule.NONE, minAge = null),
            yf("fr", YfRule.NONE, minAge = null),
            yf("tz", YfRule.FROM_RISK, minAge = null, transit = TransitRule.GT24H),
            yf("ng", YfRule.NONE, minAge = 9, exit = true),
        ),
        polioStatus = listOf(
            polioStatus("af", PolioCategory.WPV1_CVDPV1_CVDPV3),
            polioStatus("ng", PolioCategory.CVDPV2),
            polioStatus("cm", PolioCategory.WPV1_CVDPV1_CVDPV3),
            polioStatus("cm", PolioCategory.CVDPV2),
            polioStatus("ke", PolioCategory.PREVIOUSLY_INFECTED),
        ),
        polioEntry = listOf(
            PolioEntryRow("sa", PolioCategory.WPV1_CVDPV1_CVDPV3, emptySet(), PolioVaccine.BOPV_OR_IPV, PolioWindow.ANY, PolioApplies.HAJJ_UMRAH, "n", "n", listOf("F7"), "2026-10-03"),
            PolioEntryRow("sa", PolioCategory.CVDPV2, emptySet(), PolioVaccine.BOPV_OR_IPV, PolioWindow.ANY, PolioApplies.HAJJ_UMRAH, "n", "n", listOf("F7"), "2026-10-03"),
            PolioEntryRow("eg", null, setOf("pk", "af"), PolioVaccine.BOPV_OR_IPV, PolioWindow.W4_12M, PolioApplies.ALL, "", "", listOf("F7"), "2026-10-03"),
        ),
        special = listOf(
            SpecialEntryRow("sa", TripPurpose.HAJJ_UMRAH, Vaccine.MENACWY, 12, 10, 5, "", "", listOf("F7"), "2026-10-03"),
        ),
        recommended = listOf(
            RecommendedRow("ke", Vaccine.HEPA, RecommendedLevel.MOST, "cibo e acqua", "food and water", listOf("F7"), "2026-10-03"),
            RecommendedRow("ke", Vaccine.RABIES, RecommendedLevel.SOME, "animali", "animals", listOf("F7"), "2026-10-03"),
            RecommendedRow("ke", Vaccine.YELLOW_FEVER, RecommendedLevel.MOST, "", "", listOf("F7"), "2026-10-03"),
            RecommendedRow("sa", Vaccine.MENACWY, RecommendedLevel.SOME, "", "", listOf("F7"), "2026-10-03"),
        ),
        meta = VaccinationMeta("IHR EC 45, 2026-08-21", "2026-10-03", "2026-10-03"),
    )

    private fun evaluate(trip: Trip, d: VaccinationData = data, now: LocalDate = today) = evaluateVaccinations(trip, d, now)

    private fun VaccinationResult.item(vaccine: Vaccine, reason: VaccinationReason? = null) =
        items.firstOrNull { it.vaccine == vaccine && (reason == null || it.reason == reason) }

    private fun VaccinationResult.has(vaccine: Vaccine, level: VaccinationLevel) = items.any { it.vaccine == vaccine && it.level == level }

    @Test
    fun `partenza a rischio verso destinazione FROM_RISK e' un obbligo`() {
        val result = evaluate(Trip(departure = "KE", destination = "eg"))
        val item = result.item(Vaccine.YELLOW_FEVER)!!
        assertEquals(VaccinationLevel.REQUIRED, item.level)
        assertEquals(VaccinationReason.YF_ENTRY_FROM_RISK, item.reason)
        assertEquals("ke", item.country)
        assertEquals(AgeNote.FROM_AGE, item.ageNote)
        assertEquals(9, item.minAgeMonths)
        assertFalse(result.noCertificateFound)
    }

    @Test
    fun `partenza non a rischio verso FROM_RISK non da' obbligo`() {
        val result = evaluate(Trip(departure = "it", destination = "eg"))
        assertNull(result.item(Vaccine.YELLOW_FEVER))
        assertTrue(result.noCertificateFound)
    }

    @Test
    fun `destinazione ALL e' un obbligo anche partendo dall'Italia`() {
        val item = evaluate(Trip(departure = "it", destination = "ao")).item(Vaccine.YELLOW_FEVER)!!
        assertEquals(VaccinationLevel.REQUIRED, item.level)
        assertEquals(VaccinationReason.YF_ENTRY_ALL, item.reason)
        assertNull(item.country)
    }

    @Test
    fun `transito di 13 ore con GT12H e' un obbligo, 11 ore no`() {
        val over = evaluate(Trip(departure = "it", transits = listOf(TripLeg("ke", 13, false)), destination = "eg"))
        val item = over.item(Vaccine.YELLOW_FEVER)!!
        assertEquals(VaccinationLevel.REQUIRED, item.level)
        assertEquals("ke", item.country)
        assertEquals(13, item.transitHours)

        val under = evaluate(Trip(departure = "it", transits = listOf(TripLeg("ke", 11, false)), destination = "eg"))
        assertNull(under.item(Vaccine.YELLOW_FEVER))
        val exactly = evaluate(Trip(departure = "it", transits = listOf(TripLeg("ke", 12, false)), destination = "eg"))
        assertNull("la soglia e' esclusa: 12 ore non bastano", exactly.item(Vaccine.YELLOW_FEVER))
    }

    @Test
    fun `soglie di transito ANY GT4H e GT24H`() {
        fun trip(destination: String, hours: Int?) = Trip(departure = "it", transits = listOf(TripLeg("ke", hours, false)), destination = destination)
        assertEquals(VaccinationLevel.REQUIRED, evaluate(trip("sn", 2)).item(Vaccine.YELLOW_FEVER)!!.level)
        assertNull(evaluate(trip("np", 4)).item(Vaccine.YELLOW_FEVER))
        assertEquals(VaccinationLevel.REQUIRED, evaluate(trip("np", 5)).item(Vaccine.YELLOW_FEVER)!!.level)
        assertNull(evaluate(trip("tz", 24)).item(Vaccine.YELLOW_FEVER))
        assertEquals(VaccinationLevel.REQUIRED, evaluate(trip("tz", 25)).item(Vaccine.YELLOW_FEVER)!!.level)
    }

    @Test
    fun `transito con durata ignota conta per prudenza`() {
        val result = evaluate(Trip(departure = "it", transits = listOf(TripLeg("ke", null, false)), destination = "eg"))
        assertEquals(VaccinationLevel.REQUIRED, result.item(Vaccine.YELLOW_FEVER)!!.level)
    }

    @Test
    fun `uscire dall'aeroporto vale come soggiorno anche con scalo breve`() {
        val result = evaluate(Trip(departure = "it", transits = listOf(TripLeg("ke", 3, true)), destination = "eg"))
        val item = result.item(Vaccine.YELLOW_FEVER)!!
        assertEquals(VaccinationLevel.REQUIRED, item.level)
        assertNull(item.transitHours)
    }

    @Test
    fun `i soggiorni recenti contano come provenienza`() {
        val result = evaluate(Trip(departure = "it", recentCountries = setOf("KE"), destination = "eg"))
        assertEquals("ke", result.item(Vaccine.YELLOW_FEVER)!!.country)
    }

    @Test
    fun `FROM_LIST ignora la lista dei paesi a rischio dell'OMS`() {
        // et e' a rischio ma non e' nella lista dell'India; ke lo e'.
        assertNull(evaluate(Trip(departure = "et", destination = "in")).item(Vaccine.YELLOW_FEVER))
        assertEquals(VaccinationLevel.REQUIRED, evaluate(Trip(departure = "ug", destination = "in")).item(Vaccine.YELLOW_FEVER)!!.level)
        assertEquals(VaccinationReason.YF_ENTRY_FROM_LIST, evaluate(Trip(departure = "ke", destination = "in")).item(Vaccine.YELLOW_FEVER)!!.reason)
    }

    @Test
    fun `bambino di 8 mesi con soglia 9 non ha l'obbligo ma l'avviso`() {
        val result = evaluate(Trip(departure = "ke", destination = "eg", travellerAgeMonths = 8))
        val item = result.item(Vaccine.YELLOW_FEVER, VaccinationReason.YF_ENTRY_FROM_RISK)!!
        assertEquals(VaccinationLevel.CONSIDER, item.level)
        assertEquals(AgeNote.BELOW_AGE, item.ageNote)
        assertEquals(9, item.minAgeMonths)
        assertTrue(result.noCertificateFound)

        val nine = evaluate(Trip(departure = "ke", destination = "eg", travellerAgeMonths = 9)).item(Vaccine.YELLOW_FEVER)!!
        assertEquals(VaccinationLevel.REQUIRED, nine.level)
        assertEquals(AgeNote.NONE, nine.ageNote)
    }

    @Test
    fun `uscita dalla febbre gialla se il paese di partenza la chiede`() {
        val item = evaluate(Trip(departure = "ng", destination = "it")).item(Vaccine.YELLOW_FEVER)!!
        assertEquals(VaccinationLevel.REQUIRED_EXIT, item.level)
        assertEquals(VaccinationReason.YF_EXIT, item.reason)
    }

    @Test
    fun `partenza e destinazione uguali non attivano regole di ingresso ne' di uscita`() {
        val result = evaluate(Trip(departure = "ng", destination = "ng", stayOverFourWeeksInDeparture = true))
        assertTrue(result.noCertificateFound)
        assertNull(result.item(Vaccine.POLIO))
    }

    @Test
    fun `polio in uscita da paese WPV1 con soggiorno di 5 settimane e' REQUIRED_EXIT, con 2 settimane no`() {
        val long = evaluate(Trip(departure = "af", destination = "it", stayOverFourWeeksInDeparture = true))
        val item = long.item(Vaccine.POLIO)!!
        assertEquals(VaccinationLevel.REQUIRED_EXIT, item.level)
        assertEquals(VaccinationReason.POLIO_EXIT, item.reason)
        assertEquals(PolioWindow.W4_12M, item.polioDose!!.window)
        assertFalse(long.noCertificateFound)

        val short = evaluate(Trip(departure = "af", destination = "it", stayOverFourWeeksInDeparture = false))
        assertNull(short.item(Vaccine.POLIO))
        assertTrue(short.noCertificateFound)
    }

    @Test
    fun `polio da paese cVDPV2 e' solo incoraggiata, anche con soggiorno breve`() {
        val item = evaluate(Trip(departure = "ng", destination = "it")).item(Vaccine.POLIO)!!
        assertEquals(VaccinationLevel.ENCOURAGED, item.level)
        assertEquals(VaccinationReason.POLIO_ENCOURAGED, item.reason)
    }

    @Test
    fun `con due categorie vale la piu' severa e la cVDPV2 resta incoraggiata se il soggiorno e' breve`() {
        val long = evaluate(Trip(departure = "cm", destination = "it", stayOverFourWeeksInDeparture = true))
        assertEquals(listOf(VaccinationLevel.REQUIRED_EXIT), long.items.filter { it.vaccine == Vaccine.POLIO }.map { it.level })
        val short = evaluate(Trip(departure = "cm", destination = "it"))
        assertEquals(listOf(VaccinationLevel.ENCOURAGED), short.items.filter { it.vaccine == Vaccine.POLIO }.map { it.level })
    }

    @Test
    fun `paese gia' infettato negli ultimi 24 mesi non da' nessun obbligo polio`() {
        val result = evaluate(Trip(departure = "ke", destination = "it", stayOverFourWeeksInDeparture = true))
        assertNull(result.item(Vaccine.POLIO))
    }

    @Test
    fun `polio all'ingresso risolve le categorie e le liste di paesi`() {
        val fromList = evaluate(Trip(departure = "pk", destination = "eg"))
        val item = fromList.item(Vaccine.POLIO)!!
        assertEquals(VaccinationReason.POLIO_ENTRY, item.reason)
        assertEquals(VaccinationLevel.REQUIRED, item.level)
        assertEquals("pk", item.country)
        assertNull(evaluate(Trip(departure = "it", destination = "eg")).item(Vaccine.POLIO))
    }

    @Test
    fun `polio dell'Hajj solo con lo scopo, senza voci doppie per le due categorie`() {
        assertNull(evaluate(Trip(departure = "af", destination = "sa")).item(Vaccine.POLIO))
        val hajj = evaluate(Trip(departure = "cm", destination = "sa", purpose = TripPurpose.HAJJ_UMRAH))
        assertEquals(1, hajj.items.count { it.vaccine == Vaccine.POLIO && it.reason == VaccinationReason.POLIO_ENTRY })
    }

    @Test
    fun `Hajj si' e no`() {
        val hajj = evaluate(Trip(departure = "it", destination = "sa", purpose = TripPurpose.HAJJ_UMRAH))
        val item = hajj.item(Vaccine.MENACWY)!!
        assertEquals(VaccinationLevel.REQUIRED, item.level)
        assertEquals(VaccinationReason.HAJJ_UMRAH, item.reason)
        assertEquals(12, item.minAgeMonths)
        assertEquals(10, item.minDaysBefore)
        assertEquals(5, item.validityYears)
        assertEquals("la riga raccomandata MenACWY non si ripete", 1, hajj.items.count { it.vaccine == Vaccine.MENACWY })

        val tourist = evaluate(Trip(departure = "it", destination = "sa"))
        assertEquals(VaccinationLevel.CONSIDER, tourist.item(Vaccine.MENACWY)!!.level)
        assertTrue(tourist.noCertificateFound)
    }

    @Test
    fun `routine sempre presente`() {
        for (trip in listOf(Trip(departure = "it", destination = "fr"), Trip(departure = "zz", destination = "yy"))) {
            val routine = evaluate(trip).item(Vaccine.ROUTINE)!!
            assertEquals(VaccinationReason.ROUTINE, routine.reason)
        }
    }

    @Test
    fun `raccomandate per destinazione raggruppate MOST e SOME senza doppioni di febbre gialla`() {
        val result = evaluate(Trip(departure = "it", destination = "ke"))
        assertEquals(VaccinationLevel.RECOMMENDED, result.item(Vaccine.HEPA)!!.level)
        assertEquals(VaccinationReason.DESTINATION_MOST, result.item(Vaccine.HEPA)!!.reason)
        assertEquals(VaccinationLevel.CONSIDER, result.item(Vaccine.RABIES)!!.level)
        assertEquals("cibo e acqua", result.item(Vaccine.HEPA)!!.noteIt)
        val yfItems = result.items.filter { it.vaccine == Vaccine.YELLOW_FEVER }
        assertEquals(listOf(VaccinationReason.YF_DESTINATION_RISK), yfItems.map { it.reason })
        assertEquals(VaccinationLevel.RECOMMENDED, yfItems.single().level)
        // Ordine: livello e poi vaccino.
        assertEquals(result.items.sortedBy { it.level.ordinal }.map { it.level }, result.items.map { it.level })
    }

    @Test
    fun `sotto i 9 mesi la febbre gialla per destinazione a rischio e' da valutare, non consigliata`() {
        val infantResult = evaluate(Trip(departure = "it", destination = "ke", travellerAgeMonths = 4))
        val infant = infantResult.item(Vaccine.YELLOW_FEVER, VaccinationReason.YF_DESTINATION_RISK)!!
        assertEquals(VaccinationLevel.CONSIDER, infant.level)
        assertEquals(AgeNote.BELOW_AGE, infant.ageNote)
        assertEquals(9, infant.minAgeMonths)
        // La riga YF delle raccomandate non deve riportarla tra le consigliate.
        assertEquals(listOf(infant), infantResult.items.filter { it.vaccine == Vaccine.YELLOW_FEVER })

        val adult = evaluate(Trip(departure = "it", destination = "ke")).item(Vaccine.YELLOW_FEVER, VaccinationReason.YF_DESTINATION_RISK)!!
        assertEquals(VaccinationLevel.RECOMMENDED, adult.level)
    }

    @Test
    fun `scalo di durata non indicata conta anche per la soglia delle 24 ore`() {
        // "piu' di 12 ore" nella schermata arriva al motore come durata non indicata
        val result = evaluate(Trip(departure = "it", destination = "tz", transits = listOf(TripLeg("ke", transitHours = null, leftAirport = false))))
        assertTrue(result.has(Vaccine.YELLOW_FEVER, VaccinationLevel.REQUIRED))
    }

    @Test
    fun `destinazione a rischio parziale riporta le aree`() {
        val item = evaluate(Trip(departure = "it", destination = "br")).item(Vaccine.YELLOW_FEVER)!!
        assertTrue(item.partialArea)
        assertEquals("alcune aree", item.noteIt)
        assertEquals("some areas", item.noteEn)
    }

    @Test
    fun `certificato richiesto, la raccomandazione per destinazione a rischio non si aggiunge`() {
        val result = evaluate(Trip(departure = "ke", destination = "sn"))
        assertEquals(listOf(VaccinationLevel.REQUIRED), result.items.filter { it.vaccine == Vaccine.YELLOW_FEVER }.map { it.level })
    }

    @Test
    fun `nessuna regola mai vuoto e flag nessun certificato`() {
        val result = evaluate(Trip(departure = "it", destination = "fr"))
        assertTrue(result.noCertificateFound)
        assertTrue(result.items.isNotEmpty())
        assertEquals("2026-10-03", result.checkedOn)

        val empty = evaluate(Trip(departure = "it", destination = "fr"), VaccinationData())
        assertTrue(empty.noCertificateFound)
        assertTrue(empty.items.isNotEmpty())
        assertNull(empty.checkedOn)
        assertFalse(empty.polioDataStale)
    }

    @Test
    fun `i dati polio sono vecchi oltre 120 giorni`() {
        val fresh = evaluate(Trip(departure = "it", destination = "fr"), now = LocalDate.parse("2026-10-03").plusDays(120))
        assertFalse(fresh.polioDataStale)
        val stale = evaluate(Trip(departure = "it", destination = "fr"), now = LocalDate.parse("2026-10-03").plusDays(121))
        assertTrue(stale.polioDataStale)
        assertEquals("IHR EC 45, 2026-08-21", stale.polioStatement)
    }

    @Test
    fun `i codici paese si normalizzano`() {
        val result = evaluate(Trip(departure = " KE ", destination = "EG"))
        assertEquals(VaccinationLevel.REQUIRED, result.item(Vaccine.YELLOW_FEVER)!!.level)
    }

    @Test
    fun `smoke test su dati realistici, nessuna eccezione per nessuna combinazione`() {
        val countries = Locale.getISOCountries().map { it.lowercase() }
        val big = VaccinationData(
            yfRisk = countries.filterIndexed { i, _ -> i % 4 == 0 }.map { risk(it, partial = it.hashCode() % 2 == 0) },
            yfEntry = countries.mapIndexed { i, c ->
                val rule = YfRule.values()[i % YfRule.values().size]
                yf(c, rule, minAge = if (i % 3 == 0) null else 9, transit = TransitRule.values()[i % TransitRule.values().size],
                    fromList = if (rule == YfRule.FROM_LIST) setOf("ke", "br") else emptySet(), exit = i % 17 == 0)
            },
            polioStatus = countries.filterIndexed { i, _ -> i % 5 == 0 }.mapIndexed { i, c -> polioStatus(c, PolioCategory.values()[i % 3]) },
            polioEntry = data.polioEntry,
            special = data.special,
            recommended = countries.flatMap { c -> Vaccine.values().take(12).map { RecommendedRow(c, it, RecommendedLevel.SOME, "", "", emptyList(), "2026-10-03") } },
            meta = VaccinationMeta(),
        )
        var evaluated = 0
        for (destination in countries) {
            for (departure in listOf("it", "af", "ke", "br", destination)) {
                for (age in listOf(null, 0, 8, 120)) {
                    val result = evaluateVaccinations(
                        Trip(
                            departure = departure,
                            recentCountries = setOf("ng"),
                            transits = listOf(TripLeg("ae", 14, false), TripLeg("et", null, true)),
                            destination = destination,
                            travellerAgeMonths = age,
                            stayOverFourWeeksInDeparture = true,
                            purpose = if (destination == "sa") TripPurpose.HAJJ_UMRAH else null,
                        ),
                        big,
                        today,
                    )
                    assertTrue(result.items.isNotEmpty())
                    assertTrue(result.toSummaryText(Trip(departure, destination = destination), "it").isNotBlank())
                    assertTrue(result.toSummaryText(Trip(departure, destination = destination), "en").isNotBlank())
                    evaluated++
                }
            }
        }
        assertTrue(evaluated > 1000)
    }
}
