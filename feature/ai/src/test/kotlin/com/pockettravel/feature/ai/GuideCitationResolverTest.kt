package com.pockettravel.feature.ai

import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.GuideSourceTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideCitationResolverTest {

    private val salute = GuideSection(
        regionId = "italia",
        category = GuideCategory.SALUTE,
        title = "Salute",
        body = "L'acqua del rubinetto è potabile.",
        sourceUrl = "https://it.wikivoyage.org/wiki/Italia#for-nationality=IT",
    )
    private val cibo = salute.copy(category = GuideCategory.CIBO_BEVANDE, title = "A tavola")
    private val storiaRoma = CitySection(
        regionId = "italia",
        city = "Roma",
        category = GuideCategory.STORIA,
        title = "Storia",
        body = "Fondata nel 753 a.C.",
        sourceUrl = "https://it.wikipedia.org/wiki/Roma",
    )
    private val duomoMilano = storiaRoma.copy(city = "Milano", category = GuideCategory.COSA_VEDERE, title = "Duomo", sourceUrl = "https://it.wikivoyage.org/wiki/Milano")

    private suspend fun resolve(citation: String, loadedCities: MutableList<String> = mutableListOf()): GuideSourceTarget? = resolveGuideCitation(
        citation = citation,
        answer = "risposta",
        countrySections = { listOf(salute, cibo) },
        cityNames = { listOf("Roma", "Milano") },
        citySections = { city ->
            loadedCities += city
            listOf(storiaRoma, duomoMilano).filter { it.city == city }
        },
    )

    @Test
    fun `la fonte di una sezione della nazione porta a quella sezione`() = runTest {
        val target = resolve(salute.citation("it"))

        assertEquals(GuideSourceTarget(null, GuideCategory.SALUTE, "Salute", "risposta"), target)
    }

    @Test
    fun `una fonte salvata in inglese si riconosce con la lingua attuale in italiano`() = runTest {
        assertEquals(GuideCategory.CIBO_BEVANDE, resolve(cibo.citation("en"))?.category)
    }

    @Test
    fun `la fonte di una sezione di citta' porta a quella citta' e carica solo quella nominata`() = runTest {
        val loaded = mutableListOf<String>()

        val target = resolve(duomoMilano.citation("it"), loaded)

        assertEquals(GuideSourceTarget("Milano", GuideCategory.COSA_VEDERE, "Duomo", "risposta"), target)
        assertEquals(listOf("Milano"), loaded)
    }

    @Test
    fun `una fonte che non e' di nessuna sezione installata non si risolve`() = runTest {
        assertNull(resolve("Fonte: Wikivoyage, sezione Altro — https://it.wikivoyage.org/wiki/Altro"))
        assertNull(resolve("Fonte: Wikivoyage, sezione Duomo (Torino) — https://it.wikivoyage.org/wiki/Torino"))
        assertNull(resolve("Fonte: numeri di emergenza (Travel.gc.ca, Wikipedia, Wikidata, gov.uk)"))
        assertNull(resolve("Source: weather forecasts by Open-Meteo.com"))
    }

    @Test
    fun `piu' fonti caricano una volta sola nazione, nomi e sezioni di ogni citta'`() = runTest {
        var countryLoads = 0
        var nameLoads = 0
        val loaded = mutableListOf<String>()
        val citations = listOf(salute.citation("it"), duomoMilano.citation("it"), storiaRoma.citation("it"), duomoMilano.citation("it"))

        val targets = resolveGuideCitations(
            citations = citations,
            answer = "risposta",
            countrySections = { countryLoads++; listOf(salute, cibo) },
            cityNames = { nameLoads++; listOf("Roma", "Milano") },
            citySections = { city ->
                loaded += city
                listOf(storiaRoma, duomoMilano).filter { it.city == city }
            },
        )

        assertEquals(3, targets.size)
        assertEquals(1, countryLoads)
        assertEquals(1, nameLoads)
        assertEquals(listOf("Milano", "Roma"), loaded)
    }
}
