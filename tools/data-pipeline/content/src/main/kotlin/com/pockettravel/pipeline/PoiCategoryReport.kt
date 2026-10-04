package com.pockettravel.pipeline

import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.poi.poiCategoryOf
import com.pockettravel.core.poi.poiPackageOf
import java.io.File
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * Rilegge dei poi.db gia' pubblicati con le regole attuali di core:poi: quanti POI per categoria restano
 * pubblicati e quali tag OSM non si pubblicherebbero piu' (per controllare una modifica alle regole
 * prima di ripubblicare le regioni). Uso: poiCategoryReport <poi.db> [poi.db ...]
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Uso: poiCategoryReport <poi.db> [poi.db ...]")
        exitProcess(1)
    }
    val kept = sortedMapOf<PoiCategory, Int>()
    val dropped = mutableMapOf<String, Int>()
    var total = 0
    for (path in args) {
        DriverManager.getConnection("jdbc:sqlite:${File(path).absolutePath}").use { db ->
            db.createStatement().executeQuery(
                "SELECT p.name, c.category, c.osmTag FROM poi p JOIN poi_code c ON c.code = p.code",
            ).use { rows ->
                while (rows.next()) {
                    total++
                    val (name, category, osmTag) = Triple(rows.getString(1), rows.getString(2), rows.getString(3))
                    if (poiPackageOf(name, category, osmTag) == null) {
                        dropped.merge(osmTag, 1, Int::plus)
                    } else {
                        kept.merge(poiCategoryOf(category, osmTag), 1, Int::plus)
                    }
                }
            }
        }
    }
    println("POI letti: $total, ancora pubblicati: ${kept.values.sum()}, tolti: ${dropped.values.sum()}")
    kept.forEach { (category, count) -> println("%8d  %s".format(count, category)) }
    println("Tag tolti (i piu' frequenti):")
    dropped.entries.sortedByDescending { it.value }.take(TOP_DROPPED).forEach { println("%8d  %s".format(it.value, it.key)) }
}

private const val TOP_DROPPED = 80
