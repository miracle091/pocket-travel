package com.pockettravel.pipeline

import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.archive.TileArchiveMetadata
import com.onthegomap.planetiler.archive.TileCompression
import com.onthegomap.planetiler.archive.TileEncodingResult
import com.onthegomap.planetiler.archive.TileFormat
import com.onthegomap.planetiler.geo.TileCoord
import com.onthegomap.planetiler.pmtiles.WriteablePmtiles
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.OptionalLong
import java.util.zip.GZIPOutputStream

/*
 * Supporto ai test che hanno bisogno di un PMTiles costruito a mano (tile e feature scelte dal test),
 * al posto della fixture reale di San Marino: i filtri di extractAddresses ed extractRoads si provano
 * con feature che la fixture non contiene.
 */

private val testGeometryFactory = GeometryFactory()

/** Una feature da mettere in un layer di una tile di prova: geometria in coordinate di tile 0..256 e tag. */
internal class TestFeature(val geometry: Geometry, val tags: Map<String, Any>)

internal fun tilePoint(x: Double, y: Double) = testGeometryFactory.createPoint(Coordinate(x, y))

internal fun tileLine(vararg points: Pair<Double, Double>) =
    testGeometryFactory.createLineString(points.map { (x, y) -> Coordinate(x, y) }.toTypedArray())

internal fun tileMultiLine(vararg lines: org.locationtech.jts.geom.LineString) = testGeometryFactory.createMultiLineString(lines)

/** Tile vettoriale con i layer indicati (nome del layer -> feature). */
internal fun testTile(layers: Map<String, List<TestFeature>>): VectorTile {
    val tile = VectorTile()
    layers.forEach { (layer, features) ->
        tile.addLayerFeatures(
            layer,
            features.mapIndexed { index, feature -> VectorTile.Feature(layer, index.toLong(), VectorTile.encodeGeometry(feature.geometry), feature.tags) },
        )
    }
    return tile
}

private fun gzipTile(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

/** Scrive [tiles] (compresse con gzip) in un PMTiles locale, nell'ordine di scrittura che l'archivio richiede. */
internal fun writeTestPmtiles(output: File, tiles: Map<TileCoord, VectorTile>) {
    output.delete()
    WriteablePmtiles.newWriteToFile(output.toPath()).use { archive ->
        archive.initialize()
        archive.newTileWriter().use { writer ->
            tiles.entries.sortedBy { archive.tileOrder().encode(it.key) }.forEach { (coord, tile) ->
                writer.write(TileEncodingResult(coord, gzipTile(tile.encode()), OptionalLong.empty()))
            }
        }
        archive.finish(
            TileArchiveMetadata(
                "Pocket Travel test", null, "test", null, "overlay",
                TileFormat.MVT, Envelope(-180.0, 180.0, -90.0, 90.0), null, 14, 15,
                null, emptyMap(), TileCompression.GZIP,
            ),
        )
    }
}
