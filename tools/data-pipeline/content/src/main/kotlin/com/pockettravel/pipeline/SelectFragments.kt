package com.pockettravel.pipeline

import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Sceglie i frammenti del manifest da unire nella pubblicazione (job "publish" di
 * publish-regions.yml), cosi' una regione costruita non si perde e una regione rotta non blocca le
 * altre:
 * - frammenti di questa run: ogni regione si valida da sola (validateRegion); una non valida si
 *   scarta, le altre passano. I frammenti senza regioni (guide, mappa del mondo, civici) passano
 *   com'e': li controlla la validazione del manifest intero;
 * - frammenti in sospeso (release "region-fragments", caricati da ogni shard subito dopo la regione,
 *   anche da run annullate o morte prima di pubblicare): si recuperano solo se piu' recenti della
 *   regione pubblicata e di quella di questa run, non piu' vecchi di [maxAge] e validi; per una
 *   regione con piu' frammenti in sospeso vince il piu' recente.
 * Ordine dell'elenco: i recuperati dal piu' vecchio, poi quelli di questa run (in mergeManifests
 * l'ultimo vince).
 */
internal data class FragmentChoice(val file: File, val regionId: String?, val outcome: String, val reason: String = "")

internal data class FragmentSelection(val accepted: List<File>, val report: List<FragmentChoice>)

internal fun selectFragments(
    publishedManifestJson: String?,
    runFragments: List<File>,
    pendingFragments: List<File>,
    allowedHosts: Set<String>,
    now: Instant,
    maxAge: Duration,
): FragmentSelection {
    val published = publishedManifestJson?.let { json ->
        val regions = JSONObject(json).optJSONArray("regions")
        (0 until (regions?.length() ?: 0)).associate { i ->
            val region = regions!!.getJSONObject(i)
            region.getString("regionId") to region.optString("updatedAt")
        }
    }.orEmpty()
    val report = mutableListOf<FragmentChoice>()

    // Frammenti di questa run, regione per regione.
    val runAccepted = mutableListOf<File>()
    val runUpdatedAt = mutableMapOf<String, String>()
    for (file in runFragments) {
        val regions = runCatching { JSONObject(file.readText()).optJSONArray("regions") }.getOrElse { error ->
            report += FragmentChoice(file, null, "scartata", "JSON non leggibile: ${error.message}")
            continue
        }
        if (regions == null || regions.length() == 0) {
            runAccepted += file
            continue
        }
        val problems = (0 until regions.length()).mapNotNull { i ->
            runCatching { validateRegion(regions.getJSONObject(i), allowedHosts); null }.getOrElse { it.message ?: "non valida" }
        }
        val regionId = regions.getJSONObject(0).optString("regionId")
        if (problems.isEmpty()) {
            runAccepted += file
            for (i in 0 until regions.length()) {
                val region = regions.getJSONObject(i)
                runUpdatedAt[region.getString("regionId")] = region.optString("updatedAt")
            }
            report += FragmentChoice(file, regionId, "pubblicata")
        } else {
            report += FragmentChoice(file, regionId, "scartata", problems.joinToString("; "))
        }
    }

    // Frammenti in sospeso: una regione ciascuno (come li carica il job "build").
    data class Pending(val file: File, val regionId: String, val updatedAt: Instant)
    val candidates = mutableListOf<Pending>()
    for (file in pendingFragments) {
        val region = runCatching { JSONObject(file.readText()).getJSONArray("regions").getJSONObject(0) }.getOrElse { error ->
            report += FragmentChoice(file, null, "scartata", "frammento non leggibile: ${error.message}")
            continue
        }
        val regionId = region.optString("regionId")
        val updatedAt = runCatching { Instant.parse(region.getString("updatedAt")) }.getOrElse {
            report += FragmentChoice(file, regionId, "scartata", "updatedAt mancante o non valido")
            continue
        }
        val newerThan = listOfNotNull(published[regionId], runUpdatedAt[regionId])
            .mapNotNull { runCatching { Instant.parse(it) }.getOrNull() }
            .maxOrNull()
        when {
            newerThan != null && !updatedAt.isAfter(newerThan) -> report += FragmentChoice(file, regionId, "superata")
            Duration.between(updatedAt, now) > maxAge -> report += FragmentChoice(file, regionId, "scaduta", "piu' vecchia di ${maxAge.toDays()} giorni")
            else -> {
                val problem = runCatching { validateRegion(region, allowedHosts); null }.getOrElse { it.message ?: "non valida" }
                if (problem == null) candidates += Pending(file, regionId, updatedAt)
                else report += FragmentChoice(file, regionId, "scartata", problem)
            }
        }
    }
    val newest = candidates.groupBy { it.regionId }.mapValues { (_, list) -> list.maxBy { it.updatedAt } }
    candidates.filter { newest[it.regionId] !== it }.forEach { report += FragmentChoice(it.file, it.regionId, "superata") }
    val recovered = newest.values.sortedBy { it.updatedAt }
    recovered.forEach { report += FragmentChoice(it.file, it.regionId, "recuperata") }

    return FragmentSelection(recovered.map { it.file } + runAccepted, report)
}

fun main(args: Array<String>) {
    var published: File? = null
    var pagesHost: String? = null
    var outList: File? = null
    var outReport: File? = null
    var maxAgeDays = 7L
    val runFragments = mutableListOf<File>()
    val pendingFragments = mutableListOf<File>()
    var target: MutableList<File>? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--published" -> { published = File(args[++i]); target = null }
            "--pages-host" -> { pagesHost = args[++i]; target = null }
            "--out-list" -> { outList = File(args[++i]); target = null }
            "--out-report" -> { outReport = File(args[++i]); target = null }
            "--max-age-days" -> { maxAgeDays = args[++i].toLong(); target = null }
            "--run" -> target = runFragments
            "--pending" -> target = pendingFragments
            else -> requireNotNull(target) { "argomento inatteso: ${args[i]}" } += File(args[i])
        }
        i++
    }
    require(pagesHost != null && outList != null && outReport != null) {
        "Uso: selectFragments --pages-host <host> --out-list <file> --out-report <file.tsv> [--published <manifest.json>] " +
            "[--max-age-days 7] [--run <frammento.json> ...] [--pending <frammento.json> ...]"
    }
    // Stessi host di ValidateManifest.main.
    val allowedHosts = setOf(pagesHost, "brouter.de", "build.protomaps.com", "github.com")
    val selection = selectFragments(
        published?.takeIf { it.isFile }?.readText(), runFragments, pendingFragments, allowedHosts,
        Instant.now(), Duration.ofDays(maxAgeDays),
    )
    outList.writeText(selection.accepted.joinToString("") { it.path + "\n" })
    outReport.writeText(
        selection.report.joinToString("") { "${it.file.name}\t${it.regionId.orEmpty()}\t${it.outcome}\t${it.reason.replace('\t', ' ').replace('\n', ' ')}\n" },
    )
    val counts = selection.report.groupingBy { it.outcome }.eachCount()
    println("frammenti: ${selection.accepted.size} da unire; " + counts.entries.joinToString(", ") { "${it.key} ${it.value}" })
}
