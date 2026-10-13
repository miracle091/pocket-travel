package com.pockettravel.app

import com.pockettravel.core.sync.GuidesManifestEntry
import com.pockettravel.core.sync.MapExtractionSource
import com.pockettravel.core.sync.MapPackageEntry
import com.pockettravel.core.sync.PoiPackageEntry
import com.pockettravel.core.sync.RegionManifest
import com.pockettravel.core.sync.RegionManifestEntry
import com.pockettravel.core.sync.RegionManifestFile
import com.pockettravel.core.sync.RegionTransitEntry
import com.pockettravel.core.sync.RoutingPackageEntry
import com.pockettravel.core.sync.TransitFeed
import com.pockettravel.core.sync.TransitManifestEntry

// Pezzi di catalogo per i test dei ViewModel: i valori che non contano per il test hanno un default.

internal fun manifestFile(name: String, sizeBytes: Long = 1_000L) =
    RegionManifestFile(name, "https://github.com/pocket-travel/$name", sizeBytes, "a".repeat(64))

internal fun regionEntry(
    regionId: String = "italia",
    displayName: String = "Italia",
    countryCode: String? = "it",
    mapVersion: String = "m1",
    poiVersion: String = "p1",
    poiBytes: Long = 1_000L,
    routingBytes: Long = 5_000L,
    transit: RegionTransitEntry? = null,
    groupName: String? = null,
    groupLabel: String? = null,
) = RegionManifestEntry(
    regionId = regionId,
    displayName = displayName,
    updatedAt = "2026-10-01T00:00:00Z",
    map = MapPackageEntry(mapVersion, MapExtractionSource("https://build.protomaps.com/x.pmtiles", 6.0, 36.0, 19.0, 47.0, 0, 14)),
    routing = RoutingPackageEntry("r1", listOf(manifestFile("$regionId.rd5", routingBytes))),
    poi = PoiPackageEntry(poiVersion, manifestFile("$regionId-poi.db", poiBytes)),
    transit = transit,
    countryCode = countryCode,
    groupName = groupName,
    groupLabel = groupLabel,
)

internal fun transitFeed(id: String, regions: List<String>, version: String = "v1", sizeBytes: Long = 500L, bbox: List<Double>? = null) =
    TransitFeed(id, "Rete $id", regions, "CC0-1.0", "Rete $id", null, version, manifestFile("transit.db", sizeBytes), bbox = bbox)

internal fun transitManifestEntry(version: String = "t1") = TransitManifestEntry(version, "https://github.com/pocket-travel/transit.json", 100L, "b".repeat(64))

internal fun regionManifest(
    regions: List<RegionManifestEntry> = listOf(regionEntry()),
    guidesVersion: String = "g1",
    guidesBytes: Long = 2_000L,
    transit: TransitManifestEntry? = null,
) = RegionManifest(
    manifestVersion = 1,
    guides = GuidesManifestEntry(guidesVersion, manifestFile("guides.db", guidesBytes)),
    transit = transit,
    regions = regions,
)
