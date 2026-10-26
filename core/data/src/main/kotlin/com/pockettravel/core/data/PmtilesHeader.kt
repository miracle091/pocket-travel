package com.pockettravel.core.data

import java.io.File

// L'header PMTiles v3 e' fisso a 127 byte; il byte 101 (0-based) e' il max_zoom dell'archivio
// (specs.protomaps.dev/pmtiles/spec-v3). Letto qui invece che tenuto in un campo di `manifest.json`
// perche' la mappa d'insieme puo' essere rigenerata con uno zoom diverso da build-region.sh (tetto di
// peso) e l'header e' l'unica fonte sempre corretta.
private const val PMTILES_HEADER_SIZE = 127

// Per leggere un byte come valore senza segno.
private const val BYTE_MASK = 0xFF
private const val PMTILES_MAX_ZOOM_OFFSET = 101

fun pmtilesHeaderMaxZoom(file: File): Int = pmtilesHeaderMaxZoom(readPmtilesHeader(file))

fun readPmtilesHeader(file: File): ByteArray = file.inputStream().use { stream ->
    // Non InputStream.readNBytes: richiede API 33, minSdk del modulo e' 26.
    val header = ByteArray(PMTILES_HEADER_SIZE)
    var read = 0
    while (read < header.size) {
        val count = stream.read(header, read, header.size - read)
        if (count == -1) break
        read += count
    }
    header
}

fun pmtilesHeaderMaxZoom(header: ByteArray): Int {
    require(header.size >= PMTILES_HEADER_SIZE) { "Header PMTiles troncato: ${header.size} byte" }
    return header[PMTILES_MAX_ZOOM_OFFSET].toInt() and BYTE_MASK
}

/** Rettangolo dei dati della mappa installata, in gradi. */
data class MapBounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    fun contains(latitude: Double, longitude: Double): Boolean =
        longitude in minLon..maxLon && latitude in minLat..maxLat
}

// Byte 102-117 dell'header: min_lon, min_lat, max_lon, max_lat in gradi x 10^7, int32
// little-endian (spec v3; PmtilesWriter li scrive dal rettangolo della regione).
private const val PMTILES_BOUNDS_OFFSET = 102

fun pmtilesHeaderBounds(header: ByteArray): MapBounds {
    require(header.size >= PMTILES_HEADER_SIZE) { "Header PMTiles troncato: ${header.size} byte" }
    val buffer = java.nio.ByteBuffer.wrap(header, PMTILES_BOUNDS_OFFSET, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val e7 = 10_000_000.0
    return MapBounds(buffer.int / e7, buffer.int / e7, buffer.int / e7, buffer.int / e7)
}
