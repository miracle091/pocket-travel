#!/usr/bin/env python3
"""POI di una regione dagli estratti Geofabrik, nello stesso XML che build-region.sh riceve da Overpass.

Uso: geofabrik_pois.py --bbox=minLon,minLat,maxLon,maxLat [--iso=XX] [--border confine.geojson] --out poi.osm.xml --cache DIR
     [--user-agent UA] [--index FILE]

L'indice index-v1.json viene scaricato una volta in --cache e riusato dalle regioni successive; --index (opzione
manuale, build-region.sh non la passa) usa invece un indice locale: utile per provare lo script senza rete
sull'indice o con un indice ritoccato.

1. Sceglie dall'indice di Geofabrik (index-v1.json) gli estratti "foglia" il cui poligono tocca il bbox,
   scartando quelli composti (alps, dach, britain-and-ireland...), coperti per lo piu' da estratti piu' piccoli.
2. Scarica ogni estratto e lo riduce con "osmium tags-filter" ai soli oggetti che la query Overpass
   chiederebbe (piu' i nodi delle way e i membri delle relazioni, per la geometria). Il file ridotto
   resta in --cache: le regioni successive dello stesso job non riscaricano lo stesso estratto.
3. "osmium export" calcola le geometrie; di ogni oggetto che passa lo stesso filtro della query
   Overpass (filtro_overpass) e il cui punto (punto: il nodo, il centro del rettangolo come "out center" di
   Overpass se cade nell'area, altrimenti un punto sull'oggetto) cade nel bbox si scrive un <node>, o una
   <way>/<relation> con quel punto come <center>. Le relazioni che osmium export non assembla (non multipolygon ne'
   boundary) prendono il centro dai loro membri (relazioni_non_aree). Con --border si scrive solo un oggetto con il
   punto dentro il confine (Confine).

Esce con codice 1 se qualcosa non va (download, osmium): build-region.sh ripiega allora su Overpass.
"""

import argparse
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from xml.sax.saxutils import quoteattr

INDEX_URL = "https://download.geofabrik.de/index-v1.json"

# Stessi oggetti della query di fetch_overpass_chunk in build-region.sh, in sintassi di osmium tags-filter
# (qui piu' larghi: il filtro esatto, con name, information e iata, e' filtro_overpass).
TAGS_FILTER = [
    "n/amenity", "n/shop", "n/tourism", "n/leisure", "n/historic",
    "w/amenity=parking,bus_station,hospital,fire_station,monastery,ferry_terminal", "wr/amenity=place_of_worship",
    "w/tourism=information", "nw/office=diplomatic",
    "wr/leisure=park,nature_reserve,water_park,marina,garden", "wr/tourism=theme_park,zoo,museum,gallery,attraction",
    # protect_class=2 e non boundary=protected_area: le aree protette di altre classi (in Italia migliaia di siti
    # Natura 2000) porterebbero nell'estratto ridotto tutti i nodi dei loro confini.
    "wr/historic", "wr/man_made=bridge", "wr/landuse=religious", "wr/boundary=national_park", "wr/protect_class=2",
    "nwr/place=square", "nw/railway=station,halt", "nwr/aeroway=aerodrome",
]
# Paesi che un estratto contiene ma l'indice non elenca: gcc-states ha anche l'Arabia Saudita, quello
# dell'Irlanda l'Irlanda del Nord, quello della Malesia Singapore e Brunei.
PAESI_MANCANTI = {"gcc-states": {"SA"}, "ireland-and-northern-ireland": {"GB"},
                  "malaysia-singapore-brunei": {"SG", "BN"}}
NODE_KEYS = ("amenity", "shop", "tourism", "leisure", "historic")
# Musei e monumenti come aree o relazioni (Tour Eiffel, Louvre), solo con un nome: query di build-region.sh.
WR_TOURISM_NAMED = {"museum", "gallery", "attraction"}
WR_HISTORIC_NAMED = {"castle", "monument", "ruins", "archaeological_site", "fort", "city_gate"}
WAY_AMENITY = {"parking", "bus_station", "hospital", "fire_station", "monastery", "ferry_terminal"}


def filtro_overpass(kind, tags):
    """True se la query Overpass di build-region.sh restituirebbe l'oggetto (kind: node, way, relation). Ponti,
    giardini, piazze, complessi religiosi e gli altri valori di historic solo con wikidata: famosi, non le migliaia
    di ponticelli e giardinetti."""
    if kind == "node" and any(k in tags for k in NODE_KEYS):
        return True
    if kind == "way" and (tags.get("amenity") in WAY_AMENITY or
                          (tags.get("tourism") == "information" and tags.get("information") in ("office", "visitor_centre"))):
        return True
    if kind in ("node", "way") and (tags.get("office") == "diplomatic" or tags.get("railway") in ("station", "halt")):
        return True
    if kind in ("way", "relation") and (
            (tags.get("leisure") in ("park", "nature_reserve") and "name" in tags) or
            tags.get("leisure") in ("water_park", "marina") or tags.get("tourism") in ("theme_park", "zoo") or
            tags.get("amenity") == "place_of_worship" or
            ("name" in tags and (tags.get("tourism") in WR_TOURISM_NAMED or tags.get("historic") in WR_HISTORIC_NAMED or
                                 tags.get("boundary") == "national_park" or
                                 (tags.get("boundary") == "protected_area" and tags.get("protect_class") == "2"))) or
            ("name" in tags and "wikidata" in tags and (
                "historic" in tags or tags.get("man_made") == "bridge" or tags.get("landuse") == "religious" or
                tags.get("leisure") == "garden"))):
        return True
    if tags.get("place") == "square" and "name" in tags and "wikidata" in tags:
        return True
    return tags.get("aeroway") == "aerodrome" and "iata" in tags


def anelli(geometry):
    """Anelli esterni non vuoti di un Polygon/MultiPolygon GeoJSON."""
    if geometry["type"] == "Polygon":
        return [ring for ring in geometry["coordinates"][:1] if ring]
    return [poly[0] for poly in geometry["coordinates"] if poly and poly[0]]


def anelli_poly(text):
    """Anelli esterni di un file .poly di Osmosis (nome, poi sezioni di righe "lon lat" chiuse da END; quelle che
    cominciano con "!" sono buchi), come coordinate di un MultiPolygon GeoJSON."""
    lines = [line.strip() for line in text.splitlines()][1:]
    polygons, ring, hole = [], None, False
    for line in lines:
        if not line:
            continue
        if ring is None:
            if line == "END":
                break
            ring, hole = [], line.startswith("!")
        elif line == "END":
            if not hole and ring:
                polygons.append([ring])
            ring = None
        else:
            x, y = line.split()[:2]
            ring.append([float(x), float(y)])
    return polygons


def anelli_geojson(text):
    """Anelli esterni del file .geojson di un estratto (la sola geometria); nessuno se non e' JSON valido (una pagina
    d'errore al posto del file)."""
    try:
        return anelli(json.loads(text))
    except (ValueError, KeyError, TypeError):
        return []


def poligono_scaricato(base_url, cache, name, user_agent):
    """Anelli dell'estratto dai suoi file accanto al pbf: prima <nome>.geojson (la sola geometria, piena anche dove il
    .poly e' vuoto, come Kyushu), poi <nome>.poly. Un file vuoto (Geofabrik risponde 200 senza contenuto mentre
    rigenera i poligoni) non resta in cache: la regione dopo, nello stesso job, ci riprova."""
    for ext, parse in ((".geojson", anelli_geojson), (".poly", anelli_poly)):
        path = os.path.join(cache, f"{name}{ext}")
        if not os.path.exists(path):
            scarica(base_url + ext, path, user_agent)
        with open(path, encoding="utf-8") as f:
            text = f.read()
        if not text.strip():
            os.remove(path)
            continue
        polygons = [[ring] for ring in parse(text)] if ext == ".geojson" else parse(text)
        if polygons:
            return polygons
    return []


def completa_geometrie(index, cache, user_agent):
    """Agli estratti che nell'indice hanno il poligono vuoto (dal 2026-10 Giappone, Thailandia, sei stati USA...)
    da' quello dei loro file .geojson o .poly, accanto al pbf (poligono_scaricato): senza, la Georgia USA userebbe solo
    gli stati vicini. Se anche quelli sono vuoti prende il poligono dell'antenato piu' vicino che ne ha uno: piu' grande
    del vero, al massimo fa scaricare un estratto in piu', mai mancare POI. Senza nessun poligono, o se i file non si
    scaricano, lo script esce e build-region.sh ripiega su Overpass."""
    by_id = {f["properties"]["id"]: f for f in index["features"]}
    missing = []
    for feature in index["features"]:
        props, geometry = feature["properties"], feature.get("geometry")
        pbf = props.get("urls", {}).get("pbf", "")
        if not geometry or not pbf.endswith("-latest.osm.pbf") or anelli(geometry):
            continue
        polygons = poligono_scaricato(pbf.removesuffix("-latest.osm.pbf"), cache, nome_file(props["id"]), user_agent)
        feature["geometry"] = {"type": "MultiPolygon", "coordinates": polygons}
        if not polygons:
            missing.append(feature)
    for feature in missing:
        ancestor = by_id.get(feature["properties"].get("parent"))
        while ancestor and not (ancestor.get("geometry") and anelli(ancestor["geometry"])):
            ancestor = by_id.get(ancestor["properties"].get("parent"))
        if not ancestor:
            sys.exit(f"estratto Geofabrik {feature['properties']['id']} senza poligono, neanche nei file .geojson e .poly o in un genitore")
        print(f"-- geofabrik: {feature['properties']['id']} senza poligono, uso quello di {ancestor['properties']['id']}", flush=True)
        feature["geometry"] = ancestor["geometry"]
        # Il poligono prestato copre i fratelli: per scegli_estratti l'estratto non e' composto.
        feature["properties"]["poligono_del_genitore"] = True


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
    edges = list(zip(corners, corners[1:] + corners[:1]))
    for ring in rings:
        # Anello con il rettangolo tutto fuori da bbox: nessun punto in comune (vale per quasi tutti gli estratti dell'indice).
        rminx, rminy, rmaxx, rmaxy = rettangolo([ring])
        if rminx > maxx or rmaxx < minx or rminy > maxy or rmaxy < miny:
            continue
        if any(minx <= x <= maxx and miny <= y <= maxy for x, y in ring):
            return True
        if any(dentro(x, y, ring) for x, y in corners):
            return True
        if any(segmenti_si_incrociano(p, q, c, d) for p, q in zip(ring, ring[1:]) for c, d in edges):
            return True
    return False


def rettangolo(rings):
    xs = [x for ring in rings for x, _ in ring]
    ys = [y for ring in rings for _, y in ring]
    return min(xs), min(ys), max(xs), max(ys)


def area(rect):
    return (rect[2] - rect[0]) * (rect[3] - rect[1])


def copertura(big, leaves, n=20):
    """Quota dei punti di una griglia n x n interni a big che cadono in un estratto piu' piccolo: vicina a 1
    per un estratto composto (dach, alps, britain-and-ireland, us), sotto 0,15 per gli altri. Solo punti
    interni: i vertici stanno nel margine di mare, che le foglie coprono solo in parte."""
    bid, _, brings, brect = big
    # A parita' di rettangolo conta l'id: di south-africa e south-africa-and-lesotho (stesso rettangolo)
    # ne resta uno solo.
    smaller = [leaf for leaf in leaves if (area(leaf[3]), leaf[0]) < (area(brect), bid)]
    minx, miny, maxx, maxy = brect
    points = [(minx + (i + 0.5) * (maxx - minx) / n, miny + (j + 0.5) * (maxy - miny) / n)
              for i in range(n) for j in range(n)]
    points = [(x, y) for x, y in points if any(dentro(x, y, ring) for ring in brings)]
    covered = sum(1 for x, y in points
                  if any(r[0] <= x <= r[2] and r[1] <= y <= r[3] and any(dentro(x, y, ring) for ring in rings)
                         for _, _, rings, r in smaller))
    return covered / len(points) if points else 0.0


def paesi_estratto(props_by_id, cid):
    """Codici ISO 3166-1 alpha-2 (maiuscoli) dell'estratto cid: i suoi, quelli del prefisso dei suoi codici
    ISO 3166-2 (us/alaska ha solo US-AK e come genitore north-america) o quelli del genitore piu' vicino che li
    ha (nord-norge sta sotto norway)."""
    while cid in props_by_id:
        props = props_by_id[cid]
        codes = set(props.get("iso3166-1:alpha2", [])) | {c.split("-")[0] for c in props.get("iso3166-2", [])}
        codes |= PAESI_MANCANTI.get(cid, set())
        if codes:
            return codes
        cid = props.get("parent")
    return set()


def scegli_estratti(index, bbox, iso=None):
    """[(id, url pbf)] degli estratti foglia che toccano il bbox, senza quelli composti. Con iso (codice alpha-2
    della nazione della regione) e almeno un estratto di quella nazione tra i candidati, solo quelli: il bbox
    di Norvegia o Arabia Saudita contiene paesi interi (Svezia, Finlandia, Egitto, Iran...) che sarebbero
    scaricati per un pugno di POI al confine."""
    features = [f for f in index["features"] if f.get("geometry") and f["properties"].get("urls", {}).get("pbf")]
    parents = {f["properties"].get("parent") for f in features}
    leaves = [(f["properties"]["id"], f["properties"]["urls"]["pbf"], anelli(f["geometry"]))
              for f in features if f["properties"]["id"] not in parents]
    leaves = [(*c, rettangolo(c[2])) for c in leaves]
    # Composto = coperto per meta' o piu' da estratti piu' piccoli di tutto l'indice, non solo da quelli
    # che toccano il bbox: il bbox del Belgio tocca il mare di britain-and-ireland ma nessuna contea
    # inglese (si scaricherebbe tutta la Gran Bretagna; us per le Bahamas). Un'enclave non basta: il Marocco
    # contiene Ceuta e Melilla, il Sudafrica il Lesotho, e non per questo vanno scartati.
    borrowed = {f["properties"]["id"] for f in features if f["properties"].get("poligono_del_genitore")}
    chosen = [c for c in leaves if tocca_bbox(c[2], bbox) and (c[0] in borrowed or copertura(c, leaves) < 0.5)]
    if iso:
        props_by_id = {f["properties"]["id"]: f["properties"] for f in index["features"]}
        propri = [c for c in chosen if iso.upper() in paesi_estratto(props_by_id, c[0])]
        chosen = propri or chosen
    if not chosen:
        # Nessuna foglia tocca il rettangolo (Sint Maarten sta solo in central-america e north-america):
        # il piu' piccolo degli estratti che lo toccano, invece di ripiegare su Overpass.
        touching = [(f["properties"]["id"], f["properties"]["urls"]["pbf"], anelli(f["geometry"])) for f in features]
        touching = [(*c, rettangolo(c[2])) for c in touching if tocca_bbox(c[2], bbox)]
        chosen = sorted(touching, key=lambda c: area(c[3]))[:1]
    return [(cid, url) for cid, url, _, _ in chosen]


def scarica(url, dest, user_agent, attempts=3):
    """Scarica url in dest, con qualche nuovo tentativo; un file piu' corto del Content-Length e' un errore
    (urllib non lo segnala: un pbf troncato alla fine di un blocco sembrerebbe valido)."""
    for attempt in range(1, attempts + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": user_agent})
            with urllib.request.urlopen(request, timeout=120) as response, open(dest + ".part", "wb") as out:
                expected = response.headers.get("Content-Length")
                # Una riga al minuto: i file grandi (Canada, Stati Uniti) resterebbero fino a 20 minuti senza log.
                start = last = time.monotonic()
                done = 0
                while chunk := response.read(1 << 20):
                    out.write(chunk)
                    done += len(chunk)
                    if time.monotonic() - last >= 60:
                        last = time.monotonic()
                        total = f" di {int(expected) / 2**20:.0f}" if expected else ""
                        rate = done / 2**20 / max(last - start, 1)
                        print(f"-- geofabrik: {done / 2**20:.0f}{total} MB ({rate:.1f} MB/s)", flush=True)
            written = os.path.getsize(dest + ".part")
            if expected is not None and written != int(expected):
                raise OSError(f"{url}: {written} byte invece di {expected}")
            os.replace(dest + ".part", dest)
            print(f"-- geofabrik: {written / 2**20:.0f} MB in {time.monotonic() - start:.0f}s", flush=True)
            return
        except OSError as error:
            # Un 4xx non passa riprovando subito (lo ripara il file datato, vedi estratto_ridotto).
            if attempt == attempts or (isinstance(error, urllib.error.HTTPError) and error.code < 500):
                raise
            print(f"-- geofabrik: {error}, riprovo ({attempt}/{attempts})", flush=True)
            time.sleep(10 * attempt)


def url_datato(url, user_agent):
    """URL del file datato piu' recente (nome-AAMMGG.osm.pbf) accanto a nome-latest.osm.pbf, dall'elenco della
    cartella. Il proxy di Geofabrik a volte tiene in cache per il -latest un 301 verso se stesso con la barra
    finale (urllib: "infinite loop") o un 404, mentre il file datato si scarica."""
    folder, name = url.rsplit("/", 1)
    name = name.removesuffix("-latest.osm.pbf")
    # L'elenco della cartella c'e' solo per le sottocartelle (europe/norway/): per quelle di primo livello
    # (asia/, la radice) la cartella rimanda ad asia.html, e i file datati li elenca la pagina dell'estratto
    # (asia/gcc-states.html, central-america.html).
    dates = []
    for page in (folder + "/", f"{folder}/{name}.html"):
        request = urllib.request.Request(page, headers={"User-Agent": user_agent})
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                listing = response.read().decode("utf-8", "replace")
        except OSError:
            continue
        dates = re.findall(rf'href="{re.escape(name)}-(\d{{6}})\.osm\.pbf"', listing)
        if dates:
            break
    if not dates:
        raise OSError(f"nessun file datato per {name} in {folder}/")
    return f"{folder}/{name}-{max(dates)}.osm.pbf"


def estratto_ridotto(cid, url, cache, user_agent):
    """Percorso dell'estratto ridotto ai soli POI, dalla cache o scaricato e filtrato ora."""
    reduced = os.path.join(cache, f"{nome_file(cid)}-poi.osm.pbf")
    if os.path.exists(reduced):
        print(f"-- geofabrik: {cid} dalla cache", flush=True)
        return reduced
    full = os.path.join(cache, f"{nome_file(cid)}.osm.pbf")
    print(f"-- geofabrik: scarico {url}", flush=True)
    try:
        scarica(url, full, user_agent)
    except urllib.error.HTTPError as error:
        if error.code >= 500:
            raise
        url = url_datato(url, user_agent)
        print(f"-- geofabrik: {error}, provo il file datato {url}", flush=True)
        scarica(url, full, user_agent)
    try:
        subprocess.run(["osmium", "tags-filter", "--overwrite", "--no-progress", full, *TAGS_FILTER,
                        "-o", reduced + ".part.osm.pbf"], check=True)
        os.replace(reduced + ".part.osm.pbf", reduced)
    finally:
        os.remove(full)
    return reduced


def nome_file(cid):
    """Nome di file per l'id di un estratto: quelli con la barra (us/alaska) chiederebbero una sottocartella."""
    return cid.replace("/", "-")


def centro(geometry):
    if geometry["type"] == "Point":
        return geometry["coordinates"]
    coords = geometry["coordinates"]
    while isinstance(coords[0][0], list):
        coords = [p for part in coords for p in part]
    xs = [x for x, _ in coords]
    ys = [y for _, y in coords]
    return (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2


def punto(geometry):
    """Punto che rappresenta l'oggetto, sempre sull'oggetto: il nodo; per una via aperta il punto a meta' della
    lunghezza; per un'area il centro del rettangolo (il <center> di Overpass) se cade dentro, altrimenti il centro del
    tratto interno piu' largo sull'orizzontale a meta' altezza della parte piu' grande (aree a C o a L). Ogni oggetto
    ha un solo punto, dentro una sola regione: due regioni vicine non ripetono le vie e le aree a cavallo."""
    kind, coords = geometry["type"], geometry["coordinates"]
    if kind == "Point":
        return tuple(coords)
    if kind == "LineString" and len(coords) > 3 and coords[0] == coords[-1]:
        # Way chiusa: osmium la da' anche come area, e deve avere lo stesso punto.
        kind, coords = "Polygon", [coords]
    if kind in ("LineString", "MultiLineString"):
        segs = [(p, q) for line in ([coords] if kind == "LineString" else coords) for p, q in zip(line, line[1:])]
        k = math.cos(math.radians(centro(geometry)[1]))
        lens = [math.hypot((q[0] - p[0]) * k, q[1] - p[1]) for p, q in segs]
        rest = sum(lens) / 2
        for (p, q), d in zip(segs, lens):
            if rest <= d and d:
                t = rest / d
                return p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])
            rest -= d
        return centro(geometry)
    polys = [coords] if kind == "Polygon" else coords
    x, y = centro(geometry)
    if sum(dentro(x, y, ring) for poly in polys for ring in poly) % 2:
        return x, y

    def area_anello(ring):
        return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]))) / 2
    poly = max(polys, key=lambda p: area_anello(p[0]))
    _, miny, _, maxy = rettangolo(poly[:1])
    ym = (miny + maxy) / 2
    xs = sorted(x1 + (ym - y1) * (x2 - x1) / (y2 - y1) for ring in poly
                for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]) if (y1 > ym) != (y2 > ym))
    tratti = list(zip(xs[::2], xs[1::2]))
    if not tratti:
        return x, y
    a, b = max(tratti, key=lambda t: t[1] - t[0])
    return (a + b) / 2, ym


class Confine:
    """Confine di --border (Feature GeoJSON Polygon/MultiPolygon, buchi compresi: Pechino e Tianjin sono buchi
    dell'Hebei) per scrivi_xml: un oggetto resta se il suo punto (punto) e' dentro. I membri lontani delle relazioni
    lunghe, che `osmium extract -S types=any` completa per intero (i tratti della Grande Muraglia per la Mongolia
    Interna), restano fuori; le vie e le aree a cavallo restano in una sola delle regioni vicine. I lati stanno in fasce
    orizzontali: ogni punto guarda solo quelli della sua fascia, non le decine di migliaia del confine."""

    def __init__(self, geometry, fasce=None):
        geometry = geometry.get("geometry", geometry)
        polys = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
        # Ogni lato dal basso in alto: il lato in comune con la regione vicina, percorso al contrario, da' lo stesso
        # incrocio fino all'ultima cifra, e un punto su un vertice del confine sta in una regione sola.
        lati = [(x1, y1, x2, y2) if y1 < y2 else (x2, y2, x1, y1) for poly in polys for ring in poly
                for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]) if y1 != y2]
        if not lati:
            raise ValueError("confine senza lati")
        self.miny = min(min(y1, y2) for _, y1, _, y2 in lati)
        maxy = max(max(y1, y2) for _, y1, _, y2 in lati)
        self.n = fasce or max(1, min(len(lati), 4096))
        self.h = (maxy - self.miny) / self.n
        self.fasce = [[] for _ in range(self.n)]
        for lato in lati:
            for i in range(self.fascia(min(lato[1], lato[3])), self.fascia(max(lato[1], lato[3])) + 1):
                self.fasce[i].append(lato)

    def fascia(self, y):
        return min(self.n - 1, max(0, int((y - self.miny) / self.h)))

    def dentro(self, x, y):
        """Punto nel poligono (ray casting, pari-dispari su tutti gli anelli: un buco toglie)."""
        if not self.miny <= y <= self.miny + self.n * self.h:
            return False
        inside = False
        for x1, y1, x2, y2 in self.fasce[self.fascia(y)]:
            if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
                inside = not inside
        return inside



def leggi_opl(path):
    """Nodi {id: (lon, lat)}, way {id: [id dei nodi]} e relazioni {id: (tag, [(n|w|r, id)])} di un file OPL di osmium
    (formato "opl,add_metadata=false": un oggetto per riga, campi separati da spazi, caratteri speciali come %hex%)."""
    def unescape(text):
        return re.sub(r"%([0-9a-fA-F]+)%", lambda m: chr(int(m.group(1), 16)), text)

    nodes, ways, relations = {}, {}, {}
    with open(path, encoding="utf-8") as lines:
        for line in lines:
            fields = line.split()
            if not fields:
                continue
            kind, oid = fields[0][0], int(fields[0][1:])
            values = {f[0]: f[1:] for f in fields[1:]}
            if kind == "n" and values.get("x") and values.get("y"):
                nodes[oid] = (float(values["x"]), float(values["y"]))
            elif kind == "w":
                ways[oid] = [int(n[1:]) for n in values.get("N", "").split(",") if n]
            elif kind == "r":
                tags = dict(unescape(t).split("=", 1) for t in values.get("T", "").split(",") if t)
                members = [(m[0], int(m[1:].split("@", 1)[0])) for m in values.get("M", "").split(",") if m]
                relations[oid] = (tags, members)
    return nodes, ways, relations


def punto_relazione(rid, nodes, ways, relations, seen=None):
    """Punto di una relazione: quello (punto) della sua way membro con l'id piu' basso, o del nodo membro, o della
    relazione figlia. Sempre lo stesso membro, qualunque parte della relazione ci sia nel ritaglio: due regioni vicine
    danno alla relazione lo stesso punto, e la tiene solo quella che lo contiene. None se quel membro non c'e' (e' fuori
    dal ritaglio, quindi lo e' anche il punto)."""
    seen = seen if seen is not None else set()
    seen.add(rid)
    members = relations.get(rid, ({}, []))[1]
    for kind in ("w", "n", "r"):
        # Le relazioni figlie fuori dal filtro di tags-filter mancano dappertutto: vale la prima che c'e'.
        ids = sorted(mid for k, mid in members if k == kind and (kind != "r" or (mid in relations and mid not in seen)))
        if not ids:
            continue
        if kind == "n":
            return nodes.get(ids[0])
        if kind == "r":
            return punto_relazione(ids[0], nodes, ways, relations, seen)
        refs = ways.get(ids[0], [])
        if not refs or any(n not in nodes for n in refs):
            return None
        return punto({"type": "LineString", "coordinates": [list(nodes[n]) for n in refs]})
    return None


def relazioni_non_aree(clipped, tmp, nome):
    """[(id, tag, lon, lat)] delle relazioni del filtro che osmium export non assembla come aree, perche' non sono
    multipolygon o boundary (Ponte di Rialto type=building, Palmeral di Elche type=collection, Museo di storia
    naturale di Parigi type=site): Overpass le restituisce con "out center", qui con il punto di un loro membro
    (punto_relazione)."""
    rels = os.path.join(tmp, f"{nome}-relazioni.opl")
    subprocess.run(["osmium", "cat", "--overwrite", "--no-progress", "-t", "relation", clipped,
                    "-f", "opl,add_metadata=false", "-o", rels], check=True)
    candidates = [rid for rid, (tags, _) in leggi_opl(rels)[2].items()
                  if tags.get("type") not in ("multipolygon", "boundary") and filtro_overpass("relation", tags)]
    if not candidates:
        return []
    ids = os.path.join(tmp, f"{nome}-relazioni.txt")
    with open(ids, "w", encoding="utf-8") as f:
        f.writelines(f"r{rid}\n" for rid in candidates)
    members = os.path.join(tmp, f"{nome}-membri.opl")
    # Codice 1: alcuni membri non ci sono (relazioni figlie fuori dal filtro, membri fuori dal ritaglio). Senza
    # il file scritto e' invece un errore di osmium, che altrimenti perderebbe in silenzio tutte queste relazioni.
    result = subprocess.run(["osmium", "getid", "--overwrite", "--no-progress", "-r", "-i", ids, clipped,
                             "-f", "opl,add_metadata=false", "-o", members])
    if result.returncode not in (0, 1) or not os.path.exists(members):
        raise subprocess.CalledProcessError(result.returncode, result.args)
    nodes, ways, relations = leggi_opl(members)
    out = []
    for rid in candidates:
        point = punto_relazione(rid, nodes, ways, relations)
        if point:
            out.append((rid, relations[rid][0], *point))
    return out


def scrivi_xml(sources, bbox, out, relazioni=(), confine=None):
    """Scrive gli oggetti di ogni geojsonseq in sources, piu' le relazioni [(id, tag, lon, lat)] di relazioni_non_aree,
    nell'XML di Overpass; ritorna quanti. Con confine (Confine) solo quelli con il punto dentro."""
    minx, miny, maxx, maxy = bbox
    seen, fuori = set(), set()

    def oggetti():
        for source in sources:
            with open(source, encoding="utf-8") as lines:
                for line in lines:
                    line = line.strip().lstrip("\x1e")
                    if not line:
                        continue
                    feature = json.loads(line)
                    props = feature["properties"]
                    yield props.pop("@type"), props.pop("@id"), props, feature["geometry"]
        for rid, tags, lon, lat in relazioni:
            yield "relation", rid, tags, {"type": "Point", "coordinates": [lon, lat]}

    with open(out, "w", encoding="utf-8") as xml:
        xml.write('<?xml version="1.0" encoding="UTF-8"?>\n<osm version="0.6" generator="geofabrik_pois.py">\n')
        for kind, oid, props, geometry in oggetti():
            # osmium export da' una way chiusa sia come linea sia come area: conta una volta.
            if (kind, oid) in seen or not filtro_overpass(kind, props):
                continue
            lon, lat = punto(geometry)
            if not (minx <= lon <= maxx and miny <= lat <= maxy):
                continue
            if confine and not confine.dentro(lon, lat):
                fuori.add((kind, oid))
                continue
            seen.add((kind, oid))
            tags = "".join(f"<tag k={quoteattr(k)} v={quoteattr(str(v))}/>" for k, v in props.items())
            if kind == "node":
                xml.write(f'<node id="{oid}" lat="{lat:.7f}" lon="{lon:.7f}">{tags}</node>\n')
            else:
                xml.write(f'<{kind} id="{oid}"><center lat="{lat:.7f}" lon="{lon:.7f}"/>{tags}</{kind}>\n')
        xml.write("</osm>\n")
    if confine:
        # Una relazione divisa tra due estratti ha un centro per ciascuno: resta se uno dei due e' dentro.
        print(f"-- geofabrik: {len(fuori - seen)} oggetti fuori dal confine scartati", flush=True)
    return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bbox", required=True, help="minLon,minLat,maxLon,maxLat")
    parser.add_argument("--iso", help="codice ISO 3166-1 alpha-2 della nazione della regione: se qualche estratto candidato e' suo, si scartano quelli di altri paesi")
    parser.add_argument("--out", required=True)
    parser.add_argument("--cache", required=True, help="cartella degli estratti ridotti, condivisa dalle regioni del job")
    parser.add_argument("--user-agent", default="PocketTravel-pipeline")
    parser.add_argument("--border", help="confine della nazione (Feature GeoJSON di country_border.py): i POI fuori si scartano")
    parser.add_argument("--index", help="index-v1.json locale, usato al posto di quello scaricato in --cache (opzione manuale, non usata da build-region.sh)")
    args = parser.parse_args()
    bbox = tuple(float(v) for v in args.bbox.split(","))
    os.makedirs(args.cache, exist_ok=True)

    index_path = args.index or os.path.join(args.cache, "index-v1.json")
    if not os.path.exists(index_path):
        scarica(INDEX_URL, index_path, args.user_agent)
    with open(index_path, encoding="utf-8") as f:
        index = json.load(f)
    completa_geometrie(index, args.cache, args.user_agent)
    extracts = scegli_estratti(index, bbox, args.iso)
    if not extracts:
        sys.exit("nessun estratto Geofabrik tocca il bbox")
    print(f"-- geofabrik: {len(extracts)} estratti per il bbox: {' '.join(cid for cid, _ in extracts)}", flush=True)

    with tempfile.TemporaryDirectory() as tmp:
        sources, relazioni = [], []
        for cid, url in extracts:
            reduced = estratto_ridotto(cid, url, args.cache, args.user_agent)
            # Prima il ritaglio sul bbox: San Marino esporterebbe altrimenti tutto il nord-est d'Italia. "types=any":
            # complete anche le relazioni non multipolygon che escono dal bbox (parchi nazionali type=boundary, siti
            # type=site), come le vede Overpass; l'estratto ridotto ha solo le relazioni dei POI, niente confini.
            clipped = os.path.join(tmp, f"{nome_file(cid)}.osm.pbf")
            subprocess.run(["osmium", "extract", "--overwrite", "--no-progress", "-s", "smart", "-S", "types=any",
                            "-b", ",".join(str(v) for v in bbox), reduced, "-o", clipped], check=True)
            if args.border:
                # Poi sul confine della nazione: il rettangolo di San Marino o dell'Ungheria comprende anche l'Italia o
                # Vienna. Le vie e le relazioni a cavallo restano intere (strategia smart, come sopra), con i membri
                # lontani delle relazioni lunghe: scrivi_xml tiene solo gli oggetti con il punto dentro (Confine).
                inside = os.path.join(tmp, f"{nome_file(cid)}-confine.osm.pbf")
                subprocess.run(["osmium", "extract", "--overwrite", "--no-progress", "-s", "smart", "-S", "types=any",
                                "-p", args.border, clipped, "-o", inside], check=True)
                clipped = inside
            source = os.path.join(tmp, f"{nome_file(cid)}.geojsonseq")
            subprocess.run(["osmium", "export", "--overwrite", "--no-progress", "-f", "geojsonseq",
                            "-x", "print_record_separator=false", "-a", "type,id", clipped, "-o", source], check=True)
            sources.append(source)
            relazioni += relazioni_non_aree(clipped, tmp, nome_file(cid))
        confine = None
        if args.border:
            with open(args.border, encoding="utf-8") as f:
                confine = Confine(json.load(f))
        count = scrivi_xml(sources, bbox, args.out, relazioni, confine)
    # Nessun oggetto e' quasi certamente un errore (formato di osmium cambiato, filtro sbagliato): meglio Overpass.
    if count == 0:
        sys.exit("nessun oggetto POI dagli estratti Geofabrik")
    print(f"-- geofabrik: {count} oggetti POI in {args.out}", flush=True)


if __name__ == "__main__":
    main()
