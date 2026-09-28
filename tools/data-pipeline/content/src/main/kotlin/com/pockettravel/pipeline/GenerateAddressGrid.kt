package com.pockettravel.pipeline

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val CELL_MAX_ZOOM = 14

/** Una fonte citata nella schermata Licenze per i civici della griglia. */
data class GridAttribution(val source: String, val license: String, val url: String)

internal fun parseCellIdParts(id: String): Triple<Int, Int, Int> {
    val parts = id.split('/')
    require(parts.size == 3) { "id di cella non valido: $id" }
    return Triple(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
}

/** true se [ancestor] e' un antenato proprio (non se stessa) di [descendant] nel quadtree Web Mercator. */
internal fun isAncestorCell(ancestor: String, descendant: String): Boolean {
    if (ancestor == descendant) return false
    val (az, ax, ay) = parseCellIdParts(ancestor)
    val (dz, dx, dy) = parseCellIdParts(descendant)
    if (az >= dz) return false
    val shift = dz - az
    return (dx shr shift) == ax && (dy shr shift) == ay
}

/**
 * Unisce l'indice address-grid.json gia' pubblicato (se c'e') con le voci nuove di questa run (una
 * per cella rigenerata o riusata, vedi build-address-cell.sh --entries): le nuove sostituiscono
 * quelle con lo stesso id o si aggiungono. Quando una cella si e' divisa i suoi figli compaiono tra
 * le voci nuove: la voce del genitore, ancora nell'indice pubblicato, va tolta - le celle non si
 * fondono mai, quindi non serve il caso contrario. Risultato ordinato per id
 * (z, x, y): stesso ordine richiesto da ValidateManifest.
 */
fun mergeAddressGridJson(
    version: String,
    publishedIndexJson: String?,
    newEntryJsons: List<String>,
    attributions: List<GridAttribution>,
): String {
    val cells = LinkedHashMap<String, JSONObject>()
    publishedIndexJson?.let { json ->
        val array = JSONObject(json).optJSONArray("cells") ?: JSONArray()
        for (i in 0 until array.length()) {
            val cell = array.getJSONObject(i)
            cells[cell.getString("id")] = cell
        }
    }
    newEntryJsons.forEach { json ->
        val entry = JSONObject(json)
        cells[entry.getString("id")] = entry
    }
    val ids = cells.keys.toList()
    ids.filter { candidate -> ids.any { other -> isAncestorCell(candidate, other) } }.forEach { cells.remove(it) }

    val sortedCells = cells.values.sortedWith(
        compareBy(
            { parseCellIdParts(it.getString("id")).first },
            { parseCellIdParts(it.getString("id")).second },
            { parseCellIdParts(it.getString("id")).third },
        ),
    )

    return JSONObject()
        .put("version", version)
        .put("tileZoom", CELL_MAX_ZOOM)
        .put("cells", JSONArray(sortedCells))
        .put(
            "attributions",
            JSONArray(attributions.map { JSONObject().put("source", it.source).put("license", it.license).put("url", it.url) }),
        )
        .toString(2)
}

/** Frammento manifest (schema v2) con la sola voce "addressGrid", stesso trattamento di "guides"/"worldMap" in mergeManifests. */
fun buildAddressGridFragmentJson(version: String, indexFile: File, url: String): String {
    val entry = localFileEntry(indexFile, "address-grid.json", url)
    return JSONObject()
        .put("manifestVersion", MANIFEST_VERSION)
        .put(
            "addressGrid",
            JSONObject().put("version", version).put("url", url).put("sizeBytes", entry.sizeBytes).put("sha256", entry.sha256),
        )
        .put("regions", JSONArray())
        .toString(2)
}

fun main(args: Array<String>) {
    require(args.size >= 5) {
        "Uso: generateAddressGrid <version> <indexUrl> <output-address-grid.json> <output-manifest-fragment.json> " +
            "<attributions.tsv> [--published <address-grid.json esistente>] [--entries <file1.jsonl> [file2.jsonl ...]]"
    }
    val version = args[0]
    val indexUrl = args[1]
    val outputIndex = File(args[2])
    val outputFragment = File(args[3])
    val attributionsFile = File(args[4])

    var publishedIndexJson: String? = null
    val entryFiles = mutableListOf<File>()
    var rest = args.drop(5)
    while (rest.isNotEmpty()) {
        when (rest[0]) {
            "--published" -> {
                val file = File(rest[1])
                publishedIndexJson = if (file.exists()) file.readText() else null
                rest = rest.drop(2)
            }
            "--entries" -> {
                rest = rest.drop(1)
                while (rest.isNotEmpty() && !rest[0].startsWith("--")) {
                    entryFiles += File(rest[0])
                    rest = rest.drop(1)
                }
            }
            else -> error("Opzione sconosciuta: ${rest[0]}")
        }
    }

    val newEntries = entryFiles.flatMap { it.readLines() }.filter { it.isNotBlank() }
    // attributions.tsv: righe "sorgente<TAB>licenza<TAB>url" (OSM sempre presente, Overture solo se
    // questa run ha usato almeno un dataset ammesso - lo decide il chiamante, non questo tool).
    val attributions = attributionsFile.readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        .filter { it.size >= 3 }.map { GridAttribution(it[0], it[1], it[2]) }

    val index = mergeAddressGridJson(version, publishedIndexJson, newEntries, attributions)
    outputIndex.writeText(index)
    outputFragment.writeText(buildAddressGridFragmentJson(version, outputIndex, indexUrl))
    println("indice civici: ${JSONObject(index).getJSONArray("cells").length()} celle scritte in ${outputIndex.path}")
}
