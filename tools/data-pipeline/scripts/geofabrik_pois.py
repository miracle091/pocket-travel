#!/usr/bin/env python3
"""POI di una regione dagli estratti Geofabrik, nello stesso XML che build-region.sh riceve da Overpass.

Uso: geofabrik_pois.py --bbox=minLon,minLat,maxLon,maxLat --out poi.osm.xml --cache DIR [--user-agent UA]

1. Sceglie dall'indice di Geofabrik (index-v1.json) gli estratti "foglia" il cui poligono tocca il bbox,
   scartando quelli composti (alps, dach, britain-and-ireland...) che ne contengono altri gia' scelti.
2. Scarica ogni estratto e lo riduce con "osmium tags-filter" ai soli oggetti che la query Overpass
   chiederebbe (piu' i nodi delle way e i membri delle relazioni, per la geometria). Il file ridotto
   resta in --cache: le regioni successive dello stesso job non riscaricano lo stesso estratto.
3. "osmium export" calcola le geometrie; di ogni oggetto che passa lo stesso filtro della query
   Overpass (filtro_overpass) e il cui centro cade nel bbox si scrive un <node>, o una <way>/<relation>
   con il <center> del suo rettangolo come "out center" di Overpass.

Esce con codice 1 se qualcosa non va (download, osmium): build-region.sh ripiega allora su Overpass.
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from xml.sax.saxutils import quoteattr

INDEX_URL = "https://download.geofabrik.de/index-v1.json"

# Stessi oggetti della query di fetch_overpass_chunk in build-region.sh, in sintassi di osmium tags-filter
# (qui piu' larghi: il filtro esatto, con name, information e iata, e' filtro_overpass).
TAGS_FILTER = [
    "n/amenity", "n/shop", "n/tourism", "n/leisure", "n/historic",
    "w/amenity=parking,bus_station,hospital,fire_station,place_of_worship,monastery,ferry_terminal",
    "w/tourism=information", "nw/office=diplomatic",
    "wr/leisure=park,nature_reserve,water_park", "wr/tourism=theme_park,zoo",
    "nw/railway=station,halt", "nwr/aeroway=aerodrome",
]
NODE_KEYS = ("amenity", "shop", "tourism", "leisure", "historic")
WAY_AMENITY = {"parking", "bus_station", "hospital", "fire_station", "place_of_worship", "monastery", "ferry_terminal"}


def filtro_overpass(kind, tags):
    """True se la query Overpass di build-region.sh restituirebbe l'oggetto (kind: node, way, relation)."""
    if kind == "node" and any(k in tags for k in NODE_KEYS):
        return True
    if kind == "way" and (tags.get("amenity") in WAY_AMENITY or
                          (tags.get("tourism") == "information" and tags.get("information") in ("office", "visitor_centre"))):
        return True
    if kind in ("node", "way") and (tags.get("office") == "diplomatic" or tags.get("railway") in ("station", "halt")):
        return True
    if kind in ("way", "relation") and (
            (tags.get("leisure") in ("park", "nature_reserve") and "name" in tags) or
            tags.get("leisure") == "water_park" or tags.get("tourism") in ("theme_park", "zoo")):
        return True
    return tags.get("aeroway") == "aerodrome" and "iata" in tags


def anelli(geometry):
    """Anelli esterni di un Polygon/MultiPolygon GeoJSON."""
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"][0]]
    return [poly[0] for poly in geometry["coordinates"]]


def dentro(x, y, ring):
    """Punto nel poligono (ray casting)."""
    inside = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            inside = not inside
    return inside


def segmenti_si_incrociano(a, b, c, d):
    def orient(p, q, r):
        return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
    return (orient(a, b, c) > 0) != (orient(a, b, d) > 0) and (orient(c, d, a) > 0) != (orient(c, d, b) > 0)


def tocca_bbox(rings, bbox):
    minx, miny, maxx, maxy = bbox
    corners = [(minx, miny), (maxx, miny), (maxx, maxy), (minx, maxy)]
    for ring in rings:
        if any(minx <= x <= maxx and miny <= y <= maxy for x, y in ring):
            return True
        if any(dentro(x, y, ring) for x, y in corners):
            return True
        edges = list(zip(corners, corners[1:] + corners[:1]))
        if any(segmenti_si_incrociano(p, q, c, d) for p, q in zip(ring, ring[1:]) for c, d in edges):
            return True
    return False


def contiene(big, small):
    """True se almeno l'80% dei vertici (a campione) di small cade in big: big e' un estratto composto."""
    points = [p for ring in small for p in ring[::max(1, len(ring) // 50)]]
    inside = sum(1 for x, y in points if any(dentro(x, y, ring) for ring in big))
    return inside >= 0.8 * len(points)


def rettangolo(rings):
    xs = [x for ring in rings for x, _ in ring]
    ys = [y for ring in rings for _, y in ring]
    return min(xs), min(ys), max(xs), max(ys)


def area(rect):
    return (rect[2] - rect[0]) * (rect[3] - rect[1])


def rettangolo_dentro(big, small):
    """Il rettangolo small sta (a meno di 0,5 gradi di margine) nel rettangolo big."""
    return (small[0] >= big[0] - 0.5 and small[1] >= big[1] - 0.5 and
            small[2] <= big[2] + 0.5 and small[3] <= big[3] + 0.5)


def scegli_estratti(index, bbox):
    """[(id, url pbf)] degli estratti foglia che toccano il bbox, senza quelli composti."""
    features = [f for f in index["features"] if f.get("geometry") and f["properties"].get("urls", {}).get("pbf")]
    parents = {f["properties"].get("parent") for f in features}
    candidates = [(f["properties"]["id"], f["properties"]["urls"]["pbf"], anelli(f["geometry"]))
                  for f in features if f["properties"]["id"] not in parents]
    candidates = [(*c, rettangolo(c[2])) for c in candidates if tocca_bbox(c[2], bbox)]
    # Dal piu' grande: un estratto che ne contiene un altro gia' candidato (alps con la Svizzera) e' composto.
    candidates.sort(key=lambda c: area(c[3]), reverse=True)
    chosen = [big for i, big in enumerate(candidates)
              if not any(rettangolo_dentro(big[3], small[3]) and contiene(big[2], small[2])
                         for small in candidates[i + 1:])]
    return [(cid, url) for cid, url, _, _ in chosen]


def scarica(url, dest, user_agent, attempts=3):
    """Scarica url in dest, con qualche nuovo tentativo; un file piu' corto del Content-Length e' un errore
    (urllib non lo segnala: un pbf troncato alla fine di un blocco sembrerebbe valido)."""
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": user_agent})
            with urllib.request.urlopen(request, timeout=120) as response, open(dest + ".part", "wb") as out:
                expected = response.headers.get("Content-Length")
                while chunk := response.read(1 << 20):
                    out.write(chunk)
            written = os.path.getsize(dest + ".part")
            if expected is not None and written != int(expected):
                raise OSError(f"{url}: {written} byte invece di {expected}")
            os.replace(dest + ".part", dest)
            return
        except OSError as error:
            if attempt == attempts:
                raise
            print(f"-- geofabrik: {error}, riprovo ({attempt}/{attempts})", flush=True)
            time.sleep(10 * attempt)


def estratto_ridotto(cid, url, cache, user_agent):
    """Percorso dell'estratto ridotto ai soli POI, dalla cache o scaricato e filtrato ora."""
    reduced = os.path.join(cache, f"{cid}-poi.osm.pbf")
    if os.path.exists(reduced):
        print(f"-- geofabrik: {cid} dalla cache", flush=True)
        return reduced
    full = os.path.join(cache, f"{cid}.osm.pbf")
    print(f"-- geofabrik: scarico {url}", flush=True)
    scarica(url, full, user_agent)
    try:
        subprocess.run(["osmium", "tags-filter", "--overwrite", "--no-progress", full, *TAGS_FILTER,
                        "-o", reduced + ".part.osm.pbf"], check=True)
        os.replace(reduced + ".part.osm.pbf", reduced)
    finally:
        os.remove(full)
    return reduced


def centro(geometry):
    if geometry["type"] == "Point":
        return geometry["coordinates"]
    coords = geometry["coordinates"]
    while isinstance(coords[0][0], list):
        coords = [p for part in coords for p in part]
    xs = [x for x, _ in coords]
    ys = [y for _, y in coords]
    return (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2


def scrivi_xml(sources, bbox, out):
    """Scrive gli oggetti di ogni geojsonseq in sources nell'XML di Overpass; ritorna quanti."""
    minx, miny, maxx, maxy = bbox
    seen = set()
    with open(out, "w", encoding="utf-8") as xml:
        xml.write('<?xml version="1.0" encoding="UTF-8"?>\n<osm version="0.6" generator="geofabrik_pois.py">\n')
        for source in sources:
            with open(source, encoding="utf-8") as lines:
                for line in lines:
                    line = line.strip().lstrip("\x1e")
                    if not line:
                        continue
                    feature = json.loads(line)
                    props = feature["properties"]
                    kind, oid = props.pop("@type"), props.pop("@id")
                    # osmium export da' una way chiusa sia come linea sia come area: conta una volta.
                    if (kind, oid) in seen or not filtro_overpass(kind, props):
                        continue
                    lon, lat = centro(feature["geometry"])
                    if not (minx <= lon <= maxx and miny <= lat <= maxy):
                        continue
                    seen.add((kind, oid))
                    tags = "".join(f"<tag k={quoteattr(k)} v={quoteattr(str(v))}/>" for k, v in props.items())
                    if kind == "node":
                        xml.write(f'<node id="{oid}" lat="{lat:.7f}" lon="{lon:.7f}">{tags}</node>\n')
                    else:
                        xml.write(f'<{kind} id="{oid}"><center lat="{lat:.7f}" lon="{lon:.7f}"/>{tags}</{kind}>\n')
        xml.write("</osm>\n")
    return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bbox", required=True, help="minLon,minLat,maxLon,maxLat")
    parser.add_argument("--out", required=True)
    parser.add_argument("--cache", required=True, help="cartella degli estratti ridotti, condivisa dalle regioni del job")
    parser.add_argument("--user-agent", default="PocketTravel-pipeline")
    parser.add_argument("--index", help="index-v1.json locale invece di quello di Geofabrik")
    args = parser.parse_args()
    bbox = tuple(float(v) for v in args.bbox.split(","))
    os.makedirs(args.cache, exist_ok=True)

    index_path = args.index or os.path.join(args.cache, "index-v1.json")
    if not os.path.exists(index_path):
        scarica(INDEX_URL, index_path, args.user_agent)
    with open(index_path, encoding="utf-8") as f:
        extracts = scegli_estratti(json.load(f), bbox)
    if not extracts:
        sys.exit("nessun estratto Geofabrik tocca il bbox")
    print(f"-- geofabrik: {len(extracts)} estratti per il bbox: {' '.join(cid for cid, _ in extracts)}", flush=True)

    with tempfile.TemporaryDirectory() as tmp:
        sources = []
        for cid, url in extracts:
            reduced = estratto_ridotto(cid, url, args.cache, args.user_agent)
            # Prima il ritaglio sul bbox: San Marino esporterebbe altrimenti tutto il nord-est d'Italia.
            clipped = os.path.join(tmp, f"{cid}.osm.pbf")
            subprocess.run(["osmium", "extract", "--overwrite", "--no-progress", "-s", "smart",
                            "-b", ",".join(str(v) for v in bbox), reduced, "-o", clipped], check=True)
            source = os.path.join(tmp, f"{cid}.geojsonseq")
            subprocess.run(["osmium", "export", "--overwrite", "--no-progress", "-f", "geojsonseq",
                            "-x", "print_record_separator=false", "-a", "type,id", clipped, "-o", source], check=True)
            sources.append(source)
        count = scrivi_xml(sources, bbox, args.out)
    # Nessun oggetto e' quasi certamente un errore (formato di osmium cambiato, filtro sbagliato): meglio Overpass.
    if count == 0:
        sys.exit("nessun oggetto POI dagli estratti Geofabrik")
    print(f"-- geofabrik: {count} oggetti POI in {args.out}", flush=True)


if __name__ == "__main__":
    main()
