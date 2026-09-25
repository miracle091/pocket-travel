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
    fun `scarta le regioni gia' pubblicate che non sono piu' nel lotto pilota`() {
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
}
