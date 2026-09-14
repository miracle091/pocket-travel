package com.pockettravel.pipeline

import com.onthegomap.planetiler.Planetiler
import com.onthegomap.planetiler.config.Arguments
import java.io.File
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 2) { "Uso: generateMapTiles <input.osm.xml> <output.pmtiles>" }
    val inputXml = File(args[0])
    val outputPmtiles = Path.of(args[1])

    val tempPbf = File.createTempFile("pocket-travel-pipeline", ".osm.pbf")
    try {
        writeOsmPbf(parseOsmXml(inputXml), tempPbf)

        Planetiler.create(Arguments.of())
            .setProfile(ShortbreadProfile())
            .addOsmSource("osm", tempPbf.toPath())
            .overwriteOutput(outputPmtiles)
            .run()
    } finally {
        tempPbf.delete()
    }
}
