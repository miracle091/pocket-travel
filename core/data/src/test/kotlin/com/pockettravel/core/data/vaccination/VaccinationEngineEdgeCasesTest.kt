package com.pockettravel.core.data.vaccination

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Casi limite del motore oltre VaccinationEngineTest: date di verifica, ordine, eta', provenienze e righe doppie. */
class VaccinationEngineEdgeCasesTest {

    private val today = LocalDate.parse("2026-10-03")
    private val day = "2026-10-03"

    private fun yf(
        iso2: String,
        rule: YfRule = YfRule.NONE,
        minAge: Int? = 9,
        transit: StopoverRule = StopoverRule.NONE,
        fromList: Set<String> = emptySet(),
        exit: Boolean = false,
        verifiedAt: String = day,
    ) = YfEntryRow(iso2, rule, minAge, transit, fromList, exit, "", "", listOf("F7"), verifiedAt)

    private fun risk(iso2: String, partial: Boolean = false, areasIt: String = "", verifiedAt: String = day) =
        YfRiskRow(iso2, partial, areasIt, areasIt, listOf("F7"), verifiedAt)

    private fun status(iso2: String, category: PolioCategory, statement: String = "S", verifiedAt: String = day) =
        PolioStatusRow(iso2, category, statement, listOf("F3"), verifiedAt)

    private fun polioEntry(
        iso2: String,
        category: PolioCategory? = null,
        countries: Set<String> = emptySet(),
        applies: PolioApplies = PolioApplies.ALL,
        note: String = "",
        unlisted: Boolean = false,
        verifiedAt: String = day,
    ) = PolioEntryRow(iso2, category, countries, PolioVaccine.BOPV_OR_IPV, PolioWindow.ANY, applies, note, note, listOf("F7"), verifiedAt, unlisted)

    private fun recommended(iso2: String, vaccine: Vaccine, level: RecommendedLevel, verifiedAt: String = day) =
        RecommendedRow(iso2, vaccine, level, "", "", listOf("F7"), verifiedAt)

    private fun evaluate(trip: Trip, data: VaccinationData, now: LocalDate = today) = evaluateVaccinations(trip, data, now)

    private fun VaccinationResult.of(vaccine: Vaccine) = items.filter { it.vaccine == vaccine }

    // Date di verifica

    @Test
    fun `checkedOn e' la revisione piu' recente dichiarata, se c'e'`() {
        val data = VaccinationData(
            yfRisk = listOf(risk("ke", verifiedAt = "2026-12-31")),
            meta = VaccinationMeta(lastReview = "2026-03-03"),
        )
        assertEquals("2026-03-03", evaluate(Trip(departure = "it", destination = "fr"), data).checkedOn)
    }

    @Test
    fun `senza revisione dichiarata checkedOn e' la data piu' recente di qualunque tabella`() {
        val old = "2026-01-01"
        val latest = "2026-12-31"
        val tables = listOf<(String) -> VaccinationData>(
            { VaccinationData(yfRisk = listOf(risk("ke", verifiedAt = it)), yfEntry = listOf(yf("eg", verifiedAt = old))) },
            { VaccinationData(yfRisk = listOf(risk("ke", verifiedAt = old)), yfEntry = listOf(yf("eg", verifiedAt = it))) },
            { VaccinationData(polioStatus = listOf(status("af", PolioCategory.CVDPV2, verifiedAt = it)), yfEntry = listOf(yf("eg", verifiedAt = old))) },
            { VaccinationData(polioEntry = listOf(polioEntry("eg", verifiedAt = it)), yfEntry = listOf(yf("eg", verifiedAt = old))) },
            {
                VaccinationData(
                    special = listOf(SpecialEntryRow("sa", TripPurpose.HAJJ_UMRAH, Vaccine.MENACWY, null, null, null, "", "", emptyList(), it)),
                    yfEntry = listOf(yf("eg", verifiedAt = old)),
                )
            },
            { VaccinationData(recommended = listOf(recommended("ke", Vaccine.HEPA, RecommendedLevel.MOST, verifiedAt = it)), yfEntry = listOf(yf("eg", verifiedAt = old))) },
        )
        tables.forEachIndexed { index, build ->
            assertEquals("tabella $index", latest, evaluate(Trip(departure = "it", destination = "fr"), build(latest)).checkedOn)
        }
    }

    // Dati polio vecchi

    @Test
    fun `la data dei dati polio dichiarata prevale su quella delle righe`() {
        val data = VaccinationData(
            polioStatus = listOf(status("af", PolioCategory.CVDPV2, verifiedAt = "2026-10-01")),
            meta = VaccinationMeta(polioVerifiedAt = "2025-01-01"),
        )
        assertTrue(evaluate(Trip(departure = "it", destination = "fr"), data).polioDataStale)
    }

    @Test
    fun `senza data dichiarata vale la piu' recente delle righe polio`() {
        val data = VaccinationData(
            polioStatus = listOf(
                status("af", PolioCategory.CVDPV2, verifiedAt = "2025-01-01"),
                status("ng", PolioCategory.CVDPV2, verifiedAt = "2026-10-01"),
            ),
        )
        assertFalse(evaluate(Trip(departure = "it", destination = "fr"), data).polioDataStale)
    }

    @Test
    fun `una data polio illeggibile o assente non segnala dati vecchi`() {
        val garbled = VaccinationData(meta = VaccinationMeta(polioVerifiedAt = "non-una-data"))
        assertFalse(evaluate(Trip(departure = "it", destination = "fr"), garbled).polioDataStale)
        assertFalse(evaluate(Trip(departure = "it", destination = "fr"), VaccinationData()).polioDataStale)
        assertNull(evaluate(Trip(departure = "it", destination = "fr"), VaccinationData()).polioStatement)
    }

    @Test
    fun `senza dichiarazione la polioStatement e' quella della prima riga`() {
        val data = VaccinationData(
            polioStatus = listOf(status("af", PolioCategory.CVDPV2, statement = "prima"), status("ng", PolioCategory.CVDPV2, statement = "seconda")),
        )
        assertEquals("prima", evaluate(Trip(departure = "it", destination = "fr"), data).polioStatement)
    }

    // Febbre gialla

    @Test
    fun `l'uscita richiesta sotto l'eta' minima scende a CONSIDER e non conta come certificato`() {
        val data = VaccinationData(yfEntry = listOf(yf("ng", exit = true, minAge = 9)))
        val infant = evaluate(Trip(departure = "ng", destination = "it", travellerAgeMonths = 3), data)
        val item = infant.of(Vaccine.YELLOW_FEVER).single()
        assertEquals(VaccinationReason.YF_EXIT, item.reason)
        assertEquals(VaccinationLevel.CONSIDER, item.level)
        assertEquals(AgeNote.BELOW_AGE, item.ageNote)
        assertTrue(infant.noCertificateFound)

        val unknownAge = evaluate(Trip(departure = "ng", destination = "it"), data).of(Vaccine.YELLOW_FEVER).single()
        assertEquals(VaccinationLevel.REQUIRED_EXIT, unknownAge.level)
        assertEquals(AgeNote.FROM_AGE, unknownAge.ageNote)
    }

    @Test
    fun `un'uscita non richiesta non da' nessuna voce`() {
        val data = VaccinationData(yfEntry = listOf(yf("ng", exit = false)))
        assertTrue(evaluate(Trip(departure = "ng", destination = "it"), data).of(Vaccine.YELLOW_FEVER).isEmpty())
    }

    @Test
    fun `ALL sotto l'eta' minima non toglie il paese ma abbassa il livello`() {
        val data = VaccinationData(yfEntry = listOf(yf("ao", YfRule.ALL, minAge = 9)))
        val item = evaluate(Trip(departure = "it", destination = "ao", travellerAgeMonths = 5), data).of(Vaccine.YELLOW_FEVER).single()
        assertEquals(VaccinationReason.YF_ENTRY_ALL, item.reason)
        assertEquals(VaccinationLevel.CONSIDER, item.level)
        assertEquals(AgeNote.BELOW_AGE, item.ageNote)
        assertNull(item.country)
    }

    @Test
    fun `l'obbligo sotto l'eta' minima e il rischio della destinazione compaiono insieme, in ordine`() {
        val data = VaccinationData(
            yfRisk = listOf(risk("ke"), risk("sn")),
            yfEntry = listOf(yf("sn", YfRule.FROM_RISK, transit = StopoverRule.ANY)),
        )
        val result = evaluate(Trip(departure = "ke", destination = "sn", travellerAgeMonths = 4), data)
        val yfItems = result.of(Vaccine.YELLOW_FEVER)
        assertEquals(listOf(VaccinationReason.YF_ENTRY_FROM_RISK, VaccinationReason.YF_DESTINATION_RISK), yfItems.map { it.reason })
        assertTrue(yfItems.all { it.level == VaccinationLevel.CONSIDER && it.ageNote == AgeNote.BELOW_AGE })
        assertTrue(result.noCertificateFound)
    }

    @Test
    fun `le aree a rischio parziale si riportano solo se il rischio e' parziale`() {
        val data = VaccinationData(yfRisk = listOf(risk("ke", partial = false, areasIt = "ignorate")))
        val item = evaluate(Trip(departure = "it", destination = "ke"), data).of(Vaccine.YELLOW_FEVER).single()
        assertEquals("", item.noteIt)
        assertEquals("", item.noteEn)
        assertFalse(item.partialArea)
    }

    @Test
    fun `i soggiorni nella destinazione non contano come provenienza`() {
        val data = VaccinationData(
            yfRisk = listOf(risk("sn")),
            yfEntry = listOf(yf("sn", YfRule.FROM_RISK, transit = StopoverRule.ANY)),
        )
        val trip = Trip(
            departure = "it",
            recentCountries = setOf("sn"),
            transits = listOf(TripLeg("sn", 20, false), TripLeg("sn", null, true)),
            destination = "sn",
        )
        assertTrue(evaluate(trip, data).noCertificateFound)
    }

    @Test
    fun `la provenienza che fa scattare la regola e' la prima tra partenza, soggiorni, poi scali`() {
        val data = VaccinationData(
            yfRisk = listOf(risk("ke"), risk("et"), risk("ug")),
            yfEntry = listOf(yf("eg", YfRule.FROM_RISK, transit = StopoverRule.ANY)),
        )
        assertEquals("ke", evaluate(Trip(departure = "ke", recentCountries = setOf("et"), destination = "eg"), data).of(Vaccine.YELLOW_FEVER).single().country)
        assertEquals(
            "et",
            evaluate(Trip(departure = "it", recentCountries = setOf("fr", "et", "ke"), destination = "eg"), data).of(Vaccine.YELLOW_FEVER).single().country,
        )
        val viaTransit = Trip(departure = "it", recentCountries = setOf("fr"), transits = listOf(TripLeg("ug", 2, true)), destination = "eg")
        assertEquals("ug", evaluate(viaTransit, data).of(Vaccine.YELLOW_FEVER).single().country)
    }

    @Test
    fun `uno stesso paese con uscita dall'aeroporto e senza conta come soggiorno, non come scalo`() {
        val data = VaccinationData(
            yfRisk = listOf(risk("ke")),
            yfEntry = listOf(yf("tz", YfRule.FROM_RISK, transit = StopoverRule.GT24H)),
        )
        listOf(
            listOf(TripLeg("ke", 30, true), TripLeg("ke", 30, false)),
            listOf(TripLeg("ke", 30, false), TripLeg("ke", 30, true)),
        ).forEach { legs ->
            val item = evaluate(Trip(departure = "it", transits = legs, destination = "tz"), data).of(Vaccine.YELLOW_FEVER).single()
            assertFalse(item.stopover)
            assertNull(item.transitHours)
        }
    }

    @Test
    fun `con FROM_LIST e scali che non contano restano la partenza e i soggiorni`() {
        val data = VaccinationData(
            yfEntry = listOf(yf("in", YfRule.FROM_LIST, transit = StopoverRule.NONE, fromList = setOf("ke"))),
        )
        val scalo = Trip(departure = "it", transits = listOf(TripLeg("ke", null, false)), destination = "in")
        assertTrue(evaluate(scalo, data).noCertificateFound)
        assertFalse(evaluate(Trip(departure = "ke", destination = "in"), data).noCertificateFound)
        assertFalse(evaluate(Trip(departure = "it", recentCountries = setOf("ke"), destination = "in"), data).noCertificateFound)
    }

    @Test
    fun `una voce porta con se fonti e data di verifica della riga`() {
        val data = VaccinationData(yfEntry = listOf(yf("ao", YfRule.ALL, verifiedAt = "2026-02-02")))
        val item = evaluate(Trip(departure = "it", destination = "ao"), data).of(Vaccine.YELLOW_FEVER).single()
        assertEquals(listOf("F7"), item.sources)
        assertEquals("2026-02-02", item.verifiedAt)
    }

    // Polio, speciali e raccomandate

    @Test
    fun `i requisiti polio solo per visto o residenti si mostrano come richiesti`() {
        val data = VaccinationData(
            polioEntry = listOf(
                polioEntry("eg", countries = setOf("pk"), applies = PolioApplies.VISA, note = "visto"),
                polioEntry("eg", countries = setOf("pk"), applies = PolioApplies.RESIDENTS, note = "residenti"),
            ),
        )
        val items = evaluate(Trip(departure = "pk", destination = "eg"), data).of(Vaccine.POLIO)
        assertEquals(listOf(PolioApplies.VISA, PolioApplies.RESIDENTS), items.map { checkNotNull(it.polioDose).applies })
        assertTrue(items.all { it.level == VaccinationLevel.REQUIRED && it.reason == VaccinationReason.POLIO_ENTRY })
    }

    @Test
    fun `due righe polio con le stesse note sono una voce, con note diverse due`() {
        val same = VaccinationData(
            polioEntry = listOf(polioEntry("eg", countries = setOf("pk"), note = "x"), polioEntry("eg", countries = setOf("pk"), note = "x")),
        )
        assertEquals(1, evaluate(Trip(departure = "pk", destination = "eg"), same).of(Vaccine.POLIO).size)
        val different = VaccinationData(
            polioEntry = listOf(polioEntry("eg", countries = setOf("pk"), note = "x"), polioEntry("eg", countries = setOf("pk"), note = "y")),
        )
        assertEquals(2, evaluate(Trip(departure = "pk", destination = "eg"), different).of(Vaccine.POLIO).size)
    }

    @Test
    fun `la provenienza polio unisce la categoria e l'elenco dei paesi`() {
        val data = VaccinationData(
            polioStatus = listOf(status("ng", PolioCategory.CVDPV2)),
            polioEntry = listOf(polioEntry("eg", category = PolioCategory.CVDPV2, countries = setOf("zz"))),
        )
        fun entries(departure: String) =
            evaluate(Trip(departure = departure, destination = "eg"), data).of(Vaccine.POLIO).filter { it.reason == VaccinationReason.POLIO_ENTRY }
        assertEquals(1, entries("ng").size)
        assertEquals(1, entries("zz").size)
        assertTrue(entries("it").isEmpty())
    }

    @Test
    fun `per la polio d'ingresso uno scalo senza uscita non conta, con l'uscita si`() {
        val data = VaccinationData(polioEntry = listOf(polioEntry("eg", countries = setOf("pk"))))
        val noExit = Trip(departure = "it", transits = listOf(TripLeg("pk", 20, false)), destination = "eg")
        assertTrue(evaluate(noExit, data).of(Vaccine.POLIO).isEmpty())
        val exit = Trip(departure = "it", transits = listOf(TripLeg("pk", 20, true)), destination = "eg")
        assertEquals("pk", evaluate(exit, data).of(Vaccine.POLIO).single().country)
    }

    @Test
    fun `un requisito polio da valutare per l'Hajj compare solo con lo scopo`() {
        val data = VaccinationData(polioEntry = listOf(polioEntry("sa", applies = PolioApplies.HAJJ_UMRAH, unlisted = true)))
        assertTrue(evaluate(Trip(departure = "it", destination = "sa"), data).of(Vaccine.POLIO).isEmpty())
        val withPurpose = evaluate(Trip(departure = "it", destination = "sa", purpose = TripPurpose.HAJJ_UMRAH), data)
        assertEquals(VaccinationReason.POLIO_ENTRY_UNLISTED, withPurpose.of(Vaccine.POLIO).single().reason)
    }

    @Test
    fun `la polio in uscita senza righe per la partenza non da' voci`() {
        val data = VaccinationData(polioStatus = listOf(status("af", PolioCategory.WPV1_CVDPV1_CVDPV3)))
        val result = evaluate(Trip(departure = "it", destination = "fr", stayOverFourWeeksInDeparture = true), data)
        assertTrue(result.of(Vaccine.POLIO).isEmpty())
    }

    @Test
    fun `i requisiti dello scopo valgono solo per la destinazione e lo scopo giusti`() {
        val data = VaccinationData(
            special = listOf(SpecialEntryRow("sa", TripPurpose.HAJJ_UMRAH, Vaccine.MENACWY, 12, null, null, "", "", listOf("F7"), day)),
        )
        assertTrue(evaluate(Trip(departure = "it", destination = "eg", purpose = TripPurpose.HAJJ_UMRAH), data).of(Vaccine.MENACWY).isEmpty())
        assertTrue(evaluate(Trip(departure = "it", destination = "sa"), data).of(Vaccine.MENACWY).isEmpty())
    }

    @Test
    fun `sotto l'eta' minima del requisito speciale resta la voce da valutare e anche la raccomandata`() {
        val data = VaccinationData(
            special = listOf(SpecialEntryRow("sa", TripPurpose.HAJJ_UMRAH, Vaccine.MENACWY, 12, null, null, "", "", listOf("F7"), day)),
            recommended = listOf(recommended("sa", Vaccine.MENACWY, RecommendedLevel.SOME)),
        )
        val child = evaluate(Trip(departure = "it", destination = "sa", purpose = TripPurpose.HAJJ_UMRAH, travellerAgeMonths = 6), data)
        assertEquals(
            listOf(VaccinationReason.HAJJ_UMRAH, VaccinationReason.DESTINATION_SOME),
            child.of(Vaccine.MENACWY).map { it.reason },
        )
        assertTrue(child.noCertificateFound)

        val unknownAge = evaluate(Trip(departure = "it", destination = "sa", purpose = TripPurpose.HAJJ_UMRAH), data)
        assertEquals(1, unknownAge.of(Vaccine.MENACWY).size)
        assertEquals(AgeNote.FROM_AGE, unknownAge.of(Vaccine.MENACWY).single().ageNote)
    }

    @Test
    fun `due righe raccomandate per lo stesso vaccino danno una sola voce, la prima`() {
        val data = VaccinationData(
            recommended = listOf(
                recommended("ke", Vaccine.HEPA, RecommendedLevel.MOST),
                recommended("ke", Vaccine.HEPA, RecommendedLevel.SOME),
                recommended("tz", Vaccine.TYPHOID, RecommendedLevel.MOST),
            ),
        )
        val result = evaluate(Trip(departure = "it", destination = "ke"), data)
        assertEquals(listOf(VaccinationLevel.RECOMMENDED), result.of(Vaccine.HEPA).map { it.level })
        assertTrue(result.of(Vaccine.TYPHOID).isEmpty())
    }

    @Test
    fun `le voci sono ordinate per livello e poi per vaccino`() {
        val data = VaccinationData(
            yfEntry = listOf(yf("x1", YfRule.ALL, minAge = null), yf("ng", exit = true, minAge = null)),
            polioStatus = listOf(status("ng", PolioCategory.CVDPV2)),
            recommended = listOf(
                recommended("x1", Vaccine.TYPHOID, RecommendedLevel.MOST),
                recommended("x1", Vaccine.RABIES, RecommendedLevel.SOME),
                recommended("x1", Vaccine.HEPA, RecommendedLevel.MOST),
            ),
        )
        val result = evaluate(Trip(departure = "ng", destination = "x1"), data)
        assertEquals(
            listOf(
                VaccinationLevel.REQUIRED to Vaccine.YELLOW_FEVER,
                VaccinationLevel.REQUIRED_EXIT to Vaccine.YELLOW_FEVER,
                VaccinationLevel.ENCOURAGED to Vaccine.POLIO,
                VaccinationLevel.RECOMMENDED to Vaccine.HEPA,
                VaccinationLevel.RECOMMENDED to Vaccine.TYPHOID,
                VaccinationLevel.RECOMMENDED to Vaccine.ROUTINE,
                VaccinationLevel.CONSIDER to Vaccine.RABIES,
            ),
            result.items.map { it.level to it.vaccine },
        )
        assertFalse(result.noCertificateFound)
    }

    @Test
    fun `chi resta nel paese non ha regole di ingresso ma ha le raccomandate della destinazione`() {
        val data = VaccinationData(
            yfRisk = listOf(risk("ke")),
            yfEntry = listOf(yf("ke", YfRule.ALL, exit = true)),
            recommended = listOf(recommended("ke", Vaccine.HEPA, RecommendedLevel.MOST)),
        )
        val result = evaluate(Trip(departure = "ke", destination = "ke"), data)
        assertEquals(listOf(VaccinationReason.YF_DESTINATION_RISK), result.of(Vaccine.YELLOW_FEVER).map { it.reason })
        assertEquals(1, result.of(Vaccine.HEPA).size)
        assertTrue(result.noCertificateFound)
    }
}
