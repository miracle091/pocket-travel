package com.pockettravel.feature.ai

import com.pockettravel.core.data.CarRoute
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.FtsMatchInfo
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.Note
import com.pockettravel.core.data.Poi
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitDeparture
import com.pockettravel.core.data.TransitMode
import com.pockettravel.core.data.vaccination.TripPurpose
import com.pockettravel.core.poi.PoiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TravelAssistantLogicTest {

    @Test
    fun `builds an OR query from words of at least four characters`() {
        val query = buildFtsQuery("Posso portare farmaci da banco in Giappone?", "giappone")

        assertEquals("porta* OR farma* OR banco*", query)
    }

    @Test
    fun `operatori FTS scritti in maiuscolo nella domanda restano parole`() {
        val query = buildFtsQuery("Hotel NEAR stazione OR aeroporto", "italia")

        assertEquals("hotel* OR near OR stazio* OR aeropo*", query)
    }

    @Test
    fun `le parole con l'apostrofo si dividono come nell'indice`() {
        assertEquals("caldo* OR rimin* OR estat*", buildFtsQuery("Fa caldo a Rimini d'estate?", "italia"))
        assertEquals("musei* OR isola*", buildFtsQuery("Musei dell'isola", "italia"))
    }

    @Test
    fun `drops punctuation from each token`() {
        val query = buildFtsQuery("vaccini, dogana; normativa?!", "italia")

        assertEquals("vacci* OR dogan* OR normat*", query)
    }

    @Test
    fun `returns blank for a question made only of short words`() {
        val query = buildFtsQuery("hi to a in", "italia")

        assertTrue(query.isBlank())
    }

    @Test
    fun `drops tokens that are part of the region's display name`() {
        val query = buildFtsQuery("Quale valuta si usa a San Marino?", "san-marino")

        assertEquals("valut*", query)
    }

    @Test
    fun `senza parole interrogative e con le parole lunghe per prefisso`() {
        assertEquals("piatt* OR tipic* OR pugli*", buildFtsQuery("Quali sono i piatti tipici della Puglia?", "italia"))
        assertEquals("typic* OR dishe*", buildFtsQuery("What are the typical dishes?", "italia"))
        // "passaporto" -> "passap*": con 5 lettere troverebbe anche "passare".
        assertEquals("passap* OR entra*", buildFtsQuery("Serve il passaporto per entrare?", "italia"))
        assertEquals("dista* OR roma OR firen*", buildFtsQuery("Quanto dista Roma da Firenze?", "italia"))
    }

    @Test
    fun `la citta' nominata esce dalla query, salvo che resti solo lei`() {
        assertEquals("cosa OR mangi*", buildFtsQuery("Cosa si mangia a Napoli?", "italia", "Napoli"))
        assertEquals("dove OR dormi*", buildFtsQuery("Dove dormire a Porto Santo?", "portogallo", "Porto Santo"))
        assertEquals("cosa OR veder*", buildFtsQuery("Cosa vedere a Forli?", "italia", "Forlì"))
        assertEquals("napol*", buildFtsQuery("Napoli?", "italia", "Napoli"))
    }

    @Test
    fun `region name tokens are excluded even when they are the only long words`() {
        val query = buildFtsQuery("E il Giappone?", "giappone")

        assertTrue(query.isBlank())
    }

    @Test
    fun `truncates context beyond the configured character limit`() {
        val longContext = "a".repeat(3000)

        val truncated = truncateContext(longContext, maxChars = 2000)

        assertEquals(2000, truncated.length)
    }

    @Test
    fun `leaves short context untouched`() {
        val shortContext = "Dogane: dichiarare importi oltre una certa soglia."

        assertEquals(shortContext, truncateContext(shortContext, maxChars = 2000))
    }

    private fun guideSection(title: String, body: String = "corpo", category: GuideCategory = GuideCategory.TRASPORTI) = GuideSection(
        regionId = "italia",
        category = category,
        title = title,
        body = body,
        sourceUrl = "https://it.wikivoyage.org/wiki/Italia",
    )

    private fun citySection(city: String, title: String, body: String = "corpo") = CitySection(
        regionId = "italia",
        city = city,
        category = GuideCategory.COSA_VEDERE,
        title = title,
        body = body,
        sourceUrl = "https://it.wikivoyage.org/wiki/$city",
    )

    /** Una frase e due colonne (title, body): [titleHits] e [bodyHits] occorrenze in questa riga, sezione lunga [length] token. */
    private fun info(titleHits: Int = 0, bodyHits: Int = 0, length: Int = 50) = FtsMatchInfo(
        phraseCount = 1,
        columnCount = 2,
        hits = intArrayOf(titleHits, bodyHits),
        docsWithHit = intArrayOf(2, 3),
        rowCount = 20,
        averageLength = intArrayOf(3, 50),
        length = intArrayOf(3, length),
    )

    @Test
    fun `rankSections unisce guida e citta' per punteggio, non per fonte`() {
        val result = rankSections(
            guideMatches = listOf(guideSection("Dogane", "corpo dogane") to info(bodyHits = 1), guideSection("Trasporti", "corpo trasporti") to info(titleHits = 1, bodyHits = 3)),
            cityMatches = listOf(citySection("Roma", "Cosa vedere a Roma", "corpo roma") to info(bodyHits = 3)),
            limit = 3,
        )

        assertEquals(listOf("corpo trasporti", "corpo roma", "corpo dogane"), result.map { it.body })
    }

    @Test
    fun `rankSections taglia al limite anche con piu' candidati di entrambe le fonti`() {
        val guide = listOf(guideSection("A", "corpo A") to info(bodyHits = 1), guideSection("B", "corpo B") to info(bodyHits = 2))
        val city = listOf(citySection("Roma", "C", "corpo C") to info(bodyHits = 3), citySection("Roma", "D", "corpo D") to info(titleHits = 1, bodyHits = 3))

        val result = rankSections(guide, city, limit = 2)

        assertEquals(listOf("corpo D", "corpo C"), result.map { it.body })
    }

    @Test
    fun `senza una citta' nominata la guida del paese viene prima delle citta'`() {
        val guide = listOf(guideSection("Trasporti", "corpo paese") to info(bodyHits = 1))
        val city = listOf(citySection("Roma", "Trasporti", "corpo roma") to info(titleHits = 1, bodyHits = 4))

        assertEquals(listOf("corpo roma", "corpo paese"), rankSections(guide, city, limit = 3).map { it.body })
        assertEquals(listOf("corpo paese", "corpo roma"), rankSections(guide, city, limit = 3, countryFirst = true).map { it.body })
    }

    @Test
    fun `una Storia lunga non scavalca una sezione corta con le stesse occorrenze`() {
        val storia = CitySection("italia", "Roma", GuideCategory.STORIA, "Storia", "corpo storia", "https://it.wikipedia.org/wiki/Roma")
        val result = rankSections(
            emptyList(),
            listOf(storia to info(bodyHits = 3, length = 700), citySection("Roma", "Dormire", "corpo dormire") to info(bodyHits = 3, length = 40)),
            limit = 3,
        )

        assertEquals(listOf("corpo dormire", "corpo storia"), result.map { it.body })
    }

    @Test
    fun `se la domanda non parla di storia o clima la Storia non scavalca le sezioni pratiche`() {
        val storia = CitySection("italia", "Roma", GuideCategory.STORIA, "Storia", "corpo storia", "https://it.wikipedia.org/wiki/Roma")
        val city = listOf(storia to info(titleHits = 1, bodyHits = 5), citySection("Roma", "Dormire", "corpo dormire") to info(bodyHits = 1))

        assertEquals(listOf("corpo storia", "corpo dormire"), rankSections(emptyList(), city, limit = 3).map { it.body })
        assertEquals(listOf("corpo dormire", "corpo storia"), rankSections(emptyList(), city, limit = 3, historyOrClimate = false).map { it.body })
    }

    @Test
    fun `domande di storia o clima in italiano e inglese`() {
        assertTrue(isHistoryOrClimateQuestion("Chi ha fondato Rimini?"))
        assertTrue(isHistoryOrClimateQuestion("Fa freddo a Bologna d'inverno?"))
        assertTrue(isHistoryOrClimateQuestion("Was Bath a Roman town?"))
        assertTrue(isHistoryOrClimateQuestion("Does it rain a lot in Porto?"))
        assertFalse(isHistoryOrClimateQuestion("How do I get to Porto by train?"))
        assertFalse(isHistoryOrClimateQuestion("Dove dormire a Rimini?"))
        assertFalse(isHistoryOrClimateQuestion("Which hotels are in Rome?"))
    }

    @Test
    fun `con una citta' nominata le sue sezioni vengono prima di quelle del paese`() {
        val guide = listOf(guideSection("Trasporti", "corpo paese") to info(titleHits = 1, bodyHits = 4))
        val city = listOf(citySection("Roma", "Trasporti", "corpo roma") to info(bodyHits = 1))

        assertEquals(listOf("corpo paese", "corpo roma"), rankSections(guide, city, limit = 3).map { it.body })
        assertEquals(listOf("corpo roma", "corpo paese"), rankSections(guide, city, limit = 3, countryFirst = false).map { it.body })
    }

    @Test
    fun `rankSections senza candidati non trova nulla`() {
        assertTrue(rankSections(emptyList(), emptyList(), limit = 3).isEmpty())
    }

    @Test
    fun `rankSections cita la citta' nelle sezioni delle guide di citta'`() {
        val result = rankSections(emptyList(), listOf(citySection("Roma", "Cosa vedere") to info(bodyHits = 1)), limit = 3)

        assertTrue(result.single().citation.contains("(Roma)"))
    }

    @Test
    fun `rankSections cita Wikipedia per Storia e Clima delle citta'`() {
        val storia = CitySection("italia", "Roma", GuideCategory.STORIA, "Storia", "corpo", "https://it.wikipedia.org/wiki/Roma")
        val history = storia.copy(title = "History", sourceUrl = "https://en.wikipedia.org/wiki/Rome")

        assertEquals(
            "Fonte: Wikipedia, sezione Storia (Roma) — https://it.wikipedia.org/wiki/Roma",
            rankSections(emptyList(), listOf(storia to info(bodyHits = 1)), limit = 3).single().citation,
        )
        assertEquals(
            "Source: Wikipedia, section History (Roma) — https://en.wikipedia.org/wiki/Rome",
            rankSections(emptyList(), listOf(history to info(bodyHits = 1)), limit = 3, language = "en").single().citation,
        )
        assertTrue(rankSections(emptyList(), listOf(citySection("Roma", "Cosa vedere") to info(bodyHits = 1)), limit = 3).single().citation.startsWith("Fonte: Wikivoyage"))
    }

    @Test
    fun `namedCities riconosce le citta' nominate, senza disambiguatore e accenti`() {
        val cities = listOf("Porto (Portogallo)", "Porto Santo", "Forlì", "Bra", "Braga", "Ne")

        assertEquals(listOf("Porto (Portogallo)"), namedCities("Cosa vedere a Porto?", cities))
        assertEquals(listOf("Porto Santo"), namedCities("Come arrivare a Porto Santo in traghetto?", cities))
        assertEquals(listOf("Forlì"), namedCities("Dove dormire a forli?", cities))
        assertEquals(listOf("Braga"), namedCities("Musei di Braga", cities))
        assertTrue(namedCities("Quanti ne servono per entrare?", cities).isEmpty())
        assertTrue(namedCities("Serve il passaporto?", cities).isEmpty())
        // Parole comuni uguali a una citta' di una sola parola: non sono la citta'.
        val homonyms = listOf("Nice", "Mobile", "Split", "Malé")
        assertTrue(namedCities("Is there a nice beach?", homonyms, "en").isEmpty())
        assertTrue(namedCities("How do I get mobile data?", homonyms, "en").isEmpty())
        assertTrue(namedCities("Mi sento male, dove trovo un medico?", homonyms).isEmpty())
        assertEquals(listOf("Nice"), namedCities("Beaches in nice?", homonyms, "en"))
        assertEquals(listOf("Split"), namedCities("Ferry to Split", homonyms, "en"))
        assertEquals(listOf("Malé"), namedCities("Cosa vedere a male?", homonyms))
    }

    @Test
    fun `namedCities trova entrambe le citta' di una domanda sulla distanza`() {
        val cities = listOf("Firenze", "Roma", "Siena")

        assertEquals(listOf("Firenze", "Siena"), namedCities("Quanto dista Siena da Firenze?", cities))
        assertEquals(listOf("Firenze", "Roma"), namedCities("Quanti km ci sono tra Roma e Firenze?", cities))
    }

    @Test
    fun `domande sulla distanza e sul tempo di viaggio`() {
        listOf(
            "Quanto dista Siena da Firenze?", "Quanti km ci sono tra Roma e Firenze?", "Qual e' la distanza tra Torino e Milano?",
            "Asti e' lontana da Torino?", "How far is Turin from Milan?", "How many kilometres is Pisa from Lucca?",
            "Quanto è distante Siena da Firenze?", "Siena e Firenze sono distanti?", "Is Lucca distant from Pisa?",
        ).forEach { assertTrue(it, isDistanceQuestion(it)) }
        listOf(
            "Quanto ci vuole da Torino a Milano?", "Quanto ci si mette da Pisa a Lucca?", "Quanto tempo serve per andare da Pisa a Lucca?",
            "How long does it take from Pisa to Lucca?", "How long is the trip from Pisa to Lucca?", "How long does the drive from Pisa to Lucca take?",
        ).forEach { assertTrue(it, isTravelTimeQuestion(it)) }
        // Quanto restare o visitare, non il viaggio tra le due citta'.
        listOf(
            "Cosa vedere a Firenze e Siena?", "Distillerie a Firenze?", "Is Turin farther north?",
            "Quanto tempo serve per visitare Roma e Firenze?", "How long should I stay in Rome and Florence?",
        ).forEach { assertFalse(it, isDistanceQuestion(it) || isTravelTimeQuestion(it)) }
    }

    // Stessi testi attesi di test_sft_distances.py (DistanceContextTest): il training copia questo blocco.
    @Test
    fun `testo della distanza tra due citta', con e senza percorso in auto`() {
        assertEquals(
            "Torino e Milano distano 126 km in linea d'aria. Su strada la distanza è maggiore.",
            cityDistanceContext("Torino", "Milano", 125_640.0, null, "it"),
        )
        assertEquals(
            "Torino e Milano distano 126 km in linea d'aria. In auto il percorso è di 142 km, circa 1 h 35 min.",
            cityDistanceContext("Torino", "Milano", 125_640.0, CarRoute(141_800.0, 5_710.0), "it"),
        )
        assertEquals(
            "Porto and Braga are 49 km apart in a straight line. By car the route is 55 km, about 45 min.",
            cityDistanceContext("Porto (Portugal)", "Braga", 48_700.0, CarRoute(55_200.0, 2_690.0), "en"),
        )
        assertEquals(
            "Pisa and Lucca are 1 km apart in a straight line. By road the distance is longer.",
            cityDistanceContext("Pisa", "Lucca", 300.0, null, "en"),
        )
        assertEquals(
            "Torino e Milano distano 126 km in linea d'aria. In auto il percorso è di 142 km, circa 2 h.",
            cityDistanceContext("Torino", "Milano", 125_640.0, CarRoute(141_800.0, 7_190.0), "it"),
        )
    }

    @Test
    fun `focusStems tiene le radici della domanda senza la citta'`() {
        assertEquals(setOf("estat", "piove"), focusStems("estate OR piove OR rimini", "Rimini"))
        assertEquals(setOf("clima", "rimini".take(5)), focusStems("clima OR rimini", null))
        assertEquals(setOf("cosa", "mangi", "passa"), focusStems("cosa OR mangi* OR passap*", "Napoli"))
    }

    @Test
    fun `radici e paragrafi si confrontano senza accenti`() {
        assertEquals(setOf("veder"), focusStems("vedere OR forlì", "Forlì"))
        assertEquals(setOf("citta"), focusStems("città", null))
        val body = listOf("Il porto.", "La città vecchia e' murata.").joinToString("\n")

        assertEquals("La città vecchia e' murata.", relevantParagraphs(body, setOf("citta"), budget = 30))
    }

    @Test
    fun `una sezione troppo lunga lascia nel contesto i paragrafi della domanda`() {
        val clima = listOf("Il clima e' temperato.", "In inverno nevica spesso in collina.", "Le estati sono calde e afose.").joinToString("\n")

        assertEquals("Il clima e' temperato.\nLe estati sono calde e afose.", relevantParagraphs(clima, setOf("estat"), budget = 60))
        assertEquals("Il clima e' temperato.\nIn inverno nevica spesso in collina.", relevantParagraphs(clima, emptySet(), budget = 60))
        assertEquals(clima, relevantParagraphs(clima, setOf("estat"), budget = 500))
        assertEquals("abcde", relevantParagraphs("abcdefghij", emptySet(), budget = 5))
    }

    @Test
    fun `nel contesto la prima sezione prende quello che le serve, le altre lo spazio rimasto`() {
        val prima = "A".repeat(100)
        val storia = listOf("Fondata dai Romani.", "B".repeat(300), "Nel Medioevo fu libero comune.").joinToString("\n")

        val context = selectContext(listOf(prima, storia), setOf("roman", "medio"), maxChars = 160)

        assertEquals("$prima\n\nFondata dai Romani.\nNel Medioevo fu libero comune.", context)
    }

    @Test
    fun `buildOnDeviceContext aggiunge la nota etichettata dopo le sezioni`() {
        val sections = rankSections(listOf(guideSection("Dogane", "corpo dogane") to info(bodyHits = 1)), emptyList(), limit = 3)
        val note = Note(id = 1, title = "Volo di ritorno", body = "Martedi alle 18", updatedAt = 0)

        val context = buildOnDeviceContext(sections, note)

        assertEquals("corpo dogane\n\nNota personale: Volo di ritorno\nMartedi alle 18", context)
        assertEquals("corpo dogane\n\nPersonal note: Volo di ritorno\nMartedi alle 18", buildOnDeviceContext(sections, note, language = "en"))
    }

    @Test
    fun `buildOnDeviceContext senza nota resta solo le sezioni`() {
        val sections = rankSections(listOf(guideSection("Dogane", "corpo dogane") to info(bodyHits = 1)), emptyList(), limit = 3)

        assertEquals("corpo dogane", buildOnDeviceContext(sections, note = null))
    }

    @Test
    fun `buildOnDeviceContext resta nello stesso limite di caratteri con o senza nota`() {
        val sections = rankSections(listOf(guideSection("Dogane", "a".repeat(1900)) to info(bodyHits = 1)), emptyList(), limit = 3)
        val note = Note(id = 1, title = "t", body = "b".repeat(500), updatedAt = 0)

        val context = buildOnDeviceContext(sections, note, maxChars = 2000)

        assertEquals(2000, context.length)
        // la nota resta (accorciata al suo tetto), sono le sezioni a cedere spazio
        assertTrue(context.contains("Nota personale: t"))
    }

    @Test
    fun `le domande sui vaccini in italiano e inglese aggiungono l'esito delle vaccinazioni`() {
        assertTrue(isVaccinationQuestion("Servono vaccinazioni per il Kenya?"))
        assertTrue(isVaccinationQuestion("Mi chiedono il certificato della febbre gialla?"))
        assertTrue(isVaccinationQuestion("Do I need a yellow fever vaccine?"))
        assertTrue(isVaccinationQuestion("Polio requirements for Pakistan?"))
        assertFalse(isVaccinationQuestion("Com'e' la cucina in Kenya?"))
        assertFalse(isVaccinationQuestion("Is tap water safe to drink?"))
    }

    @Test
    fun `una domanda su Hajj o Umrah calcola i vaccini per il pellegrinaggio`() {
        assertEquals(TripPurpose.HAJJ_UMRAH, tripPurposeOf("Che vaccini servono per l'Umrah?"))
        assertEquals(TripPurpose.HAJJ_UMRAH, tripPurposeOf("Vaccines for the Hajj?"))
        assertNull(tripPurposeOf("Servono vaccinazioni per il Kenya?"))
    }

    @Test
    fun `le domande su cosa c'e' attorno alla posizione aggiungono i POI vicini`() {
        assertTrue(isNearbyQuestion("C'e' una farmacia qui vicino?"))
        assertTrue(isNearbyQuestion("Dov'è il bancomat più vicino?"))
        assertTrue(isNearbyQuestion("Dove mangio nei dintorni?"))
        assertTrue(isNearbyQuestion("Is there an ATM near me?"))
        assertTrue(isNearbyQuestion("Where is the nearest pharmacy?"))
        assertFalse(isNearbyQuestion("C'e' un aeroporto vicino a Riga?"))
        assertFalse(isNearbyQuestion("Serve il visto per il Giappone?"))
        // "Piu' vicino a <luogo>": il riferimento e' un altro posto, non la posizione dell'utente.
        assertFalse(isNearbyQuestion("Qual è la farmacia più vicina a Roma?"))
        assertFalse(isNearbyQuestion("Where is the nearest pharmacy to the station?"))
        assertFalse(isNearbyQuestion("La stazione più vicina a Merano?"))
        assertTrue(isNearbyQuestion("Qual è la farmacia più vicina a me?"))
        assertTrue(isNearbyQuestion("What's the closest ATM to me?"))
    }

    @Test
    fun `si citano solo le fonti delle sezioni entrate nel contesto`() {
        val dentro = AssistantSection("Primo paragrafo della guida sulla dogana", GuideCategory.DOGANE, "Fonte A")
        val fuori = AssistantSection("Paragrafo rimasto fuori per spazio", GuideCategory.DOGANE, "Fonte B")
        val context = buildOnDeviceContext(listOf(dentro), note = null)
        assertEquals(listOf("Fonte A"), citedSections(listOf(dentro, fuori), context).map { it.citation })
    }

    @Test
    fun `i POI vicini sono raggruppati per categoria in ordine di distanza`() {
        val pois = listOf(
            poi("Farmacia Centrale", "amenity=pharmacy", openingHours = "Mo-Fr 08:30-19:30") to 80,
            poi("Bar Roma", "amenity=cafe") to 120,
            poi("Farmacia Nord", "amenity=pharmacy") to 250,
        )
        val labels = mapOf(PoiCategory.FARMACIA to "Farmacie", PoiCategory.CIBO_BEVANDE to "Ristoranti e bar")

        val text = nearbyPoiContext(pois, radiusMeters = 300, label = { labels.getValue(it) }, language = "it")

        assertEquals(
            "Punti di interesse entro 300 m dalla tua posizione:\n" +
                "Farmacie: Farmacia Centrale (80 m, orari Mo-Fr 08:30-19:30), Farmacia Nord (250 m)\n" +
                "Ristoranti e bar: Bar Roma (120 m)",
            text,
        )
    }

    @Test
    fun `in inglese i POI vicini usano il nome inglese e l'intestazione inglese`() {
        val pois = listOf(poi("Aptieka", "amenity=pharmacy", nameEn = "Pharmacy Riga") to 40)

        val text = nearbyPoiContext(pois, radiusMeters = 150, label = { "Pharmacies" }, language = "en")

        assertEquals("Points of interest within 150 m of your position:\nPharmacies: Pharmacy Riga (40 m)", text)
    }

    @Test
    fun `senza POI vicini non c'e' nessun contesto`() {
        assertNull(nearbyPoiContext(emptyList(), radiusMeters = 600, label = { "" }, language = "it"))
    }

    @Test
    fun `le domande sui mezzi pubblici aggiungono le prossime partenze`() {
        assertTrue(isTransitQuestion("Quando passa il prossimo autobus?"))
        assertTrue(isTransitQuestion("A che ora parte il tram?"))
        assertTrue(isTransitQuestion("When is the next bus?"))
        assertTrue(isTransitQuestion("Metro departures from here?"))
        assertFalse(isTransitQuestion("Serve il visto per il Giappone?"))
        assertFalse(isTransitQuestion("Is the busy season in August?"))
    }

    @Test
    fun `le prossime partenze diventano righe con ora, minuti, mezzo, linea e direzione`() {
        val board = TransitBoard.Departures(
            items = listOf(
                departure("7", TransitMode.TRAM, "Centrale", minuteOfDay = 14 * 60 + 5, inMinutes = 3),
                departure("22", TransitMode.BUS, null, minuteOfDay = 14 * 60 + 20, inMinutes = 18),
            ),
            validUntil = LocalDate.of(2026, 12, 31), daysLeft = 89, feeds = emptyList(),
        )

        assertEquals(
            "Prossime partenze dalle fermate qui vicino:\n14:05 (tra 3 min) Tram 7 per Centrale\n14:20 (tra 18 min) Autobus 22",
            transitContext(board, "it"),
        )
        assertEquals(
            "Next departures from the stops nearby:\n14:05 (in 3 min) Tram 7 to Centrale\n14:20 (in 18 min) Bus 22",
            transitContext(board, "en"),
        )
    }

    @Test
    fun `orari scaduti, nessuna partenza e nessuna fermata`() {
        assertEquals(
            "Gli orari dei mezzi pubblici installati sono scaduti il 30/9/2026.",
            transitContext(TransitBoard.Expired(LocalDate.of(2026, 9, 30), emptyList()), "it"),
        )
        assertEquals(
            "No departures in the next hours from the stops nearby.",
            transitContext(TransitBoard.Departures(emptyList(), LocalDate.of(2026, 12, 31), 89, emptyList()), "en"),
        )
        assertNull(transitContext(TransitBoard.NoStops, "it"))
    }

    private fun departure(line: String, mode: TransitMode, headsign: String?, minuteOfDay: Int, inMinutes: Int) =
        TransitDeparture(line, mode, color = null, textColor = null, headsign = headsign, minuteOfDay = minuteOfDay, inMinutes = inMinutes)

    private fun poi(name: String, osmTag: String, openingHours: String? = null, nameEn: String? = null): Poi {
        return Poi(
            id = 1, regionId = "riga", name = name, category = osmTag.substringAfter("="), latitude = 0.0, longitude = 0.0, osmTag = osmTag,
            phone = null, openingHours = openingHours, nameEn = nameEn,
        )
    }
}
