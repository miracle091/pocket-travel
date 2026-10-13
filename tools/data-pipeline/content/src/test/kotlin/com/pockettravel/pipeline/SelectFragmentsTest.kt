package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

class SelectFragmentsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val allowedHosts = setOf("miracle091.github.io", "github.com", "build.protomaps.com")
    private val now = Instant.parse("2026-09-29T12:00:00Z")
    private val week = Duration.ofDays(7)

    private fun region(regionId: String, updatedAt: String, host: String = "github.com") = """
        {
          "regionId": "$regionId", "updatedAt": "$updatedAt",
          "map": { "version": "2026.09.29", "source": { "sourceUrl": "https://build.protomaps.com/20260929.pmtiles",
            "minLon": 12.40, "minLat": 43.89, "maxLon": 12.52, "maxLat": 43.99, "minZoom": 0, "maxZoom": 14 } },
          "routing": { "version": "2026.09.29", "files": [ { "name": "E10_N40.rd5",
            "url": "https://$host/miracle091/pocket-travel/releases/download/region-data-europa/$regionId--E10_N40.rd5", "sizeBytes": 200, "sha256": "${"b".repeat(64)}" } ] },
          "poi": { "version": "2026.09.29", "file": { "name": "poi.db",
            "url": "https://github.com/miracle091/pocket-travel/releases/download/region-data-europa/$regionId--poi.db", "sizeBytes": 100, "sha256": "${"a".repeat(64)}" } }
        }
    """.trimIndent()

    private fun fragment(name: String, vararg regions: String): File =
        tmp.newFile(name).apply { writeText("""{ "manifestVersion": 2, "regions": [${regions.joinToString(",")}] }""") }

    private fun published(vararg regions: Pair<String, String>) =
        """{ "manifestVersion": 2, "regions": [${regions.joinToString(",") { (id, at) -> """{ "regionId": "$id", "updatedAt": "$at" }""" }}] }"""

    private fun outcomes(selection: FragmentSelection) = selection.report.associate { it.file.name to it.outcome }

    @Test
    fun `una regione non valida si scarta e le altre si pubblicano`() {
        val good = fragment("andorra.json", region("andorra", "2026-09-29T10:00:00Z"))
        val bad = fragment("malta.json", region("malta", "2026-09-29T10:00:00Z", host = "evil.example"))
        val guides = tmp.newFile("guides.json").apply { writeText("""{ "manifestVersion": 2, "guides": {} }""") }

        val selection = selectFragments(null, listOf(good, bad, guides), emptyList(), allowedHosts, now, week)

        assertEquals(listOf(good, guides), selection.accepted)
        assertEquals(mapOf("andorra.json" to "pubblicata", "malta.json" to "scartata"), outcomes(selection))
    }

    @Test
    fun `recupera i frammenti in sospeso piu' recenti del pubblicato, prima di quelli della run`() {
        val run = fragment("andorra.json", region("andorra", "2026-09-29T10:00:00Z"))
        val recoveredOld = fragment("p-italia.json", region("italia", "2026-09-28T20:00:00Z"))
        val recoveredNew = fragment("p-francia.json", region("francia", "2026-09-28T22:00:00Z"))
        val alreadyPublished = fragment("p-austria.json", region("austria", "2026-09-27T08:00:00Z"))

        val selection = selectFragments(
            published("italia" to "2026-09-25T08:00:00Z", "austria" to "2026-09-28T08:00:00Z"),
            listOf(run), listOf(recoveredNew, alreadyPublished, recoveredOld), allowedHosts, now, week,
        )

        assertEquals(listOf(recoveredOld, recoveredNew, run), selection.accepted)
        assertEquals("superata", outcomes(selection)["p-austria.json"])
        assertEquals("recuperata", outcomes(selection)["p-italia.json"])
    }

    @Test
    fun `il frammento di questa run vince su quello in sospeso della stessa regione`() {
        val run = fragment("andorra.json", region("andorra", "2026-09-29T10:00:00Z"))
        // Lo stesso frammento caricato sulla release dallo shard: stesso updatedAt.
        val sameUpload = fragment("p-andorra.json", region("andorra", "2026-09-29T10:00:00Z"))
        val older = fragment("p-andorra-old.json", region("andorra", "2026-09-28T10:00:00Z"))

        val selection = selectFragments(null, listOf(run), listOf(sameUpload, older), allowedHosts, now, week)

        assertEquals(listOf(run), selection.accepted)
        assertEquals("superata", outcomes(selection)["p-andorra.json"])
        assertEquals("superata", outcomes(selection)["p-andorra-old.json"])
    }

    @Test
    fun `due frammenti in sospeso della stessa regione, vince il piu' recente`() {
        val older = fragment("p1.json", region("italia", "2026-09-27T10:00:00Z"))
        val newer = fragment("p2.json", region("italia", "2026-09-28T10:00:00Z"))

        val selection = selectFragments(null, emptyList(), listOf(newer, older), allowedHosts, now, week)

        assertEquals(listOf(newer), selection.accepted)
        assertEquals(mapOf("p1.json" to "superata", "p2.json" to "recuperata"), outcomes(selection))
    }

    @Test
    fun `scarta i frammenti in sospeso piu' vecchi di una settimana e quelli non validi`() {
        val expired = fragment("p-old.json", region("italia", "2026-09-20T10:00:00Z"))
        val invalid = fragment("p-bad.json", region("malta", "2026-09-29T09:00:00Z", host = "evil.example"))
        val broken = tmp.newFile("p-broken.json").apply { writeText("non json") }

        val selection = selectFragments(null, emptyList(), listOf(expired, invalid, broken), allowedHosts, now, week)

        assertEquals(emptyList<File>(), selection.accepted)
        assertEquals(mapOf("p-old.json" to "scaduta", "p-bad.json" to "scartata", "p-broken.json" to "scartata"), outcomes(selection))
    }

    private fun reportOf(selection: FragmentSelection, name: String) = selection.report.single { it.file.name == name }

    @Test
    fun `un frammento della run che non e' JSON si scarta con il motivo e senza regionId`() {
        val broken = tmp.newFile("rotto.json").apply { writeText("{ non json") }

        val selection = selectFragments(null, listOf(broken), emptyList(), allowedHosts, now, week)

        assertEquals(emptyList<File>(), selection.accepted)
        val choice = reportOf(selection, "rotto.json")
        assertEquals("scartata", choice.outcome)
        assertNull(choice.regionId)
        assertTrue(choice.reason.startsWith("JSON non leggibile"))
    }

    @Test
    fun `i frammenti senza regioni passano senza comparire nel rapporto`() {
        val noRegions = tmp.newFile("guide.json").apply { writeText("""{ "manifestVersion": 2, "guides": {} }""") }
        val emptyRegions = tmp.newFile("mondo.json").apply { writeText("""{ "manifestVersion": 2, "regions": [] }""") }

        val selection = selectFragments(null, listOf(noRegions, emptyRegions), emptyList(), allowedHosts, now, week)

        assertEquals(listOf(noRegions, emptyRegions), selection.accepted)
        assertEquals(emptyList<FragmentChoice>(), selection.report)
    }

    @Test
    fun `se una regione di un frammento a piu' regioni e' non valida si scarta tutto il file e si elencano i motivi`() {
        val file = fragment(
            "doppio.json",
            region("andorra", "2026-09-29T10:00:00Z"),
            region("malta", "2026-09-29T10:00:00Z", host = "evil.example"),
        )

        val selection = selectFragments(null, listOf(file), emptyList(), allowedHosts, now, week)

        assertEquals(emptyList<File>(), selection.accepted)
        val choice = reportOf(selection, "doppio.json")
        assertEquals("andorra", choice.regionId)
        assertEquals("scartata", choice.outcome)
        assertTrue(choice.reason, choice.reason.contains("evil.example"))
    }

    @Test
    fun `il limite di eta' e' incluso, un secondo oltre scade`() {
        val onTheLimit = fragment("p-limite.json", region("italia", now.minus(week).toString()))
        val oneSecondOver = fragment("p-oltre.json", region("francia", now.minus(week).minusSeconds(1).toString()))

        val selection = selectFragments(null, emptyList(), listOf(onTheLimit, oneSecondOver), allowedHosts, now, week)

        assertEquals(listOf(onTheLimit), selection.accepted)
        assertEquals("recuperata", reportOf(selection, "p-limite.json").outcome)
        assertEquals("scaduta", reportOf(selection, "p-oltre.json").outcome)
        assertTrue(reportOf(selection, "p-oltre.json").reason.contains("7 giorni"))
    }

    @Test
    fun `un frammento in sospeso con la stessa data del pubblicato e' superato, uno piu' recente di un secondo no`() {
        val same = fragment("p-uguale.json", region("italia", "2026-09-28T10:00:00Z"))
        val later = fragment("p-dopo.json", region("francia", "2026-09-28T10:00:01Z"))

        val selection = selectFragments(
            published("italia" to "2026-09-28T10:00:00Z", "francia" to "2026-09-28T10:00:00Z"),
            emptyList(), listOf(same, later), allowedHosts, now, week,
        )

        assertEquals(listOf(later), selection.accepted)
        assertEquals("superata", reportOf(selection, "p-uguale.json").outcome)
    }

    @Test
    fun `un frammento in sospeso senza updatedAt valido o senza regioni si scarta`() {
        val missing = tmp.newFile("p-manca.json").apply { writeText(region("italia", "x").let { """{ "regions": [${it.replace("\"updatedAt\": \"x\",", "")}] }""" }) }
        val garbage = fragment("p-ieri.json", region("francia", "ieri"))
        val noRegion = tmp.newFile("p-vuoto.json").apply { writeText("""{ "regions": [] }""") }

        val selection = selectFragments(null, emptyList(), listOf(missing, garbage, noRegion), allowedHosts, now, week)

        assertEquals(emptyList<File>(), selection.accepted)
        assertEquals("updatedAt mancante o non valido", reportOf(selection, "p-manca.json").reason)
        assertEquals("updatedAt mancante o non valido", reportOf(selection, "p-ieri.json").reason)
        assertEquals("francia", reportOf(selection, "p-ieri.json").regionId)
        assertTrue(reportOf(selection, "p-vuoto.json").reason.startsWith("frammento non leggibile"))
        assertNull(reportOf(selection, "p-vuoto.json").regionId)
    }

    @Test
    fun `un frammento in sospeso piu' recente di quello della run si recupera e va prima`() {
        val run = fragment("andorra.json", region("andorra", "2026-09-29T10:00:00Z"))
        val newer = fragment("p-andorra.json", region("andorra", "2026-09-29T11:00:00Z"))

        val selection = selectFragments(null, listOf(run), listOf(newer), allowedHosts, now, week)

        assertEquals(listOf(newer, run), selection.accepted)
    }

    @Test
    fun `un frammento della run scartato non fa superare quelli in sospeso della stessa regione`() {
        val badRun = fragment("andorra.json", region("andorra", "2026-09-29T10:00:00Z", host = "evil.example"))
        val pending = fragment("p-andorra.json", region("andorra", "2026-09-28T10:00:00Z"))

        val selection = selectFragments(null, listOf(badRun), listOf(pending), allowedHosts, now, week)

        assertEquals(listOf(pending), selection.accepted)
        assertEquals("scartata", reportOf(selection, "andorra.json").outcome)
        assertEquals("recuperata", reportOf(selection, "p-andorra.json").outcome)
    }

    @Test
    fun `un manifest pubblicato senza regioni o con date illeggibili non impedisce il recupero`() {
        val pending = fragment("p-italia.json", region("italia", "2026-09-28T10:00:00Z"))

        listOf("""{ "manifestVersion": 2 }""", """{ "regions": [] }""", published("italia" to "non-una-data")).forEach { json ->
            val selection = selectFragments(json, emptyList(), listOf(pending), allowedHosts, now, week)
            assertEquals(json, listOf(pending), selection.accepted)
        }
    }

    @Test
    fun `un manifest pubblicato malformato fa fallire la selezione invece di recuperare alla cieca`() {
        val pending = fragment("p-italia.json", region("italia", "2026-09-28T10:00:00Z"))

        org.junit.Assert.assertThrows(org.json.JSONException::class.java) {
            selectFragments("non json", emptyList(), listOf(pending), allowedHosts, now, week)
        }
    }

    @Test
    fun `senza frammenti la selezione e' vuota`() {
        val selection = selectFragments(null, emptyList(), emptyList(), allowedHosts, now, week)

        assertEquals(emptyList<File>(), selection.accepted)
        assertEquals(emptyList<FragmentChoice>(), selection.report)
    }

    @Test
    fun `i recuperati sono ordinati dal piu' vecchio al piu' recente a prescindere dall'ordine di ingresso`() {
        val a = fragment("p-a.json", region("a", "2026-09-28T12:00:00Z"))
        val b = fragment("p-b.json", region("b", "2026-09-26T12:00:00Z"))
        val c = fragment("p-c.json", region("c", "2026-09-27T12:00:00Z"))

        val selection = selectFragments(null, emptyList(), listOf(a, b, c), allowedHosts, now, week)

        assertEquals(listOf(b, c, a), selection.accepted)
    }
}
