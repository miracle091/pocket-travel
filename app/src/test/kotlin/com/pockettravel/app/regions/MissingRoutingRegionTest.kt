package com.pockettravel.app.regions

import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.feature.map.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissingRoutingRegionTest {

    private fun file(name: String) = RegionManifestFile(name, "https://github.com/$name", 1_000, "a".repeat(64))

    private fun region(id: String, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, country: String = id.take(2)) = RegionManifestEntry(
        regionId = id,
        displayName = id.replaceFirstChar { it.uppercase() },
        updatedAt = "2026-09-23T00:00:00Z",
        map = MapPackageEntry("m1", MapExtractionSource("https://build.protomaps.com/x.pmtiles", minLon, minLat, maxLon, maxLat, 0, 14)),
        routing = RoutingPackageEntry("r1", listOf(file("E10_N40.rd5"))),
        poi = PoiPackageEntry("p1", file("poi.db")),
        countryCode = country,
    )

    private val italia = region("italia", 6.6, 35.5, 18.5, 47.1)
    private val sanMarino = region("san-marino", 12.40, 43.89, 12.52, 43.99, "sm")
    private val svizzera = region("svizzera", 5.9, 45.8, 10.5, 47.8, "ch")
    private val all = listOf(italia, sanMarino, svizzera)

    private val rimini = RoutePoint(44.059, 12.568)
    private val cittaDiSanMarino = RoutePoint(43.9356, 12.4473)
    private val milano = RoutePoint(45.4642, 9.19)
    private val zurigo = RoutePoint(47.3769, 8.5417)

    // Il paese di un punto per le prove: quello della regione piu' piccola che lo contiene (i confini veri li da'
    // CountryLocator), null in mare.
    private fun paeseDi(catalogo: List<RegionManifestEntry>, mare: (RoutePoint) -> Boolean = { false }): (RoutePoint) -> String? = { p ->
        if (mare(p)) null else catalogo.filter { p.longitude in it.map.source.minLon..it.map.source.maxLon && p.latitude in it.map.source.minLat..it.map.source.maxLat }
            .minByOrNull { (it.map.source.maxLon - it.map.source.minLon) * (it.map.source.maxLat - it.map.source.minLat) }?.countryCode
    }

    @Test
    fun `un punto in due riquadri, senza percorsi installati, la regione piu' piccola`() {
        assertEquals(listOf(sanMarino), missingRoutingRegions(all, emptySet(), listOf(cittaDiSanMarino), paeseDi(all)))
    }

    @Test
    fun `il riquadro dell'Italia copre Rimini ma non San Marino, che e' un altro paese`() {
        assertEquals(emptyList<RegionManifestEntry>(), missingRoutingRegions(all, setOf("italia"), listOf(rimini), paeseDi(all)))
        assertEquals(listOf(sanMarino), missingRoutingRegions(all, setOf("italia"), listOf(rimini, cittaDiSanMarino), paeseDi(all)))
    }

    @Test
    fun `con l'arrivo in Svizzera e i percorsi dell'Italia manca la Svizzera`() {
        assertEquals(listOf(svizzera), missingRoutingRegions(all, setOf("italia"), listOf(milano, zurigo), paeseDi(all)))
    }

    @Test
    fun `senza percorsi mancano tutte le regioni, in ordine dalla partenza`() {
        assertEquals(listOf(italia, svizzera), missingRoutingRegions(all, emptySet(), listOf(milano, zurigo), paeseDi(all)))
    }

    @Test
    fun `da San Marino a Riga mancano tutti i paesi in mezzo, una volta ciascuno e in ordine`() {
        val austria = region("austria", 9.5, 46.4, 17.2, 49.0)
        val cechia = region("cechia", 12.1, 48.5, 18.9, 51.1)
        val polonia = region("polonia", 14.1, 49.0, 24.2, 54.9)
        val lituania = region("lituania", 20.9, 53.9, 26.9, 56.5)
        val lettonia = region("lettonia", 20.9, 55.6, 28.3, 58.1)
        val catalogo = listOf(italia, sanMarino, austria, cechia, polonia, lituania, lettonia)
        val riga = RoutePoint(56.952, 24.1147)
        val linea = (0..100).map { i ->
            val t = i / 100.0
            RoutePoint(cittaDiSanMarino.latitude + (riga.latitude - cittaDiSanMarino.latitude) * t, cittaDiSanMarino.longitude + (riga.longitude - cittaDiSanMarino.longitude) * t)
        }

        assertEquals(
            listOf(italia, austria, cechia, polonia, lituania),
            missingRoutingRegions(catalogo, setOf("san-marino", "lettonia"), linea, paeseDi(catalogo)),
        )
    }

    @Test
    fun `un punto in mare non conta, anche se cade nel riquadro della Svezia`() {
        val svezia = region("svezia", 11.0, 55.3, 24.2, 69.1)
        val lettonia = region("lettonia", 20.9, 55.6, 28.3, 58.1)
        val catalogo = listOf(svezia, lettonia)
        val baltico = RoutePoint(55.5, 20.0)
        val riga = RoutePoint(56.952, 24.1147)

        assertEquals(emptyList<RegionManifestEntry>(), missingRoutingRegions(catalogo, setOf("lettonia"), listOf(baltico, riga), paeseDi(catalogo) { it == baltico }))
        assertEquals(listOf(svezia), missingRoutingRegions(catalogo, setOf("lettonia"), listOf(baltico, riga), paeseDi(catalogo)))
    }

    @Test
    fun `con i percorsi dell'Italia l'Austria manca, anche se il riquadro dell'Italia la contiene`() {
        val austria = region("austria", 9.5, 46.4, 17.2, 49.0)
        val catalogo = listOf(italia, austria)
        val innsbruck = RoutePoint(47.26, 11.39)

        assertEquals(listOf(austria), missingRoutingRegions(catalogo, setOf("italia"), listOf(milano, innsbruck), paeseDi(catalogo)))
    }

    @Test
    fun `un punto di un paese fuori dal suo riquadro non prende quello di un altro paese, le Canarie si`() {
        val svezia = region("svezia", 11.03, 55.36, 23.90, 69.11, "se")
        val canarie = region("isole-canarie", -18.20, 27.60, -13.30, 29.45, "ic")
        val penisolaDeiCuri = RoutePoint(55.5, 21.0)
        val tenerife = RoutePoint(28.3, -16.5)
        val paese = { p: RoutePoint -> if (p == penisolaDeiCuri) "lt" else "es" }

        assertEquals(listOf(canarie), missingRoutingRegions(listOf(svezia, canarie), emptySet(), listOf(penisolaDeiCuri, tenerife), paese))
        // Con la Lituania nel catalogo, il punto fuori dal suo riquadro e' suo (il riquadro piu' vicino del paese).
        val lituania = region("lituania", 21.06, 53.91, 26.59, 56.37, "lt")
        assertEquals(listOf(lituania, canarie), missingRoutingRegions(listOf(svezia, canarie, lituania), emptySet(), listOf(penisolaDeiCuri, tenerife), paese))
    }

    @Test
    fun `un punto in nessuna regione del manifest si salta`() {
        val newYork = RoutePoint(40.7, -74.0)

        assertEquals(listOf(svizzera), missingRoutingRegions(all, setOf("italia"), listOf(newYork, milano, zurigo), paeseDi(all)))
    }

    // Confini e centri (approssimati) dei paesi tra San Marino e la Lettonia, come li darebbe CountryLocator.
    private val vicini = mapOf(
        "sm" to setOf("it"), "it" to setOf("sm", "at", "si", "ch"), "si" to setOf("it", "at", "hr"), "hr" to setOf("si"),
        "at" to setOf("it", "si", "cz", "ch"), "ch" to setOf("it", "at"), "cz" to setOf("at", "pl"), "pl" to setOf("cz", "lt", "ru", "by"),
        "lt" to setOf("pl", "lv", "ru", "by"), "lv" to setOf("lt", "ru", "by"), "ru" to setOf("pl", "lt", "lv", "by"), "by" to setOf("pl", "lt", "lv", "ru"),
    )
    private val centri = mapOf(
        "sm" to RoutePoint(43.94, 12.46), "it" to RoutePoint(42.0, 12.6), "si" to RoutePoint(46.1, 14.8), "hr" to RoutePoint(44.5, 16.4),
        "at" to RoutePoint(47.7, 13.3), "ch" to RoutePoint(46.8, 8.2), "cz" to RoutePoint(49.8, 15.5), "pl" to RoutePoint(52.0, 19.1),
        "lt" to RoutePoint(55.2, 23.9), "lv" to RoutePoint(56.9, 24.6), "ru" to RoutePoint(61.0, 100.0), "by" to RoutePoint(53.7, 28.0),
    )

    @Test
    fun `il cammino via terra da San Marino alla Lettonia passa per i confini, non per la Russia o la Bielorussia`() {
        assertEquals(listOf("sm", "it", "at", "cz", "pl", "lt", "lv"), landPath("sm", "lv", vicini, centri) { true })
        // Senza la Lituania nel catalogo si passa dalla Bielorussia, se c'e'.
        assertEquals(listOf("sm", "it", "at", "cz", "pl", "by", "lv"), landPath("sm", "lv", vicini, centri) { it != "lt" })
        assertNull(landPath("sm", "lv", vicini, centri) { it != "pl" })
    }

    @Test
    fun `da San Marino a Riga i paesi del cammino via terra, non quelli che la linea sfiora sul mare`() {
        val croazia = region("croazia", 13.4, 42.3, 19.5, 46.6, "hr")
        val slovenia = region("slovenia", 13.3, 45.4, 16.6, 46.9, "si")
        val austria = region("austria", 9.5, 46.4, 17.2, 49.0, "at")
        val cechia = region("cechia", 12.1, 48.5, 18.9, 51.1, "cz")
        val polonia = region("polonia", 14.1, 49.0, 24.2, 54.9, "pl")
        val kaliningrad = region("russia-kaliningrad", 19.5, 54.25, 22.9, 55.35, "ru")
        val lituania = region("lituania", 21.06, 53.91, 26.59, 56.37, "lt")
        val lettonia = region("lettonia", 21.06, 55.62, 28.18, 57.97, "lv")
        val catalogo = listOf(italia, sanMarino, croazia, slovenia, austria, cechia, polonia, kaliningrad, lituania, lettonia)
        val liepaja = RoutePoint(56.6, 21.2)
        val linea = (0..200).map { i ->
            val t = i / 200.0
            RoutePoint(cittaDiSanMarino.latitude + (liepaja.latitude - cittaDiSanMarino.latitude) * t, cittaDiSanMarino.longitude + (liepaja.longitude - cittaDiSanMarino.longitude) * t)
        }
        // La linea attraversa l'Adriatico, l'Istria (Croazia), la Slovenia, Kaliningrad e il Baltico, senza toccare Italia e
        // Lituania: il cammino via terra e' San Marino, Italia, Austria, Cechia, Polonia, Lituania (la Slovenia non serve).
        val paese = { p: RoutePoint ->
            when {
                p.latitude < 43.99 -> "sm"
                p.latitude < 45.0 -> null
                p.latitude < 45.4 -> "hr"
                p.latitude < 46.4 -> "si"
                p.latitude < 48.6 -> "at"
                p.latitude < 50.0 -> "cz"
                p.latitude < 54.4 -> "pl"
                p.latitude < 55.3 -> "ru"
                p.latitude < 56.0 -> null
                else -> "lv"
            }
        }

        assertEquals(
            listOf(italia, austria, cechia, polonia, lituania),
            missingRoutingRegions(catalogo, setOf("san-marino", "lettonia"), linea, paese, vicini, centri),
        )
    }
}
