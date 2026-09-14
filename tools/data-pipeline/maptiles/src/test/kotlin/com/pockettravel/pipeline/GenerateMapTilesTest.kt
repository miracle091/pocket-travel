package com.pockettravel.pipeline

import com.onthegomap.planetiler.Planetiler
import com.onthegomap.planetiler.config.Arguments
import com.onthegomap.planetiler.pmtiles.ReadablePmtiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip reale (non un mock): genera un .osm.pbf da testdata/tiny-region.osm.xml,
 * lo passa a Planetiler con ShortbreadProfile, poi rilegge il .pmtiles risultante e
 * verifica i nomi dei source-layer — la stessa verifica gia' fatta manualmente in precedenza,
 * qui automatizzata invece che manuale.
 */
class GenerateMapTilesTest {

    @Test
    fun `planetiler output declares the three shortbread layers used by OfflineTileSource`() {
        val inputXml = File("testdata/tiny-region.osm.xml")
        val outputPmtiles = File.createTempFile("pocket-travel-test", ".pmtiles")
        outputPmtiles.delete()
        val tempPbf = File.createTempFile("pocket-travel-test", ".osm.pbf")

        try {
            writeOsmPbf(parseOsmXml(inputXml), tempPbf)

            Planetiler.create(Arguments.of())
                .setProfile(ShortbreadProfile())
                .addOsmSource("osm", tempPbf.toPath())
                .overwriteOutput(outputPmtiles.toPath())
                .run()

            ReadablePmtiles.newReadFromFile(outputPmtiles.toPath()).use { archive ->
                val layerNames = archive.metadata().vectorLayers().map { it.id() }.toSet()
                assertEquals(setOf("water", "transportation", "buildings"), layerNames)
            }
        } finally {
            tempPbf.delete()
            outputPmtiles.delete()
        }
    }
}
