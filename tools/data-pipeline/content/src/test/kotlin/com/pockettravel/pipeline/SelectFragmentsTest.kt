package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
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
}
