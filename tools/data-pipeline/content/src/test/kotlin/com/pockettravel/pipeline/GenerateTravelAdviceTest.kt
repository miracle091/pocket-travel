package com.pockettravel.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.io.path.createTempDirectory

class GenerateTravelAdviceTest {

    // Struttura del JSON open data di travel.gc.ca (cta-cap-it.json), testi accorciati.
    private fun json(eng: Map<String, String>, iso: String = "IT", state: Int = 1, regional: Int = 0) = JSONObject(
        mapOf(
            "data" to mapOf(
                "country-iso" to iso, "advisory-state" to state, "has-regional-advisory" to regional,
                "eng" to mapOf("friendly-date" to "September 22, 2026 05:35 EDT") + eng,
            ),
        ),
    ).toString()

    private val day0 = LocalDate.of(2026, 10, 1)

    private val security = """
        <h3>Crime</h3>
        <h4>Petty crime</h4>
        <p>Petty crime, such as pickpocketing, occurs frequently. Report it to the nearest Government of Canada office.</p>
        <ul><li>keep your belongings in a secure place</li><li><p>do not show signs of affluence</p></li><li>contact the Canadian embassy</li></ul>
        <h4>Useful links</h4>
        <ul><li>Overseas fraud</li></ul>
        <h3>Terrorism</h3>
        <p>Attacks in Italy cannot be ruled out.&nbsp;Targets could include airports. We don't assess local airlines.</p>
        <p><a href="https://travel.gc.ca/travelling/health-safety/drugs">Drugs, alcohol and travel</a></p>
    """.trimIndent()

    @Test
    fun `sottotitoli, elenchi e paragrafi nel formato delle guide, senza frasi e voci sul Canada`() {
        assertEquals(
            "▸ Petty crime\nPetty crime, such as pickpocketing, occurs frequently.\n" +
                "• keep your belongings in a secure place\n• do not show signs of affluence\n\n" +
                "▸ Terrorism\nAttacks in Italy cannot be ruled out. Targets could include airports.",
            adviceHtmlToText(security),
        )
    }

    @Test
    fun `le frasi utili in prima persona diventano impersonali, le altre con we e our restano fuori`() {
        val html = """
            <p>Criminal activity has been reported near Huaquillas, where we advise against non-essential travel.</p>
            <p>We also strongly advise that you file a report with the local police. Our ability to provide consular services is limited.</p>
            <p>We don't make assessments on the compliance of foreign domestic airlines with international safety standards.</p>
        """.trimIndent()

        assertEquals(
            "Criminal activity has been reported near Huaquillas, where the advice is against non-essential travel.\n\n" +
                "You should also file a report with the local police.",
            adviceHtmlToText(html),
        )
    }

    @Test
    fun `una frase sul Canada esce intera anche con abbreviazioni come U_S_ e St_`() {
        // Testi di cta-cap-us.json e cta-cap-ru.json del 2026-10-09, accorciati.
        val html = """
            <p>Although the possession of cannabis is legal in some U.S. states, it remains illegal under U.S. federal laws in any form and quantity, making it illegal to bring across the Canada-U.S. border.</p>
            <p>Don't attempt to cross the Canada-U.S. border with any amount of cannabis in any form. You can expect legal prosecution and fines.</p>
            <p>Drone strikes have occurred in Moscow and St. Petersburg. Contact the Canadian embassy if affected.</p>
        """.trimIndent()

        assertEquals(
            "You can expect legal prosecution and fines.\n\nDrone strikes have occurred in Moscow and St. Petersburg.",
            adviceHtmlToText(html),
        )
    }

    @Test
    fun `un sottotitolo escluso toglie anche quelli di livello inferiore`() {
        val html = """
            <h3>Pre-travel vaccines and medications</h3><p>Hepatitis A.</p><h4>Yellow fever</h4><p>Required.</p>
            <h3>Safe food and water precautions</h3><p>Drink bottled water.</p>
            <h3>Dual citizenship</h3><p>Recognized.</p><h3>Keep in Mind...</h3><p>The decision to travel is yours.</p>
        """.trimIndent()

        assertEquals("▸ Safe food and water precautions\nDrink bottled water.", adviceHtmlToText(html))
    }

    @Test
    fun `un paragrafo che inizia con un link resta, anche se un paragrafo dopo finisce con un link`() {
        // Blocco Zika del Brasile: un link a fine paragrafo non deve far togliere i paragrafi dopo.
        val html = """
            <p><a href="https://www.canada.ca/zika.html">Zika virus</a> is a risk in this country.</p>
            <p>Zika virus is spread through the bite of an infected mosquito.</p>
            <p>For more information, see <a href="https://www.canada.ca/zika-pregnancy.html">Zika virus: Pregnant or planning a pregnancy.</a></p>
        """.trimIndent()

        assertEquals(
            "Zika virus is a risk in this country.\n\nZika virus is spread through the bite of an infected mosquito.\n\n" +
                "For more information, see Zika virus: Pregnant or planning a pregnancy.",
            adviceHtmlToText(html),
        )
    }

    @Test
    fun `salute compatta - avvisi e strutture interi, delle malattie il nome e la prima frase, niente consigli generici`() {
        // Struttura della parte health di travel.gc.ca (Brasile), testi accorciati.
        val html = """
            <h3>Outbreak Monitoring</h3><h4>Chikungunya in Mato Grosso do Sul, Brazil</h4>
            <p>There is a large outbreak. Prevent bites.</p><p><strong>Learn more:</strong><br><a href="https://x">Chikungunya</a></p>
            <h3>Safe food and water precautions</h3><p>Eating unsafe food can make you sick.</p><ul><li>Boil it, cook it</li></ul>
            <details class="health-tab"><summary class="healthtabexpandablesection">Typhoid fever </summary>
            <p>There is a risk of typhoid fever in this destination, but the risk is low for most travellers. Travellers visiting friends are at higher risk.</p>
            <p>Typhoid fever is caused by bacteria.</p><p><strong>Learn more:<br></strong><a href="https://x">Typhoid fever</a></p></details>
            <h3>Tick and insect bite prevention</h3><p>Many diseases are spread by bites.</p>
            <details class="health-tab"><summary class="healthtabexpandablesection">Dengue </summary>
            <ul><li>In this country, dengue is a risk to travellers. It is a viral disease.</li><li>Dengue can cause flu-like symptoms.</li></ul></details>
            <details class="health-tab"><summary class="healthtabexpandablesection">Cholera</summary>
            <p><strong>Risk</strong></p><p>Cholera is a risk in parts of this country. It spreads through unsafe water.</p></details>
            <details class="health-tab"><summary class="healthtabexpandablesection">Tuberculosis</summary>
            <p>Tuberculosis is an infection caused by bacteria.</p><p>For most travellers the risk of tuberculosis is low.</p></details>
            <details class="health-tab"><summary class="healthtabexpandablesection">Measles</summary>
            <p>Measles is a serious viral infection.</p><p>It spreads easily.</p></details>
            <h3>Medical services and facilities</h3><p>Good health care is usually only available in urban areas.</p>
        """.trimIndent()

        assertEquals(
            "▸ Chikungunya in Mato Grosso do Sul, Brazil\nThere is a large outbreak. Prevent bites.\n\n" +
                "▸ Typhoid fever\nThere is a risk of typhoid fever in this destination, but the risk is low for most travellers.\n\n" +
                "▸ Dengue\n• In this country, dengue is a risk to travellers.\n\n" +
                "▸ Cholera\nCholera is a risk in parts of this country.\n\n" +
                "▸ Tuberculosis\nFor most travellers the risk of tuberculosis is low.\n\n" +
                "▸ Measles\nMeasles is a serious viral infection.\n\n" +
                "▸ Medical services and facilities\nGood health care is usually only available in urban areas.",
            adviceHtmlToText(html, compactHealth = true),
        )
        // Senza compattare (le altre parti): le malattie sono sottotitoli e "Learn more:" non c'e', il resto intero.
        assertTrue(adviceHtmlToText(html).let { "▸ Dengue\n• In this country" in it && "Learn more" !in it && "Boil it" in it })
    }

    @Test
    fun `sezioni della guida con livello di rischio, categoria, url e data`() {
        val sections = travelAdviceSections(
            json(
                mapOf(
                    "advisories" to "<h3>Italy - Exercise a high degree of caution</h3><p>Exercise a high degree of caution due to terrorism.</p>",
                    "security" to security,
                    "entry-exit" to "<p>Canadians need a valid passport.</p>",
                    "laws-culture" to "<h3>Drugs</h3><p>Penalties are severe.</p><h3>Transfer to a Canadian prison</h3><p>Possible.</p>",
                    "disasters-climate" to "<h3>Volcanoes</h3><p>There are nine active volcanoes.</p>",
                    "health" to "<h3>Routine vaccines</h3><p>Be up to date.</p>",
                    "offices-html" to "<p>Embassy of Canada in Rome</p>",
                ),
            ),
        )

        assertEquals(listOf("SICUREZZA", "USI_COSTUMI", "SICUREZZA"), sections.map { it.category })
        assertEquals(
            listOf("Safety and security (Government of Canada)", "Laws and culture (Government of Canada)", "Natural disasters and climate (Government of Canada)"),
            sections.map { it.title },
        )
        assertTrue(sections.all { it.sourceUrl == "https://travel.gc.ca/destinations/it" && it.body.endsWith("Last updated by the Government of Canada: September 22, 2026.") })
        assertTrue(sections.first().body.startsWith("▸ Italy - Exercise a high degree of caution\nExercise a high degree of caution due to terrorism.\n\n▸ Petty crime"))
        assertFalse(sections.any { "passport" in it.body || "Embassy" in it.body || "prison" in it.body })
        assertTrue(sections.all(::isTravelAdvice))
    }

    // Struttura della pagina FCDO della GOV.UK Content API (foreign-travel-advice/wallis-and-futuna), testi accorciati.
    private fun fcdoJson(alerts: List<String> = listOf("avoid_all_travel_to_parts")) = JSONObject(
        mapOf(
            "base_path" to "/foreign-travel-advice/wallis-and-futuna",
            "public_updated_at" to "2025-12-10T13:02:12+00:00",
            "details" to mapOf(
                "alert_status" to alerts,
                "parts" to listOf(
                    mapOf(
                        "title" to "Warnings and insurance",
                        "body" to "<p>Your travel insurance could be invalidated.</p>" +
                            "<h2 id=\"areas-where-fcdo-advises-against-travel\">Areas where <abbr title=\"Foreign\">FCDO</abbr> advises against travel</h2>" +
                            "<h3>Alofi</h3><p><abbr title=\"Foreign\">FCDO</abbr> advises against all travel to:</p><ul><li>Alofi island</li></ul>" +
                            "<p>Find out more about why FCDO advises against travel.</p>" +
                            "<h2 id=\"before-you-travel\">Before you travel</h2><p>No travel can be guaranteed safe.</p>",
                    ),
                    mapOf("title" to "Entry requirements", "body" to "<p>You need a British passport.</p>"),
                    mapOf(
                        "title" to "Safety and security",
                        "body" to "<h2>Crime</h2><p>Crime is low. Report it to the British embassy in Paris. " +
                            "We advise you not to use unlicensed taxis.</p>" +
                            "<h2>Laws and cultural differences</h2><h3>Dress code</h3><p>Dress modestly in villages.</p>" +
                            "<h2>Extreme weather and natural disasters</h2><p>Cyclones hit from November to April.</p>",
                    ),
                    mapOf("title" to "Regional risks", "body" to "<h2>Futuna</h2><p>Roads close after storms.</p>"),
                    mapOf(
                        "title" to "Health",
                        "body" to "<h2>Emergency medical number</h2><p>Call 15. The NHS does not cover you.</p>" +
                            "<h2>Vaccine recommendations and health risks</h2><p>Check TravelHealthPro.</p>",
                    ),
                    mapOf("title" to "Getting help", "body" to "<p>We can help British nationals.</p>"),
                ),
            ),
        ),
    ).toString()

    @Test
    fun `consigli FCDO nelle stesse quattro sezioni, senza le parti per i britannici`() {
        val sections = fcdoAdviceSections(fcdoJson())

        assertEquals(listOf("SICUREZZA", "USI_COSTUMI", "SICUREZZA", "SALUTE"), sections.map { it.category })
        assertEquals(
            listOf(
                "▸ Travel advice level\nThe UK government advises against all travel to parts of the country.\n\n" +
                    "▸ Alofi\nAvoid all travel to:\n• Alofi island\n\n" +
                    "▸ Crime\nCrime is low. Do not use unlicensed taxis.\n\n▸ Futuna\nRoads close after storms.",
                "▸ Dress code\nDress modestly in villages.",
                "▸ Extreme weather and natural disasters\nCyclones hit from November to April.",
                "▸ Emergency medical number\nCall 15.",
            ),
            sections.map { it.body.substringBefore("\n\nLast updated") },
        )
        assertEquals("Safety and security (UK government)", sections.first().title)
        assertEquals(
            listOf("safety-and-security", "safety-and-security", "safety-and-security", "health").map { "https://www.gov.uk/foreign-travel-advice/wallis-and-futuna/$it" },
            sections.map { it.sourceUrl },
        )
        assertTrue(sections.all { it.body.endsWith("Last updated by the UK government: December 10, 2025.") && isTravelAdvice(it) })
    }

    @Test
    fun `Palestina - le parti solo israeliane in una sezione per chi e' israeliano, per gli altri un rimando`() {
        val json = JSONObject(
            mapOf(
                "base_path" to "/foreign-travel-advice/palestine",
                "public_updated_at" to "2026-07-22T13:43:56+01:00",
                "details" to mapOf(
                    "alert_status" to listOf("avoid_all_travel_to_parts"),
                    "parts" to listOf(
                        mapOf(
                            "title" to "Warnings and insurance",
                            "body" to "<h2>Areas where FCDO advises against travel</h2>" +
                                "<h3>Gaza</h3><p>FCDO advises against all travel to Gaza</p>" +
                                "<h3>Northern Israel and Occupied Golan Heights</h3><p>FCDO advises against all travel to:</p><ul><li>Sheba’a Farms</li></ul>" +
                                "<h2>Areas where FCDO advises against all but essential travel</h2>" +
                                "<h3>West Bank</h3><p>FCDO advises against all but essential travel to:</p><ul><li>The rest of the West Bank</li></ul>",
                        ),
                        mapOf(
                            "title" to "Safety and security",
                            "body" to "<h2>Conflict between Iran and Israel</h2><p>A ceasefire was agreed.</p>" +
                                "<h2>Crime</h2><p>Pickpocketing happens in Jerusalem.</p>",
                        ),
                        mapOf(
                            "title" to "Regional risks",
                            "body" to "<h2>Tel Aviv</h2><h3>Buses</h3><p>Take care on buses.</p><h2>West Bank</h2><p>Clashes happen.</p>",
                        ),
                    ),
                ),
            ),
        ).toString()

        val sections = fcdoAdviceSections(json)
        assertEquals(
            listOf("Safety and security (UK government)", "Israel: areas and risks outside Palestine (UK government)", "Israel (UK government)"),
            sections.map { it.title },
        )
        val (palestine, israelOnly, notice) = sections.map { it.body.substringBefore("\n\nLast updated") }
        assertEquals(
            "▸ Travel advice level\nThe UK government advises against all travel to parts of the country.\n\n" +
                "▸ Gaza\nAvoid all travel to Gaza\n\n▸ West Bank\nAvoid all but essential travel to:\n• The rest of the West Bank\n\n" +
                "▸ Crime\nPickpocketing happens in Jerusalem.\n\n▸ West Bank\nClashes happen.",
            palestine,
        )
        assertEquals(
            "▸ Northern Israel and Occupied Golan Heights\nAvoid all travel to:\n• Sheba’a Farms\n\n" +
                "▸ Conflict between Iran and Israel\nA ceasefire was agreed.\n\n▸ Buses\nTake care on buses.",
            israelOnly,
        )
        assertTrue(notice.startsWith("This advice also covers Israel."))
        assertEquals(
            listOf("", "#for-nationality=IL", "#not-for-nationality=IL"),
            sections.map { it.sourceUrl.orEmpty().removePrefix("https://www.gov.uk/foreign-travel-advice/palestine/safety-and-security") },
        )
        assertTrue(sections.all(::isTravelAdvice))
    }

    @Test
    fun `consigli FCDO letti dal tsv come quelli di travel_gc_ca, con il livello sulla stessa scala`() {
        val dir = createTempDirectory("advice-fcdo").toFile()
        val wallis = File(dir, "wf.json").apply { writeText(fcdoJson(listOf("avoid_all_but_essential_travel_to_whole_country"))) }
        val tsv = File(dir, "advice.tsv").apply { writeText("wallis-futuna\t${wallis.path}\n") }

        val advice = readTravelAdvice(tsv, { emptyList() }, emptyMap(), day0).getValue("wallis-futuna")
        assertEquals(TravelAdviceMeta(2, 0, day0), advice.meta)
        assertEquals(4 + 2, advice.sections.size) // quattro per tutti, ingresso e assistenza per i britannici
        assertTrue(advice.sections.first().body.contains("against all but essential travel to the whole country."))
        dir.deleteRecursively()
    }

    @Test
    fun `JSON illeggibile o senza la parte inglese non da' sezioni`() {
        assertEquals(emptyList<GuideSectionRow>(), travelAdviceSections("<html>404</html>"))
        assertEquals(emptyList<GuideSectionRow>(), travelAdviceSections("""{"data": {"country-iso": "IT"}}"""))
    }

    @Test
    fun `consigli scaricati o ricopiati dal pubblicato, al posto di quelli vecchi`() {
        val dir = createTempDirectory("advice").toFile()
        val italy = File(dir, "it.json").apply { writeText(json(mapOf("security" to "<p>Fresh advice.</p>"))) }
        val tsv = File(dir, "advice.tsv").apply { writeText("italia\t${italy.path}\nmessico\t\n") }
        val publishedAdvice = GuideSectionRow("SICUREZZA", "Safety and security (Government of Canada)", "Old advice.", "https://travel.gc.ca/destinations/mx")
        val wikivoyage = GuideSectionRow("SICUREZZA", "Stay safe", "Watch your bags.")

        val publishedMeta = mapOf("messico" to TravelAdviceMeta(1, 1, day0.minusDays(3)))
        val advice = readTravelAdvice(tsv, { listOf(wikivoyage, publishedAdvice) }, publishedMeta, day0)
        assertEquals(TravelAdviceMeta(1, 0, day0), advice.getValue("italia").meta)
        assertEquals(publishedMeta["messico"], advice.getValue("messico").meta) // ricopiati: la data resta quella vecchia
        val guides = listOf(
            RegionGuide("italia", "https://en.wikivoyage.org/wiki/Italy", listOf(wikivoyage, publishedAdvice.copy(body = "Stale."))),
            RegionGuide("messico", "https://en.wikivoyage.org/wiki/Mexico", listOf(wikivoyage)),
            RegionGuide("san-marino", "https://en.wikivoyage.org/wiki/San_Marino", listOf(wikivoyage)),
        ).withTravelAdvice(advice)

        assertEquals(listOf("Watch your bags.", "Fresh advice.\n\nLast updated by the Government of Canada: September 22, 2026."), guides[0].sections.map { it.body })
        assertEquals(listOf(wikivoyage, publishedAdvice), guides[1].sections)
        assertEquals(listOf(wikivoyage), guides[2].sections)
        dir.deleteRecursively()
    }

    @Test
    fun `i soli consigli cambiati pubblicano al piu' una volta a settimana, subito se cambia il rischio`() {
        val dir = createTempDirectory("advice-week").toFile()
        val wikivoyage = GuideSectionRow("SICUREZZA", "Stay safe", "Watch your bags.")
        val published = File(dir, "published.db")
        val fresh = File(dir, "guides.db")
        // Un guides.db come lo scrive generateGuides: sezioni di Wikivoyage, consigli di travel.gc.ca e travel_advice_meta.
        fun build(db: File, today: LocalDate, adviceText: String, state: Int = 1, wikivoyageText: String = "Watch your bags.") {
            db.delete()
            val italy = File(dir, "it.json").apply { writeText(json(mapOf("security" to "<p>$adviceText</p>"), state = state)) }
            val tsv = File(dir, "advice.tsv").apply { writeText("italia\t${italy.path}\n") }
            val advice = readTravelAdvice(tsv, { emptyList() }, emptyMap(), today)
            val guides = listOf(RegionGuide("italia", "https://en.wikivoyage.org/wiki/Italy", listOf(wikivoyage.copy(body = wikivoyageText))))
            writeGuidesDb(guides.withTravelAdvice(advice), db)
            writeTravelAdviceMeta(advice, db)
        }
        try {
            build(published, day0, "Old advice.")
            assertEquals(mapOf("italia" to TravelAdviceMeta(1, 0, day0)), readTravelAdviceMeta(published))

            // Stesso testo: si tiene il pubblicato anche dopo mesi (la data di download non conta).
            build(fresh, day0.plusDays(60), "Old advice.")
            assertTrue(keepPublishedGuides(fresh, published, day0.plusDays(60)))

            // Testo dei consigli cambiato: per 6 giorni si tiene il pubblicato, il settimo si pubblica.
            build(fresh, day0.plusDays(6), "New advice.")
            assertTrue(keepPublishedGuides(fresh, published, day0.plusDays(6)))
            assertFalse(keepPublishedGuides(fresh, published, day0.plusDays(7)))

            // Livello di rischio cambiato: si pubblica subito.
            build(fresh, day0.plusDays(1), "New advice.", state = 2)
            assertEquals(listOf("italia: livello 1 -> 2, avvisi regionali 0 -> 0"), travelAdviceRiskChanges(readTravelAdviceMeta(published), readTravelAdviceMeta(fresh)))
            assertFalse(keepPublishedGuides(fresh, published, day0.plusDays(1)))

            // Wikivoyage cambiato: si pubblica subito, con i consigli freschi.
            build(fresh, day0.plusDays(1), "New advice.", wikivoyageText = "Watch your wallet.")
            assertFalse(keepPublishedGuides(fresh, published, day0.plusDays(1)))

            // Pubblicato senza travel_advice_meta (guides.db precedente): i consigli si considerano vecchi.
            DriverManager.getConnection("jdbc:sqlite:${published.path}").use { it.createStatement().use { s -> s.execute("DROP TABLE travel_advice_meta") } }
            build(fresh, day0.plusDays(1), "New advice.")
            assertFalse(keepPublishedGuides(fresh, published, day0.plusDays(1)))
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- adviceHtmlToText: casi limite ---

    @Test
    fun `html vuoto, solo tag o solo spazi non da' testo`() {
        assertEquals("", adviceHtmlToText(""))
        assertEquals("", adviceHtmlToText("<div><ul><li></li></ul><br/></div>"))
        assertEquals("", adviceHtmlToText("<p>&nbsp;  </p>"))
    }

    @Test
    fun `un sottotitolo senza contenuto non compare e il successivo si`() {
        assertEquals("▸ Pieno\nTesto.", adviceHtmlToText("<h3>Vuoto</h3><h3>Pieno</h3><p>Testo.</p>"))
        // Anche se tutto il testo sotto il sottotitolo parla del Canada.
        assertEquals("", adviceHtmlToText("<h3>Ambasciata</h3><p>Contact the Canadian embassy.</p>"))
    }

    @Test
    fun `un sottotitolo escluso finisce al titolo dello stesso livello o superiore, senza distinguere le maiuscole`() {
        assertEquals("▸ Next\nb", adviceHtmlToText("<h3>USEFUL LINKS</h3><p>a</p><h4>Sotto</h4><p>c</p><h2>Next</h2><p>b</p>"))
        assertEquals("", adviceHtmlToText("<h3>Keep in mind that the decision is yours</h3><p>Testo.</p>"))
    }

    @Test
    fun `i parametri personalizzati scelgono le frasi da togliere e i sottotitoli da escludere`() {
        assertEquals("This is fine.", adviceHtmlToText("<p>This is secret. This is fine.</p>", foreign = Regex("secret")))
        assertEquals("▸ Keep\ny", adviceHtmlToText("<h3>Skip</h3><p>x</p><h3>Keep</h3><p>y</p>", excluded = { it == "Skip" }))
        // Una voce di elenco con un riferimento straniero cade intera, un paragrafo perde solo la frase.
        assertEquals("• ok item", adviceHtmlToText("<ul><li>keep it. Canada rules</li><li>ok item</li></ul>"))
    }

    @Test
    fun `i separatori dipendono dal tipo di blocco precedente`() {
        assertEquals("a\n• b\n\nc", adviceHtmlToText("<p>a</p><ul><li>b</li></ul><p>c</p>"))
        assertEquals("a\n\nb", adviceHtmlToText("<p>a</p><p>b</p>"))
        assertEquals("• a\n• b", adviceHtmlToText("<ul><li>a</li><li>b</li></ul>"))
    }

    @Test
    fun `spazi non separabili, spazi ripetuti ed entita' nei sottotitoli e nel testo`() {
        assertEquals("Solo due spazi", adviceHtmlToText("<p>Solo&nbsp;&nbsp;due   spazi</p>"))
        assertEquals("▸ Rock & roll\nx", adviceHtmlToText("<h3>Rock &amp; roll</h3><p>x</p>"))
    }

    @Test
    fun `testo unicode attraversa la pulizia`() {
        assertEquals("Tōkyō 東京 Москва 😀.", adviceHtmlToText("<p>Tōkyō 東京 Москва 😀.</p>"))
    }

    // --- travelAdviceSections: casi limite ---

    @Test
    fun `senza paese a due lettere o senza dati non ci sono sezioni`() {
        assertEquals(emptyList<GuideSectionRow>(), travelAdviceSections(json(mapOf("security" to "<p>Ok.</p>"), iso = "ITA")))
        assertEquals(emptyList<GuideSectionRow>(), travelAdviceSections(json(mapOf("security" to "<p>Ok.</p>"), iso = "")))
        assertEquals(emptyList<GuideSectionRow>(), travelAdviceSections(json(mapOf("security" to "<p>Contact the Canadian embassy.</p>"))))
        assertEquals(emptyList<GuideSectionRow>(), travelAdviceSections("{}"))
        assertEquals(emptyList<GuideSectionRow>(), travelAdviceSections(""))
    }

    @Test
    fun `il codice paese si porta in minuscolo nell'URL e senza data valida manca il piede`() {
        val noDate = travelAdviceSections(json(mapOf("friendly-date" to "", "security" to "<p>Ok.</p>"), iso = "Jp")).single()
        assertEquals("https://travel.gc.ca/destinations/jp", noDate.sourceUrl)
        assertEquals("Ok.", noDate.body)

        val badDate = travelAdviceSections(json(mapOf("friendly-date" to "presto", "security" to "<p>Ok.</p>"))).single()
        assertEquals("Ok.", badDate.body)

        val shortDay = travelAdviceSections(json(mapOf("friendly-date" to "October 2, 2026 10:00 EDT", "security" to "<p>Ok.</p>"))).single()
        assertTrue(shortDay.body.endsWith("Last updated by the Government of Canada: October 2, 2026."))
    }

    @Test
    fun `gli avvisi regionali da soli bastano per la sezione sicurezza e non entrano nelle altre`() {
        val sections = travelAdviceSections(json(mapOf("advisories" to "<h3>Avviso</h3><p>Evitate la zona nord.</p>", "health" to "<p>Bevete acqua.</p>")))

        assertEquals(listOf("SICUREZZA", "SALUTE"), sections.map { it.category })
        assertEquals("▸ Avviso\nEvitate la zona nord.", sections[0].body.substringBefore("\n\nLast"))
        assertFalse("Avviso" in sections[1].body)
    }

    // --- FCDO: casi limite ---

    private fun fcdoMinimal(
        basePath: String = "/foreign-travel-advice/test-land",
        alerts: List<String> = emptyList(),
        health: String = "",
        updated: String? = null,
    ) = JSONObject(
        buildMap<String, Any> {
            put("base_path", basePath)
            updated?.let { put("public_updated_at", it) }
            put(
                "details",
                mapOf(
                    "alert_status" to alerts,
                    "parts" to listOf(mapOf("title" to "Health", "body" to health)),
                ),
            )
        },
    ).toString()

    @Test
    fun `FCDO - JSON illeggibile, senza dettagli o con un percorso estraneo non da' sezioni`() {
        assertEquals(emptyList<GuideSectionRow>(), fcdoAdviceSections("non json"))
        assertEquals(emptyList<GuideSectionRow>(), fcdoAdviceSections("""{"base_path": "/foreign-travel-advice/x"}"""))
        assertEquals(emptyList<GuideSectionRow>(), fcdoAdviceSections(fcdoMinimal(basePath = "/altro/percorso", health = "<p>Ok.</p>")))
        assertEquals(emptyList<GuideSectionRow>(), fcdoAdviceSections("""{"details": {"parts": []}}"""))
    }

    @Test
    fun `FCDO - senza livelli ne' testo non ci sono sezioni`() {
        assertEquals(emptyList<GuideSectionRow>(), fcdoAdviceSections(fcdoMinimal()))
    }

    @Test
    fun `FCDO - il solo livello di allerta basta per la sicurezza e piu' livelli si elencano nell'ordine fisso`() {
        val whole = fcdoAdviceSections(fcdoMinimal(alerts = listOf("avoid_all_travel_to_whole_country"))).single()
        assertEquals("SICUREZZA", whole.category)
        assertEquals("▸ Travel advice level\nThe UK government advises against all travel to the whole country.", whole.body)
        assertEquals("https://www.gov.uk/foreign-travel-advice/test-land/safety-and-security", whole.sourceUrl)

        val both = fcdoAdviceSections(
            fcdoMinimal(alerts = listOf("avoid_all_but_essential_travel_to_parts", "avoid_all_travel_to_whole_country", "valore_sconosciuto")),
        ).single()
        assertEquals(
            "▸ Travel advice level\nThe UK government advises against all travel to the whole country and " +
                "against all but essential travel to parts of the country.",
            both.body,
        )
    }

    @Test
    fun `FCDO - la salute ha il proprio URL e il piede dipende dalla data`() {
        val dated = fcdoAdviceSections(fcdoMinimal(health = "<h2>Ospedali</h2><p>Buona assistenza.</p>", updated = "2025-12-10T13:02:12+00:00")).single()
        assertEquals("Health (UK government)", dated.title)
        assertEquals("SALUTE", dated.category)
        assertEquals("https://www.gov.uk/foreign-travel-advice/test-land/health", dated.sourceUrl)
        assertEquals("▸ Ospedali\nBuona assistenza.\n\nLast updated by the UK government: December 10, 2025.", dated.body)

        val badDate = fcdoAdviceSections(fcdoMinimal(health = "<p>Ok.</p>", updated = "ieri")).single()
        assertEquals("Ok.", badDate.body)
        val noDate = fcdoAdviceSections(fcdoMinimal(health = "<p>Ok.</p>")).single()
        assertEquals("Ok.", noDate.body)
    }

    @Test
    fun `FCDO - i sottotitoli sui vaccini e sulla salute mentale si escludono`() {
        val health = "<h2>Vaccine recommendations and health risks</h2><p>Check it.</p><h2>Travel and mental health</h2><p>Care.</p>" +
            "<h2>Hospitals</h2><p>Good.</p>"

        assertEquals("▸ Hospitals\nGood.", fcdoAdviceSections(fcdoMinimal(health = health)).single().body)
    }

    // --- readTravelAdvice: ripieghi ---

    private fun withDir(block: (File) -> Unit) {
        val dir = createTempDirectory("advice-extra").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `readTravelAdvice ricopia il pubblicato se la riga non ha file, il file manca, e' vuoto o non si legge`() {
        withDir { dir ->
            val empty = File(dir, "vuoto.json").apply { writeText("") }
            val html = File(dir, "pagina.html").apply { writeText("<html>404</html>") }
            val noState = File(dir, "senza-stato.json").apply { writeText("""{"data": {"country-iso": "IT", "eng": {"security": "<p>Ok.</p>"}}}""") }
            val tsv = File(dir, "advice.tsv").apply {
                writeText("senza-tab\nnon-esiste\t${File(dir, "non-esiste.json").path}\nvuoto\t${empty.path}\nhtml\t${html.path}\nsenza-stato\t${noState.path}\n\n")
            }
            val oldAdvice = GuideSectionRow("SICUREZZA", "Old", "vecchio", "https://travel.gc.ca/destinations/it")
            val wikivoyage = GuideSectionRow("SICUREZZA", "Stay safe", "Watch your bags.")
            val meta = TravelAdviceMeta(2, 1, day0.minusDays(2))

            val advice = readTravelAdvice(tsv, { listOf(wikivoyage, oldAdvice) }, mapOf("html" to meta), day0)

            assertEquals(setOf("senza-tab", "non-esiste", "vuoto", "html", "senza-stato"), advice.keys)
            advice.values.forEach { assertEquals(listOf(oldAdvice), it.sections) }
            assertEquals(meta, advice.getValue("html").meta)
            assertEquals(null, advice.getValue("vuoto").meta)
            // Sezioni valide ma senza advisory-state: il livello manca, quindi si tiene il pubblicato.
            assertEquals(listOf(oldAdvice), advice.getValue("senza-stato").sections)
            assertEquals(null, advice.getValue("senza-stato").meta)
        }
    }

    @Test
    fun `readTravelAdvice con un tsv vuoto non da' regioni`() {
        withDir { dir ->
            val tsv = File(dir, "advice.tsv").apply { writeText("\n\n") }

            assertEquals(emptyMap<String, RegionAdvice>(), readTravelAdvice(tsv, { emptyList() }, emptyMap(), day0))
        }
    }

    @Test
    fun `readTravelAdvice porta il livello FCDO sulla scala di travel_gc_ca`() {
        withDir { dir ->
            fun levelFor(alerts: List<String>): TravelAdviceMeta? {
                val file = File(dir, "fcdo.json").apply { writeText(fcdoMinimal(alerts = alerts, health = "<p>Ok.</p>")) }
                val tsv = File(dir, "advice.tsv").apply { writeText("paese\t${file.path}\n") }
                return readTravelAdvice(tsv, { emptyList() }, emptyMap(), day0).getValue("paese").meta
            }

            assertEquals(TravelAdviceMeta(3, 0, day0), levelFor(listOf("avoid_all_travel_to_whole_country")))
            assertEquals(TravelAdviceMeta(2, 0, day0), levelFor(listOf("avoid_all_but_essential_travel_to_whole_country")))
            assertEquals(TravelAdviceMeta(0, 1, day0), levelFor(listOf("avoid_all_travel_to_parts")))
            assertEquals(TravelAdviceMeta(0, 1, day0), levelFor(listOf("avoid_all_but_essential_travel_to_parts")))
            assertEquals(TravelAdviceMeta(3, 1, day0), levelFor(listOf("avoid_all_travel_to_whole_country", "avoid_all_travel_to_parts")))
            assertEquals(TravelAdviceMeta(0, 0, day0), levelFor(emptyList()))
        }
    }

    @Test
    fun `la data di download dei consigli freschi e' quella passata, non quella del file`() {
        withDir { dir ->
            val italy = File(dir, "it.json").apply { writeText(json(mapOf("security" to "<p>Ok.</p>"), state = 3, regional = 1)) }
            val tsv = File(dir, "advice.tsv").apply { writeText("italia\t${italy.path}\n") }

            assertEquals(TravelAdviceMeta(3, 1, day0), readTravelAdvice(tsv, { emptyList() }, emptyMap(), day0).getValue("italia").meta)
        }
    }

    // --- sezioni per i soli britannici e canadesi ---

    private val britishLink = "https://www.gov.uk/foreign-travel-advice/wallis-and-futuna"

    @Test
    fun `FCDO - ingresso e assistenza consolare per intero, solo per i britannici`() {
        val sections = fcdoBritishSections(fcdoJson())!!

        assertEquals(listOf("DOGANE", "SICUREZZA"), sections.map { it.category })
        assertEquals(listOf("Entry requirements (UK government)", "Getting help (UK government)"), sections.map { it.title })
        assertEquals(
            listOf("$britishLink/entry-requirements#for-nationality=GB", "$britishLink/getting-help#for-nationality=GB"),
            sections.map { it.sourceUrl },
        )
        assertEquals(
            listOf("You need a British passport.", "We can help British nationals."),
            sections.map { it.body.substringBefore("\n\nLast updated") },
        )
        assertTrue(sections.all { it.body.endsWith("Last updated by the UK government: December 10, 2025.") && isTravelAdvice(it) })
    }

    @Test
    fun `FCDO - le sezioni per tutti non cambiano e restano senza ingresso e assistenza`() {
        val everyone = fcdoAdviceSections(fcdoJson())

        assertEquals(4, everyone.size)
        assertTrue(everyone.none { "#for-nationality=" in it.sourceUrl.orEmpty() || "British passport" in it.body || "British nationals" in it.body })
    }

    @Test
    fun `FCDO - sottotitoli, elenchi e link orfani delle parti per i britannici`() {
        val json = JSONObject(
            mapOf(
                "base_path" to "/foreign-travel-advice/japan",
                "details" to mapOf(
                    "parts" to listOf(
                        mapOf(
                            "title" to "Entry requirements",
                            "body" to "<div class=\"example\"><p>Covers Japan.</p></div><h2 id=\"a\">Visa requirements  </h2>" +
                                "<p>You can get a visa on arrival.</p><ul><li>Up to 90 days</li></ul><p><a href=\"https://x.test\">Check the rules</a></p>",
                        ),
                    ),
                ),
            ),
        ).toString()

        val entry = fcdoBritishSections(json)!!.single()
        assertEquals("▸ Visa requirements\nYou can get a visa on arrival.\n• Up to 90 days", entry.body)
        assertEquals("https://www.gov.uk/foreign-travel-advice/japan/entry-requirements#for-nationality=GB", entry.sourceUrl)
    }

    @Test
    fun `FCDO - senza le due parti la lista e' vuota, con un JSON estraneo o illeggibile e' null`() {
        assertEquals(emptyList<GuideSectionRow>(), fcdoBritishSections(fcdoMinimal(health = "<p>Ok.</p>")))
        assertEquals(null, fcdoBritishSections("non json"))
        assertEquals(null, fcdoBritishSections(fcdoMinimal(basePath = "/altro/percorso")))
        assertEquals(null, fcdoBritishSections(json(mapOf("security" to "<p>Ok.</p>"))))
    }

    // JSON di travel.gc.ca con gli uffici in "offices" (struttura reale: campi nulli o vuoti dove mancano).
    private fun canadianJson(entry: String = "<p>Your passport must be valid.</p>", help: String = "<h3>Consular assistance</h3><p>Contact the nearest office.</p>") = JSONObject(
        mapOf(
            "data" to mapOf(
                "country-iso" to "JP", "advisory-state" to 0, "has-regional-advisory" to 0,
                "eng" to mapOf(
                    "friendly-date" to "September 22, 2026 05:35 EDT",
                    "entry-exit" to entry,
                    "offices-html" to "<h3>Local services</h3><section><details><summary>Emergency services</summary><div><p>Dial 110.</p></div></details></section>" +
                        help + "<details class=\"span-3\"><summary><strong>Tokyo</strong> - Embassy of Canada</summary><div>Street Address Akasaka</div></details>",
                    "offices" to listOf(
                        mapOf(
                            "city" to "Tokyo", "type" to "Embassy of Canada", "address" to "3-38 Akasaka, Tokyo", "postal-address" to "",
                            "tel-legacy" to "81 (3) 5412-6200", "fax-legacy" to "81 (3) 5412-6289", "email-1" to "tokyo@international.gc.ca",
                            "internet" to "https://www.international.gc.ca/tokyo", "facebook" to "https://facebook.example/x",
                            "note-1-title" to "", "note-1-text" to "",
                        ),
                        mapOf(
                            "city" to "Osaka", "type" to "Honorary consul of Canada", "address" to "Osaka Castle Hotel", "tel-legacy" to JSONObject.NULL,
                            "note-2-title" to "Consular services", "note-2-text" to "Not all services.<Br>Contact the Embassy.",
                            "note-3-title" to "Other social media", "note-3-text" to "@CanadaNihon",
                        ),
                    ),
                ),
            ),
        ),
    ).toString()

    @Test
    fun `travel_gc_ca - ingresso e uscita e uffici consolari per intero, solo per i canadesi`() {
        val sections = canadianSections(canadianJson())!!

        assertEquals(listOf("DOGANE", "SICUREZZA"), sections.map { it.category })
        assertEquals(
            listOf("Entry and exit requirements (Government of Canada)", "Getting help (Government of Canada)"),
            sections.map { it.title },
        )
        assertTrue(sections.all { it.sourceUrl == "https://travel.gc.ca/destinations/jp#for-nationality=CA" && isTravelAdvice(it) })
        val footer = "\n\nLast updated by the Government of Canada: September 22, 2026."
        assertEquals("Your passport must be valid.$footer", sections[0].body)
        assertEquals(
            "▸ Consular assistance\nContact the nearest office.\n\n" +
                "▸ Tokyo - Embassy of Canada\n• Address: 3-38 Akasaka, Tokyo\n• Telephone: 81 (3) 5412-6200\n" +
                "• Email: tokyo@international.gc.ca\n• Website: https://www.international.gc.ca/tokyo\n\n" +
                "▸ Osaka - Honorary consul of Canada\n• Address: Osaka Castle Hotel\nConsular services: Not all services. Contact the Embassy.$footer",
            sections[1].body,
        )
    }

    @Test
    fun `travel_gc_ca - il centro di emergenza di Ottawa nominato senza numeri riceve i suoi recapiti`() {
        val help = "<h3>Consular assistance</h3><p>For emergency assistance at any time: contact the " +
            "<a href=\"http://travel.gc.ca/assistance/emergency-assistance\">Emergency Watch and Response Centre</a> in Ottawa.</p>"

        val body = canadianSections(canadianJson(help = help))!![1].body

        assertTrue(
            body.startsWith(
                "▸ Consular assistance\nFor emergency assistance at any time: contact the Emergency Watch and Response Centre in Ottawa.\n" +
                    "• Telephone: +1-613-996-8885 (call collect where available)\n• SMS: +1-613-686-3658\n" +
                    "• Email: SOS@international.gc.ca\n\n▸ Tokyo - Embassy of Canada",
            ),
        )
    }

    @Test
    fun `travel_gc_ca - le sezioni per tutti non cambiano e restano senza ingresso e uffici`() {
        val everyone = travelAdviceSections(canadianJson().replace("\"eng\":{", "\"eng\":{\"security\":\"<p>Be careful.</p>\","))

        assertEquals(listOf("Safety and security (Government of Canada)"), everyone.map { it.title })
        assertTrue(everyone.none { "#for-nationality=" in it.sourceUrl.orEmpty() || "passport" in it.body || "Akasaka" in it.body })
    }

    @Test
    fun `travel_gc_ca - una sola parte basta, nessuna da' lista vuota, JSON illeggibile e' null`() {
        // Senza il testo sull'assistenza consolare restano gli uffici; senza ingresso e uscita, la sola assistenza.
        assertTrue(canadianSections(canadianJson(help = ""))!![1].body.startsWith("▸ Tokyo - Embassy of Canada"))
        assertEquals(listOf("SICUREZZA"), canadianSections(canadianJson(entry = ""))!!.map { it.category })
        assertEquals(emptyList<GuideSectionRow>(), canadianSections(json(mapOf("security" to "<p>Ok.</p>"))))
        assertEquals(null, canadianSections("<html>404</html>"))
        assertEquals(null, canadianSections("""{"data": {"country-iso": "JP"}}"""))
        assertEquals(null, canadianSections(json(mapOf("entry-exit" to "<p>Ok.</p>"), iso = "JPN")))
    }

    @Test
    fun `readTravelAdvice aggiunge le sezioni per i britannici e i canadesi dopo quelle per tutti`() {
        withDir { dir ->
            val gc = File(dir, "jp.json").apply { writeText(canadianJson().replace("\"eng\":{", "\"eng\":{\"security\":\"<p>Be careful.</p>\",")) }
            val fcdo = File(dir, "jp.fcdo.json").apply { writeText(fcdoJson()) }
            val tsv = File(dir, "advice.tsv").apply { writeText("giappone\t${gc.path}\t${fcdo.path}\n") }

            val advice = readTravelAdvice(tsv, { emptyList() }, emptyMap(), day0).getValue("giappone")

            assertEquals(
                listOf(
                    "Safety and security (Government of Canada)", "Entry requirements (UK government)", "Getting help (UK government)",
                    "Entry and exit requirements (Government of Canada)", "Getting help (Government of Canada)",
                ),
                advice.sections.map { it.title },
            )
            assertEquals(TravelAdviceMeta(0, 0, day0), advice.meta)
        }
    }

    @Test
    fun `readTravelAdvice - la pagina FCDO del tipo del primo file da' anche le sezioni per i britannici`() {
        withDir { dir ->
            val fcdo = File(dir, "wf.json").apply { writeText(fcdoJson()) }
            val tsv = File(dir, "advice.tsv").apply { writeText("wallis-futuna\t${fcdo.path}\n") }

            val titles = readTravelAdvice(tsv, { emptyList() }, emptyMap(), day0).getValue("wallis-futuna").sections.map { it.title }

            assertEquals(6, titles.size)
            assertEquals(listOf("Entry requirements (UK government)", "Getting help (UK government)"), titles.takeLast(2))
        }
    }

    @Test
    fun `readTravelAdvice - una pagina non scaricata lascia le sezioni pubblicate della sua nazionalita'`() {
        withDir { dir ->
            val gc = File(dir, "jp.json").apply { writeText(canadianJson().replace("\"eng\":{", "\"eng\":{\"security\":\"<p>Be careful.</p>\",")) }
            val fcdo = File(dir, "jp.fcdo.json").apply { writeText(fcdoJson()) }
            val oldBritish = GuideSectionRow("DOGANE", "Entry requirements (UK government)", "vecchio", "$britishLink/entry-requirements#for-nationality=GB")
            val oldCanadian = GuideSectionRow("DOGANE", "Old (Government of Canada)", "vecchio", "https://travel.gc.ca/destinations/jp#for-nationality=CA")
            val oldEveryone = GuideSectionRow("SICUREZZA", "Old (Government of Canada)", "vecchio", "https://travel.gc.ca/destinations/jp")
            val published = { _: String -> listOf(oldEveryone, oldBritish, oldCanadian) }

            // FCDO non scaricata: restano quelle pubblicate per i britannici, le altre sono nuove.
            val noFcdo = File(dir, "a.tsv").apply { writeText("giappone\t${gc.path}\t\n") }
            val first = readTravelAdvice(noFcdo, published, emptyMap(), day0).getValue("giappone").sections
            assertEquals(listOf("Safety and security (Government of Canada)", "Entry requirements (UK government)"), first.take(2).map { it.title })
            assertEquals(oldBritish, first[1])
            assertEquals(2, first.count { it.sourceUrl.orEmpty().endsWith("#for-nationality=CA") })

            // travel.gc.ca non scaricato: tutto quello pubblicato per tutti e per i canadesi, le sezioni britanniche nuove.
            val noGc = File(dir, "b.tsv").apply { writeText("giappone\t\t${fcdo.path}\n") }
            val second = readTravelAdvice(noGc, published, mapOf("giappone" to TravelAdviceMeta(1, 0, day0.minusDays(1))), day0).getValue("giappone")
            assertEquals(listOf(oldEveryone), second.sections.take(1))
            assertEquals(listOf("Entry requirements (UK government)", "Getting help (UK government)"), second.sections.drop(1).take(2).map { it.title })
            assertEquals(oldCanadian, second.sections.last())
            assertEquals(day0.minusDays(1), second.meta?.fetched)
        }
    }

    // --- isTravelAdvice, withTravelAdvice ---

    @Test
    fun `isTravelAdvice riconosce solo gli URL di travel_gc_ca e dell'FCDO`() {
        fun section(url: String?) = GuideSectionRow("SICUREZZA", "t", "b", url)

        assertTrue(isTravelAdvice(section("https://travel.gc.ca/destinations/it")))
        assertTrue(isTravelAdvice(section("http://travel.gc.ca/destinations/it")))
        assertTrue(isTravelAdvice(section("https://www.gov.uk/foreign-travel-advice/italy/health")))
        assertFalse(isTravelAdvice(section(null)))
        assertFalse(isTravelAdvice(section("https://en.wikivoyage.org/wiki/Italy")))
        assertFalse(isTravelAdvice(section("https://evil.example/travel.gc.ca/x")))
        assertFalse(isTravelAdvice(section("https://www.gov.uk/foreign-travel-advice")))
        assertFalse(isTravelAdvice(section("https://www.gov.uk/other/foreign-travel-advice/x")))
    }

    @Test
    fun `withTravelAdvice sostituisce le sezioni di consigli anche dell'FCDO e lascia le regioni senza consigli`() {
        val wikivoyage = GuideSectionRow("SICUREZZA", "Stay safe", "Watch your bags.")
        val oldFcdo = GuideSectionRow("SALUTE", "Health (UK government)", "vecchio", "${FCDO_ADVICE_PREFIX}x/health")
        val fresh = GuideSectionRow("SICUREZZA", "Fresh", "nuovo", "https://travel.gc.ca/destinations/x")
        val guides = listOf(
            RegionGuide("a", "https://example.org/a", listOf(oldFcdo, wikivoyage)),
            RegionGuide("b", "https://example.org/b", listOf(oldFcdo)),
        ).withTravelAdvice(mapOf("a" to RegionAdvice(listOf(fresh), null)))

        assertEquals(listOf(wikivoyage, fresh), guides[0].sections)
        assertEquals(listOf(oldFcdo), guides[1].sections)
        assertEquals(emptyList<RegionGuide>(), emptyList<RegionGuide>().withTravelAdvice(emptyMap()))
    }

    // --- travel_advice_meta: scrittura, lettura, confronto ---

    private fun metaRows(db: File): List<List<String?>>? = readRows(db, "SELECT regionId, advisory_state, regional, fetched FROM travel_advice_meta ORDER BY 1")

    @Test
    fun `writeTravelAdviceMeta scrive una riga per regione con metadati e salta le altre, e si rilegge uguale`() {
        withDir { dir ->
            val db = File(dir, "guides.db")
            writeTravelAdviceMeta(
                mapOf(
                    "b" to RegionAdvice(emptyList(), TravelAdviceMeta(2, 1, day0)),
                    "a" to RegionAdvice(emptyList(), TravelAdviceMeta(0, 0, day0.minusDays(1))),
                    "senza" to RegionAdvice(emptyList(), null),
                ),
                db,
            )

            assertEquals(listOf(listOf("a", "0", "0", "2026-09-30"), listOf("b", "2", "1", "2026-10-01")), metaRows(db))
            assertEquals(
                mapOf("a" to TravelAdviceMeta(0, 0, day0.minusDays(1)), "b" to TravelAdviceMeta(2, 1, day0)),
                readTravelAdviceMeta(db),
            )
        }
    }

    @Test
    fun `writeTravelAdviceMeta senza consigli crea la tabella vuota e riscrivere non accumula`() {
        withDir { dir ->
            val db = File(dir, "guides.db")
            val advice = mapOf("a" to RegionAdvice(emptyList(), TravelAdviceMeta(1, 0, day0)))
            writeTravelAdviceMeta(emptyMap(), db)
            assertEquals(emptyList<List<String?>>(), metaRows(db))

            writeTravelAdviceMeta(advice, db)
            writeTravelAdviceMeta(advice, db)
            assertEquals(1, metaRows(db)?.size)
        }
    }

    @Test
    fun `readTravelAdviceMeta salta le date illeggibili, tratta i numeri non validi come zero e senza tabella e' vuota`() {
        withDir { dir ->
            val missing = File(dir, "senza.db")
            DriverManager.getConnection("jdbc:sqlite:${missing.path}").use { it.createStatement().use { s -> s.execute("CREATE TABLE altro (x TEXT)") } }
            assertEquals(emptyMap<String, TravelAdviceMeta>(), readTravelAdviceMeta(missing))

            val db = File(dir, "guides.db")
            DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
                conn.createStatement().use { s ->
                    s.execute("CREATE TABLE travel_advice_meta (regionId TEXT, advisory_state TEXT, regional TEXT, fetched TEXT)")
                    s.execute("INSERT INTO travel_advice_meta VALUES ('ok', '2', '1', '2026-10-01')")
                    s.execute("INSERT INTO travel_advice_meta VALUES ('numeri', 'x', NULL, '2026-10-02')")
                    s.execute("INSERT INTO travel_advice_meta VALUES ('data', '1', '0', 'ieri')")
                    s.execute("INSERT INTO travel_advice_meta VALUES ('senza-data', '1', '0', NULL)")
                }
            }

            assertEquals(
                mapOf("ok" to TravelAdviceMeta(2, 1, day0), "numeri" to TravelAdviceMeta(0, 0, LocalDate.of(2026, 10, 2))),
                readTravelAdviceMeta(db),
            )
        }
    }

    @Test
    fun `travelAdviceRiskChanges segnala regioni nuove, livelli e avvisi regionali cambiati, non le date`() {
        val published = mapOf(
            "uguale" to TravelAdviceMeta(1, 0, day0),
            "livello" to TravelAdviceMeta(1, 0, day0),
            "regionale" to TravelAdviceMeta(1, 0, day0),
        )
        val fresh = mapOf(
            "uguale" to TravelAdviceMeta(1, 0, day0.plusDays(30)),
            "livello" to TravelAdviceMeta(3, 0, day0),
            "regionale" to TravelAdviceMeta(1, 1, day0),
            "nuova" to TravelAdviceMeta(2, 1, day0),
        )

        assertEquals(
            listOf(
                "livello: livello 1 -> 3, avvisi regionali 0 -> 0",
                "regionale: livello 1 -> 1, avvisi regionali 0 -> 1",
                "nuova: livello - -> 2, avvisi regionali - -> 1",
            ),
            travelAdviceRiskChanges(published, fresh),
        )
        assertEquals(emptyList<String>(), travelAdviceRiskChanges(published, emptyMap()))
        assertEquals(emptyList<String>(), travelAdviceRiskChanges(emptyMap(), emptyMap()))
    }

    @Test
    fun `keepPublishedTravelAdvice - il limite e' di 7 giorni dalla data piu' recente e il rischio cambiato annulla`() {
        withDir { dir ->
            val published = File(dir, "published.db")
            val fresh = File(dir, "fresh.db")
            writeTravelAdviceMeta(
                mapOf(
                    "a" to RegionAdvice(emptyList(), TravelAdviceMeta(1, 0, day0.minusDays(30))),
                    "b" to RegionAdvice(emptyList(), TravelAdviceMeta(1, 0, day0)),
                ),
                published,
            )
            writeTravelAdviceMeta(
                mapOf("a" to RegionAdvice(emptyList(), TravelAdviceMeta(1, 0, day0)), "b" to RegionAdvice(emptyList(), TravelAdviceMeta(1, 0, day0))),
                fresh,
            )

            assertTrue(keepPublishedTravelAdvice(fresh, published, day0))
            assertTrue(keepPublishedTravelAdvice(fresh, published, day0.plusDays(6)))
            assertFalse(keepPublishedTravelAdvice(fresh, published, day0.plusDays(7)))

            writeTravelAdviceMeta(mapOf("a" to RegionAdvice(emptyList(), TravelAdviceMeta(2, 0, day0))), fresh)
            assertFalse(keepPublishedTravelAdvice(fresh, published, day0))
        }
    }

    @Test
    fun `keepPublishedTravelAdvice - senza consigli pubblicati si considerano vecchi`() {
        withDir { dir ->
            val published = File(dir, "published.db")
            val fresh = File(dir, "fresh.db")
            writeTravelAdviceMeta(emptyMap(), published)
            writeTravelAdviceMeta(mapOf("a" to RegionAdvice(emptyList(), TravelAdviceMeta(1, 0, day0))), fresh)

            assertFalse(keepPublishedTravelAdvice(fresh, published, day0))
        }
    }
}
