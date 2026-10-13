package com.pockettravel.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeManifestsTest {

    private val mapSource = MapSourceInput("https://build.protomaps.com/20260914.pmtiles", 12.4, 43.89, 12.52, 43.99, 0, 14)

    private fun fragmentFor(regionId: String, displayName: String, version: String = "1"): String =
        buildRegionFragmentJson(
            regionId, displayName, version, "2026-09-14T00:00:00Z", mapSource,
            routingFiles = listOf(ManifestFileEntry("E10_N40.rd5", "https://example.org/$regionId/E10_N40.rd5", 10, "a".repeat(64))),
            poiFile = ManifestFileEntry("poi.db", "https://example.org/$regionId/poi.db", 5, "b".repeat(64)),
        )

    private fun guidesFragment(version: String) = buildGuidesFragmentJson(
        version,
        ManifestFileEntry("guides.db", "https://example.org/guides-$version.db", 3, "c".repeat(64)),
        mapOf("san-marino" to "https://it.wikivoyage.org/wiki/San_Marino"),
    )

    private fun regionsById(merged: JSONObject): Map<String, JSONObject> = merged.getJSONArray("regions").let { regions ->
        (0 until regions.length()).associate { regions.getJSONObject(it).getString("regionId") to regions.getJSONObject(it) }
    }

    @Test
    fun `unisce piu' manifest in uno unico con tutte le regioni`() {
        val merged = JSONObject(mergeManifestJson(listOf(fragmentFor("san-marino", "San Marino"), fragmentFor("italia", "Italia"))))

        assertEquals(2, merged.getInt("manifestVersion"))
        assertEquals(setOf("san-marino", "italia"), regionsById(merged).keys)
    }

    @Test
    fun `scrive il continente su tutte le regioni presenti nella mappa, anche quelle gia' pubblicate`() {
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("san-marino", "San Marino"), fragmentFor("giappone", "Giappone")),
                continents = mapOf("san-marino" to "Europa", "giappone" to "Asia"),
                countryCodes = mapOf("san-marino" to "sm", "giappone" to "jp"),
            ),
        )

        val infoById = regionsById(merged).mapValues { (_, region) -> region.optString("continent") to region.optString("countryCode") }
        assertEquals(mapOf("san-marino" to ("Europa" to "sm"), "giappone" to ("Asia" to "jp")), infoById)
    }

    @Test
    fun `scarta le regioni gia' pubblicate che non sono piu' nell'elenco`() {
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("san-marino", "San Marino"), fragmentFor("antartide", "Antartide"), guidesFragment("1")),
                knownRegionIds = setOf("san-marino", "italia"),
            ),
        )

        assertEquals(setOf("san-marino"), regionsById(merged).keys)
        assertEquals("1", merged.getJSONObject("guides").getString("version"))
    }

    @Test
    fun `una regionId duplicata viene sostituita dall'ultimo manifest fornito`() {
        val merged = JSONObject(mergeManifestJson(listOf(fragmentFor("italia", "Italia", "1"), fragmentFor("italia", "Italia", "2"))))

        val regions = merged.getJSONArray("regions")
        assertEquals(1, regions.length())
        assertEquals("2", regions.getJSONObject(0).getJSONObject("poi").getString("version"))
    }

    @Test
    fun `una regione sostituita da un frammento nuovo tiene continente, codice paese e link wikivoyage`() {
        val giaPubblicato = mergeManifestJson(
            listOf(guidesFragment("1"), fragmentFor("san-marino", "San Marino", "1")),
            continents = mapOf("san-marino" to "Europa"),
            countryCodes = mapOf("san-marino" to "sm"),
        )

        val merged = JSONObject(mergeManifestJson(listOf(giaPubblicato, fragmentFor("san-marino", "San Marino", "2"))))

        val region = regionsById(merged).getValue("san-marino")
        assertEquals("2", region.getJSONObject("poi").getString("version"))
        assertEquals("Europa", region.getString("continent"))
        assertEquals("sm", region.getString("countryCode"))
        assertEquals("https://it.wikivoyage.org/wiki/San_Marino", region.getString("wikivoyageUrl"))
    }

    @Test
    fun `una pubblicazione parziale non fa sparire le regioni ne' le guide non toccate`() {
        val giaPubblicato = mergeManifestJson(
            listOf(guidesFragment("2026.09.20"), fragmentFor("giappone", "Giappone"), fragmentFor("stati-uniti", "Stati Uniti")),
        )

        val merged = JSONObject(mergeManifestJson(listOf(giaPubblicato, fragmentFor("stati-uniti", "Stati Uniti", "2"))))

        val byId = regionsById(merged).mapValues { (_, region) -> region.getJSONObject("routing").getString("version") }
        assertEquals(mapOf("giappone" to "1", "stati-uniti" to "2"), byId)
        assertEquals("2026.09.20", merged.getJSONObject("guides").getString("version"))
    }

    @Test
    fun `le guide dell'ultimo frammento sostituiscono quelle pubblicate e portano il link wikivoyage`() {
        val giaPubblicato = mergeManifestJson(listOf(guidesFragment("2026.09.20"), fragmentFor("san-marino", "San Marino")))

        val merged = JSONObject(mergeManifestJson(listOf(giaPubblicato, guidesFragment("2026.09.23"))))

        assertEquals("2026.09.23", merged.getJSONObject("guides").getString("version"))
        assertEquals("https://it.wikivoyage.org/wiki/San_Marino", regionsById(merged).getValue("san-marino").getString("wikivoyageUrl"))
        assertFalse(merged.has("wikivoyageUrls"))
    }

    @Test
    fun `converte una regione del manifest v1 in mappa, routing e POI da content_db`() {
        val v1 = """
            {
              "manifestVersion": 1,
              "regions": [{
                "regionId": "san-marino", "displayName": "San Marino", "version": "2026.09.20",
                "updatedAt": "2026-09-20T07:28:15Z", "continent": "Europa", "countryCode": "sm",
                "files": [
                  { "name": "content.db", "url": "https://example.org/san-marino--2026.09.20--content.db", "sizeBytes": 81920, "sha256": "${"a".repeat(64)}" },
                  { "name": "E10_N40.rd5", "url": "https://example.org/san-marino--2026.09.20--E10_N40.rd5", "sizeBytes": 80044439, "sha256": "${"b".repeat(64)}" }
                ],
                "mapSource": { "sourceUrl": "https://build.protomaps.com/20260919.pmtiles", "minLon": 12.4, "minLat": 43.89, "maxLon": 12.52, "maxLat": 43.99, "minZoom": 0, "maxZoom": 14 }
              }]
            }
        """.trimIndent()

        val region = regionsById(JSONObject(mergeManifestJson(listOf(v1)))).getValue("san-marino")

        assertFalse(region.has("files"))
        assertFalse(region.has("version"))
        assertEquals("Europa", region.getString("continent"))
        assertEquals("2026.09.20", region.getJSONObject("map").getString("version"))
        assertEquals("https://build.protomaps.com/20260919.pmtiles", region.getJSONObject("map").getJSONObject("source").getString("sourceUrl"))
        val routingFiles = region.getJSONObject("routing").getJSONArray("files")
        assertEquals(1, routingFiles.length())
        assertEquals("E10_N40.rd5", routingFiles.getJSONObject(0).getString("name"))
        val poiFile = region.getJSONObject("poi").getJSONObject("file")
        assertEquals("poi.db", poiFile.getString("name"))
        assertEquals("https://example.org/san-marino--2026.09.20--content.db", poiFile.getString("url"))
        assertEquals(81920, poiFile.getLong("sizeBytes"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fallisce se non ci sono manifest da unire`() {
        mergeManifestJson(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fallisce se un manifest ha una manifestVersion non supportata`() {
        mergeManifestJson(listOf("""{"manifestVersion": 3, "regions": []}"""))
    }

    @Test
    fun `il risultato e' JSON valido riparsabile`() {
        assertTrue(JSONObject(mergeManifestJson(listOf(fragmentFor("san-marino", "San Marino")))).has("regions"))
    }

    @Test
    fun `scrive gruppo e regioni sostituite, e toglie il gruppo a chi non ne ha piu' uno`() {
        val published = JSONObject(fragmentFor("stati-uniti", "Stati Uniti (contigui)"))
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("italia", "Italia").let { JSONObject(it).apply { getJSONArray("regions").getJSONObject(0).put("groupName", "vecchio") }.toString() }, published.toString(), fragmentFor("stati-uniti-ohio", "Stati Uniti - Ohio")),
                knownRegionIds = setOf("italia", "stati-uniti-ohio"),
                groups = mapOf("stati-uniti-ohio" to ("Stati Uniti d'America" to "Ohio")),
                replacedRegions = mapOf("stati-uniti" to "Stati Uniti d'America"),
            ),
        )
        val regions = regionsById(merged)

        assertEquals(setOf("italia", "stati-uniti-ohio"), regions.keys)
        assertEquals("Stati Uniti d'America", regions.getValue("stati-uniti-ohio").getString("groupName"))
        assertEquals("Ohio", regions.getValue("stati-uniti-ohio").getString("groupLabel"))
        assertFalse(regions.getValue("italia").has("groupName"))
        val replaced = merged.getJSONArray("replacedRegions").getJSONObject(0)
        assertEquals("stati-uniti", replaced.getString("regionId"))
        assertEquals("Stati Uniti d'America", replaced.getString("groupName"))
    }

    @Test
    fun `la regione sostituita resta finche' non sono pubblicate tutte le regioni del suo gruppo`() {
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("canada", "Canada"), fragmentFor("canada-yukon", "Canada - Yukon")),
                knownRegionIds = setOf("canada-yukon", "canada-ontario"),
                groups = mapOf("canada-yukon" to ("Canada" to "Yukon"), "canada-ontario" to ("Canada" to "Ontario")),
                replacedRegions = mapOf("canada" to "Canada"),
            ),
        )

        assertEquals(setOf("canada", "canada-yukon"), regionsById(merged).keys)
        assertFalse(merged.has("replacedRegions"))
    }

    @Test
    fun `la mappa del mondo dell'ultimo frammento sostituisce quella pubblicata`() {
        val worldMapFragment = """{"manifestVersion": 2, "worldMap": {"version": "20260926", "maxZoom": 8, "url": "https://github.com/miracle091/pocket-travel/releases/download/world-map/world-20260926-z8.pmtiles", "sizeBytes": 555000000}, "regions": []}"""
        val giaPubblicato = mergeManifestJson(listOf(worldMapFragment, fragmentFor("san-marino", "San Marino")))

        val merged = JSONObject(mergeManifestJson(listOf(giaPubblicato, fragmentFor("italia", "Italia"))))

        assertEquals("20260926", merged.getJSONObject("worldMap").getString("version"))
        assertTrue(regionsById(merged).keys.containsAll(setOf("san-marino", "italia")))
    }

    @Test
    fun `senza un nuovo frammento worldMap resta quello gia' pubblicato`() {
        val worldMapFragment = """{"manifestVersion": 2, "worldMap": {"version": "20260926", "maxZoom": 8, "url": "https://github.com/miracle091/pocket-travel/releases/download/world-map/world-20260926-z8.pmtiles", "sizeBytes": 555000000}, "regions": []}"""
        val giaPubblicato = mergeManifestJson(listOf(worldMapFragment, fragmentFor("san-marino", "San Marino")))

        val merged = JSONObject(mergeManifestJson(listOf(giaPubblicato, fragmentFor("san-marino", "San Marino", "2"))))

        assertEquals("20260926", merged.getJSONObject("worldMap").getString("version"))
    }

    @Test
    fun `scrive minAppVersionCode solo se indicato`() {
        val fragments = listOf(fragmentFor("san-marino", "San Marino"))

        assertEquals(9, JSONObject(mergeManifestJson(fragments, minAppVersionCode = 9)).getInt("minAppVersionCode"))
        assertFalse(JSONObject(mergeManifestJson(fragments)).has("minAppVersionCode"))
    }

    @Test
    fun `porta la mappa di tutte le regioni sulla build Protomaps corrente`() {
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("san-marino", "San Marino"), fragmentFor("italia", "Italia")),
                mapSourceUrl = "https://build.protomaps.com/20260925.pmtiles",
            ),
        )

        regionsById(merged).values.forEach { region ->
            assertEquals("https://build.protomaps.com/20260925.pmtiles", region.getJSONObject("map").getJSONObject("source").getString("sourceUrl"))
            assertEquals("1", region.getJSONObject("map").getString("version"))
        }
    }

    private fun addressGridFragment(version: String) =
        """{ "manifestVersion": 2, "addressGrid": { "version": "$version", "url": "https://example.org/address-grid-$version.json", "sizeBytes": 300000, "sha256": "${"d".repeat(64)}" }, "regions": [] }"""

    @Test
    fun `l'indice dei civici dell'ultimo frammento sostituisce quello pubblicato, come le guide`() {
        val giaPubblicato = mergeManifestJson(listOf(guidesFragment("1"), fragmentFor("san-marino", "San Marino"), addressGridFragment("2026.09.30.1")))

        val merged = JSONObject(mergeManifestJson(listOf(giaPubblicato, addressGridFragment("2026.10.01.1"))))

        assertEquals("2026.10.01.1", merged.getJSONObject("addressGrid").getString("version"))
        assertEquals(setOf("san-marino"), regionsById(merged).keys)
    }

    @Test
    fun `l'indice delle reti si porta avanti e l'ultimo frammento lo sostituisce`() {
        val transit = { version: String ->
            """{ "manifestVersion": 2, "transit": { "version": "$version", "url": "https://example.org/transit.json", "sizeBytes": 900, "sha256": "${"e".repeat(64)}" }, "regions": [] }"""
        }
        val giaPubblicato = mergeManifestJson(listOf(guidesFragment("1"), fragmentFor("san-marino", "San Marino"), transit("2026.09.29.1")))

        assertEquals("2026.09.29.1", JSONObject(mergeManifestJson(listOf(giaPubblicato, fragmentFor("san-marino", "San Marino", "2")))).getJSONObject("transit").getString("version"))
        assertEquals("2026.09.30.1", JSONObject(mergeManifestJson(listOf(giaPubblicato, transit("2026.09.30.1")))).getJSONObject("transit").getString("version"))
    }

    @Test
    fun `senza un nuovo frammento l'indice dei civici pubblicato resta com'e'`() {
        val giaPubblicato = mergeManifestJson(listOf(guidesFragment("1"), fragmentFor("san-marino", "San Marino"), addressGridFragment("2026.09.30.1")))

        val merged = JSONObject(mergeManifestJson(listOf(giaPubblicato, fragmentFor("san-marino", "San Marino", "2"))))

        assertEquals("2026.09.30.1", merged.getJSONObject("addressGrid").getString("version"))
    }

    @Test
    fun `nome inglese delle regioni di un gruppo dal titolo di Wikivoyage`() {
        assertEquals("North Carolina", englishLabelOf("North_Carolina"))
        assertEquals("Georgia", englishLabelOf("Georgia_(U.S._state)"))
        assertEquals("New York", englishLabelOf("New_York_(state)"))
        assertEquals("Washington, D.C.", englishLabelOf("Washington,_D.C."))
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("stati-uniti-carolina-del-nord", "Stati Uniti - Carolina del Nord")),
                groups = mapOf("stati-uniti-carolina-del-nord" to ("Stati Uniti d'America" to "Carolina del Nord")),
                groupLabelsEn = mapOf("stati-uniti-carolina-del-nord" to "North Carolina"),
            ),
        )
        assertEquals("North Carolina", merged.getJSONArray("regions").getJSONObject(0).getString("groupLabelEn"))
    }

    @Test
    fun `la guida inglese passa nel manifest unito come quella italiana`() {
        val en = """{"manifestVersion": 2, "regions": [], "guidesEn": {"version": "2026.09.28", "file": {"name": "guides.db", "url": "https://x/guides-en.db.xz", "sizeBytes": 1, "sha256": "a"}}}"""
        val merged = JSONObject(mergeManifestJson(listOf(fragmentFor("san-marino", "San Marino"), en)))
        assertEquals("2026.09.28", merged.getJSONObject("guidesEn").getString("version"))
    }

    private fun v1Region(
        files: String = """
            { "name": "content.db", "url": "https://example.org/r--content.db", "sizeBytes": 10, "sha256": "${"a".repeat(64)}" },
            { "name": "E10_N40.rd5", "url": "https://example.org/r--E10_N40.rd5", "sizeBytes": 20, "sha256": "${"b".repeat(64)}" }
        """.trimIndent(),
        extra: String = "",
        mapSourceJson: String = """"mapSource": { "sourceUrl": "https://build.protomaps.com/1.pmtiles", "minLon": 1, "minLat": 2, "maxLon": 3, "maxLat": 4, "minZoom": 0, "maxZoom": 14 }""",
    ) = """
        { "manifestVersion": 1, "regions": [{
          "regionId": "r", "displayName": "Citta' dell'Aquila - Åland", "version": "2026.09.20", "updatedAt": "2026-09-20T00:00:00Z",
          "files": [$files], $mapSourceJson $extra
        }] }
    """.trimIndent()

    @Test
    fun `una regione v1 senza content_db o senza mapSource fa fallire l'unione`() {
        val onlyRd5 = """{ "name": "E10_N40.rd5", "url": "https://example.org/x.rd5", "sizeBytes": 20, "sha256": "${"b".repeat(64)}" }"""
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { mergeManifestJson(listOf(v1Region(files = onlyRd5))) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            mergeManifestJson(listOf(v1Region(mapSourceJson = """"altro": 1""")))
        }
    }

    @Test
    fun `una regione v1 non perde il nome unicode e ignora metadati vuoti`() {
        val extra = ", \"continent\": \"\", \"countryCode\": \"  \", \"wikivoyageUrl\": \"\""
        val region = regionsById(JSONObject(mergeManifestJson(listOf(v1Region(extra = extra))))).getValue("r")

        assertEquals("Citta' dell'Aquila - Åland", region.getString("displayName"))
        assertFalse(region.has("continent"))
        assertFalse(region.has("countryCode"))
        assertFalse(region.has("wikivoyageUrl"))
    }

    @Test
    fun `convertV1Region non modifica la regione di partenza e non include content_db nel routing`() {
        val source = regionsInput(v1Region())
        val converted = convertV1Region(source)

        assertEquals("content.db", source.getJSONArray("files").getJSONObject(0).getString("name"))
        assertEquals("poi.db", converted.getJSONObject("poi").getJSONObject("file").getString("name"))
        val routingNames = converted.getJSONObject("routing").getJSONArray("files").let { files -> (0 until files.length()).map { files.getJSONObject(it).getString("name") } }
        assertEquals(listOf("E10_N40.rd5"), routingNames)
        assertEquals("2026.09.20", converted.getJSONObject("routing").getString("version"))
    }

    private fun regionsInput(manifest: String): JSONObject = JSONObject(manifest).getJSONArray("regions").getJSONObject(0)

    @Test
    fun `un manifest v1 senza regioni e' accettato e produce un manifest v2 vuoto`() {
        val merged = JSONObject(mergeManifestJson(listOf("""{"manifestVersion": 1, "regions": []}""")))

        assertEquals(2, merged.getInt("manifestVersion"))
        assertEquals(0, merged.getJSONArray("regions").length())
        assertFalse(merged.has("guides"))
        assertFalse(merged.has("replacedRegions"))
    }

    @Test
    fun `un JSON malformato o senza regioni fa fallire l'unione`() {
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { mergeManifestJson(listOf("non e' json")) }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { mergeManifestJson(listOf("""{"manifestVersion": 2}""")) }
        org.junit.Assert.assertThrows(org.json.JSONException::class.java) { mergeManifestJson(listOf("""{"regions": []}""")) }
    }

    @Test
    fun `una regione sostituita da un frammento mantiene la posizione originale nell'elenco`() {
        val merged = JSONObject(
            mergeManifestJson(listOf(fragmentFor("a", "A"), fragmentFor("b", "B"), fragmentFor("c", "C"), fragmentFor("a", "A", "2"))),
        )

        val ids = merged.getJSONArray("regions").let { regions -> (0 until regions.length()).map { regions.getJSONObject(it).getString("regionId") } }
        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals("2", regionsById(merged).getValue("a").getJSONObject("poi").getString("version"))
    }

    @Test
    fun `i metadati nuovi battono quelli vecchi e quelli dell'elenco battono entrambi`() {
        val old = mergeManifestJson(
            listOf(fragmentFor("san-marino", "San Marino")),
            continents = mapOf("san-marino" to "Europa"), countryCodes = mapOf("san-marino" to "sm"),
        )
        val fresh = JSONObject(fragmentFor("san-marino", "San Marino", "2")).apply {
            getJSONArray("regions").getJSONObject(0).put("continent", "Asia")
        }.toString()

        val kept = regionsById(JSONObject(mergeManifestJson(listOf(old, fresh)))).getValue("san-marino")
        assertEquals("Asia", kept.getString("continent"))
        assertEquals("sm", kept.getString("countryCode"))

        val overridden = regionsById(JSONObject(mergeManifestJson(listOf(old, fresh), continents = mapOf("san-marino" to "Europa")))).getValue("san-marino")
        assertEquals("Europa", overridden.getString("continent"))
    }

    @Test
    fun `l'ultimo link wikivoyage vince e i link di regioni assenti sono ignorati`() {
        val first = buildGuidesFragmentJson(
            "1", ManifestFileEntry("guides.db", "https://example.org/g1.db", 3, "c".repeat(64)),
            mapOf("san-marino" to "https://it.wikivoyage.org/wiki/Vecchio", "assente" to "https://it.wikivoyage.org/wiki/Assente"),
        )
        val merged = JSONObject(mergeManifestJson(listOf(fragmentFor("san-marino", "San Marino"), first, guidesFragment("2"))))

        assertEquals("https://it.wikivoyage.org/wiki/San_Marino", regionsById(merged).getValue("san-marino").getString("wikivoyageUrl"))
        assertEquals(setOf("san-marino"), regionsById(merged).keys)
    }

    @Test
    fun `senza elenco completo il gruppo vecchio resta, con l'elenco sparisce insieme all'etichetta inglese`() {
        val published = JSONObject(fragmentFor("italia", "Italia")).apply {
            getJSONArray("regions").getJSONObject(0).put("groupName", "G").put("groupLabel", "L").put("groupLabelEn", "LE")
        }.toString()

        val untouched = regionsById(JSONObject(mergeManifestJson(listOf(published)))).getValue("italia")
        assertEquals("G", untouched.getString("groupName"))
        assertEquals("LE", untouched.getString("groupLabelEn"))

        val cleaned = regionsById(JSONObject(mergeManifestJson(listOf(published), knownRegionIds = setOf("italia")))).getValue("italia")
        assertFalse(cleaned.has("groupName"))
        assertFalse(cleaned.has("groupLabel"))
        assertFalse(cleaned.has("groupLabelEn"))
    }

    @Test
    fun `l'etichetta inglese si scrive solo per le regioni con gruppo`() {
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("a", "A"), fragmentFor("b", "B")),
                groups = mapOf("a" to ("G" to "A1")),
                groupLabelsEn = mapOf("a" to "A one", "b" to "B one"),
            ),
        )
        val regions = regionsById(merged)

        assertEquals("A one", regions.getValue("a").getString("groupLabelEn"))
        assertFalse(regions.getValue("b").has("groupLabelEn"))
    }

    @Test
    fun `una regione sostituita il cui gruppo non e' definito resta e non entra in replacedRegions`() {
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("canada", "Canada"), fragmentFor("italia", "Italia")),
                knownRegionIds = setOf("italia"),
                replacedRegions = mapOf("canada" to "Canada"),
            ),
        )

        assertEquals(setOf("canada", "italia"), regionsById(merged).keys)
        assertFalse(merged.has("replacedRegions"))
    }

    @Test
    fun `una regione sostituita con gruppo completo sparisce e viene elencata`() {
        val merged = JSONObject(
            mergeManifestJson(
                listOf(fragmentFor("canada", "Canada"), fragmentFor("canada-yukon", "Yukon"), fragmentFor("canada-ontario", "Ontario")),
                knownRegionIds = setOf("canada-yukon", "canada-ontario"),
                groups = mapOf("canada-yukon" to ("Canada" to "Yukon"), "canada-ontario" to ("Canada" to "Ontario")),
                replacedRegions = mapOf("canada" to "Canada"),
            ),
        )

        assertEquals(setOf("canada-yukon", "canada-ontario"), regionsById(merged).keys)
        assertEquals(1, merged.getJSONArray("replacedRegions").length())
    }

    @Test
    fun `mapSourceUrl si applica anche alle regioni v1 convertite e non tocca le regioni senza mappa`() {
        val merged = JSONObject(mergeManifestJson(listOf(v1Region()), mapSourceUrl = "https://build.protomaps.com/2.pmtiles"))
        val source = regionsById(merged).getValue("r").getJSONObject("map").getJSONObject("source")

        assertEquals("https://build.protomaps.com/2.pmtiles", source.getString("sourceUrl"))
        assertEquals(14, source.getInt("maxZoom"))

        val untouched = JSONObject(mergeManifestJson(listOf(v1Region())))
        assertEquals("https://build.protomaps.com/1.pmtiles", regionsById(untouched).getValue("r").getJSONObject("map").getJSONObject("source").getString("sourceUrl"))
    }

    @Test
    fun `l'unione e' idempotente se si rilegge il proprio risultato`() {
        val first = mergeManifestJson(
            listOf(guidesFragment("1"), fragmentFor("san-marino", "San Marino"), fragmentFor("italia", "Italia")),
            continents = mapOf("san-marino" to "Europa"), minAppVersionCode = 9,
        )
        val second = mergeManifestJson(listOf(first), continents = mapOf("san-marino" to "Europa"), minAppVersionCode = 9)

        assertTrue(JSONObject(first).similar(JSONObject(second)))
    }

    @Test
    fun `minAppVersionCode del manifest pubblicato non si propaga se non e' indicato di nuovo`() {
        val old = mergeManifestJson(listOf(fragmentFor("san-marino", "San Marino")), minAppVersionCode = 9)
        val merged = JSONObject(mergeManifestJson(listOf(old)))

        assertFalse(merged.has("minAppVersionCode"))
    }

    @Test
    fun `englishLabelOf gestisce casi limite`() {
        assertEquals("", englishLabelOf(""))
        assertEquals("Paris", englishLabelOf("Paris"))
        assertEquals("A (b)", englishLabelOf("A_(b)_(c)"))
        assertEquals("", englishLabelOf("(solo parentesi)"))
        assertEquals("Citta' del Messico", englishLabelOf("  Citta'_del_Messico  "))
        assertEquals("Åland", englishLabelOf("Åland_(region)"))
    }
}
