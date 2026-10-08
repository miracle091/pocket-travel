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
    fun `un sottotitolo escluso toglie anche quelli di livello inferiore`() {
        val html = """
            <h3>Pre-travel vaccines and medications</h3><p>Hepatitis A.</p><h4>Yellow fever</h4><p>Required.</p>
            <h3>Safe food and water precautions</h3><p>Drink bottled water.</p>
            <h3>Dual citizenship</h3><p>Recognized.</p><h3>Keep in Mind...</h3><p>The decision to travel is yours.</p>
        """.trimIndent()

        assertEquals("▸ Safe food and water precautions\nDrink bottled water.", adviceHtmlToText(html))
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
}
