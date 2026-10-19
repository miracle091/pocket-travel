#!/usr/bin/env python3
"""Parti del confine di ogni regione fuori dal suo rettangolo: POI che non entrano in nessun pacchetto.

Uso: bbox_audit.py --manifest manifest.json --cache DIR [--user-agent UA] [--min-km 30] [--places] [--out audit.tsv]

geofabrik_pois.py taglia prima sul rettangolo della regione (`map.source` in manifest.json, da regions.sh) e poi sul
confine (poi.borderRelation), quindi un POI dentro il confine ma fuori dal rettangolo non sta in nessun pacchetto
(Chatham per la Nuova Zelanda, Aleutine occidentali per l'Alaska). Per ogni relazione, il poligono completo di
Nominatim (country_border.poligono_completo, in --cache come confine-r<id>.json) perde le parti dentro i rettangoli
delle regioni tagliate su quella relazione (le dieci regioni russe sul confine della Russia) e quelle coperte da
un'altra regione (dentro il suo rettangolo e il suo confine: Riunione per la Francia). Il resto va al rettangolo piu'
vicino, diviso in zone (ovest, est, e sud e nord tra i due lati); di ogni zona si scrivono l'area, la distanza
massima dal rettangolo della regione, il rettangolo della parte e se la parte e' oltre l'antimeridiano (raggiungibile solo con un rettangolo che
attraversa +-180, che la pipeline non gestisce). Le zone che escono meno di --min-km dal rettangolo si ignorano: sono
le acque territoriali (12 miglia, 22 km) lungo le coste; ne restano anche di piu' larghe, quindi l'area da sola non
dice se ci vive qualcuno. Con --places conta in Overpass i luoghi abitati (place=city/town/village/hamlet) di ogni
zona.

Serve a scegliere a mano quali rettangoli di regions.sh allargare; non e' usato dalla pipeline.
"""

import argparse
import json
import math
import sys
import time
import urllib.parse
import urllib.request

import country_border
from geofabrik_pois import Confine

KM_PER_GRADO = 111.32
OVERPASS = "https://overpass-api.de/api/interpreter"


def ritaglia(ring, dentro, incrocio):
    """Sutherland-Hodgman su un semipiano: i punti di [ring] con dentro(p), piu' gli incroci dei lati con il bordo."""
    out = []
    for i, p in enumerate(ring):
        q = ring[i - 1]
        if dentro(p):
            if not dentro(q):
                out.append(incrocio(q, p))
            out.append(p)
        elif dentro(q):
            out.append(incrocio(q, p))
    return out


def su_x(x0):
    return lambda a, b: (x0, a[1] + (b[1] - a[1]) * (x0 - a[0]) / (b[0] - a[0]))


def su_y(y0):
    return lambda a, b: (a[0] + (b[0] - a[0]) * (y0 - a[1]) / (b[1] - a[1]), y0)


def zone(bbox):
    """Le quattro zone fuori dal rettangolo, come elenchi di semipiani (dentro, incrocio)."""
    minx, miny, maxx, maxy = bbox
    tra = [(lambda p: p[0] >= minx, su_x(minx)), (lambda p: p[0] <= maxx, su_x(maxx))]
    return {
        "ovest": [(lambda p: p[0] < minx, su_x(minx))],
        "est": [(lambda p: p[0] > maxx, su_x(maxx))],
        "sud": tra + [(lambda p: p[1] < miny, su_y(miny))],
        "nord": tra + [(lambda p: p[1] > maxy, su_y(maxy))],
    }


def area_km2(ring):
    """Area dell'anello in km2, nella proiezione sinusoidale (equivalente: x = lon * cos(lat))."""
    pts = [(x * math.cos(math.radians(y)), y) for x, y in ring]
    return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1]))) / 2 * KM_PER_GRADO ** 2


def distanze_x(p, bbox):
    """Distanze in longitudine del punto dal rettangolo: senza giro, e facendo il giro a +-180 (il minimo dei due versi)."""
    minx, _, maxx, _ = bbox
    dx = [abs(p[0] + k - min(max(p[0] + k, minx), maxx)) for k in (-360, 0, 360)]
    return dx[1], min(dx[0], dx[2])


def distanza(p, bbox):
    """Distanza in gradi del punto dal rettangolo, con la longitudine misurata anche facendo il giro a +-180."""
    _, miny, _, maxy = bbox
    return math.hypot(min(distanze_x(p, bbox)) * math.cos(math.radians(p[1])), max(miny - p[1], 0, p[1] - maxy))


def rettangolo_di(ring):
    xs, ys = [p[0] for p in ring], [p[1] for p in ring]
    return min(xs), min(ys), max(xs), max(ys)


def eccedenze(geometry, rettangoli, min_km=30, coperto=None):
    """Parti del poligono [geometry] (GeoJSON) fuori da tutti i [rettangoli] {regione: bbox} delle regioni tagliate su
    quel confine e non coperte da un'altra regione (coperto(punto), sul centro della parte: i territori d'oltremare con
    una regione propria, come Riunione o le isole Cocos), ognuna assegnata al rettangolo piu' vicino e alla sua zona
    (ovest, est, sud, nord); per regione e zona, se la parte ne esce piu' di [min_km]: regione, lato, km2, km,
    rettangolo, antimeridiano."""
    geometry = geometry.get("geometry", geometry)
    polys = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
    # Il primo anello di ogni poligono e' il contorno (+1), gli altri sono buchi (-1).
    parti = []
    for poly in polys:
        for n, r in enumerate(poly):
            ring = [tuple(p[:2]) for p in r[:-1]]
            if len(ring) >= 3:
                parti.append((ring, 1 if n == 0 else -1, rettangolo_di(ring)))
    # I rettangoli piu' grandi prima: tolgono subito la maggior parte dei vertici.
    for bbox in sorted(rettangoli.values(), key=lambda b: (b[2] - b[0]) * (b[3] - b[1]), reverse=True):
        minx, miny, maxx, maxy = bbox
        resto = []
        for ring, segno, (ax, ay, bx, by) in parti:
            if bx < minx or ax > maxx or by < miny or ay > maxy:
                resto.append((ring, segno, (ax, ay, bx, by)))
                continue
            for semipiani in zone(bbox).values():
                parte = ring
                for dentro, incrocio in semipiani:
                    parte = ritaglia(parte, dentro, incrocio)
                if len(parte) >= 3:
                    resto.append((parte, segno, rettangolo_di(parte)))
        parti = resto

    gruppi = {}
    for ring, segno, _ in parti:
        centro = (sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring))
        if coperto and coperto(centro):
            continue
        regione = min(rettangoli, key=lambda r: (distanza(centro, rettangoli[r]), r))
        minx, miny, maxx, maxy = rettangoli[regione]
        lato = "ovest" if centro[0] < minx else "est" if centro[0] > maxx else "sud" if centro[1] < miny else "nord"
        # Oltre l'antimeridiano: il rettangolo e' piu' vicino facendo il giro a +-180 (le Chatham per la Nuova Zelanda).
        diretta, con_giro = distanze_x(centro, rettangoli[regione])
        gruppo = gruppi.setdefault((regione, lato, con_giro < diretta), {"km2": 0.0, "punti": []})
        gruppo["km2"] += area_km2(ring) * segno
        gruppo["punti"] += ring if segno > 0 else []

    out = []
    for (regione, lato, antimeridiano), gruppo in sorted(gruppi.items()):
        punti = gruppo["punti"]
        if gruppo["km2"] <= 0 or not punti:
            continue
        bbox = rettangoli[regione]
        km = max(distanza(p, bbox) for p in punti) * KM_PER_GRADO
        if km < min_km:
            continue
        xs, ys = [p[0] for p in punti], [p[1] for p in punti]
        rettangolo = (min(xs), min(ys), max(xs), max(ys))
        out.append({"regione": regione, "lato": lato, "km2": gruppo["km2"], "km": km, "rettangolo": rettangolo,
                    "antimeridiano": antimeridiano})
    return out


def luoghi(relation, rettangolo, user_agent):
    """Luoghi abitati della relazione dentro [rettangolo], da Overpass; "?" se Overpass non risponde."""
    minx, miny, maxx, maxy = rettangolo
    query = (f'[out:json][timeout:180];rel({relation});map_to_area->.a;'
             f'node["place"~"^(city|town|village|hamlet)$"](area.a)({miny},{minx},{maxy},{maxx});out count;')
    request = urllib.request.Request(OVERPASS, data=urllib.parse.urlencode({"data": query}).encode(),
                                     headers={"User-Agent": user_agent})
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=240) as response:
                return str(json.load(response)["elements"][0]["tags"]["nodes"])
        except (OSError, ValueError) as error:
            print(f"-- overpass: relazione {relation}: {error} ({attempt}/3)", file=sys.stderr, flush=True)
            time.sleep(30 * attempt)
    return "?"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--manifest", required=True, help="manifest.json pubblicato (file locale)")
    parser.add_argument("--cache", required=True, help="cartella dei poligoni di Nominatim (confine-r<id>.json)")
    parser.add_argument("--user-agent", default="pocket-travel-bbox-audit/1.0 (https://github.com/miracle091/pocket-travel)")
    parser.add_argument("--min-km", type=float, default=30)
    parser.add_argument("--places", action="store_true", help="conta i luoghi abitati di ogni zona con Overpass")
    parser.add_argument("--out", help="file TSV (predefinito: standard output)")
    args = parser.parse_args()
    with open(args.manifest, encoding="utf-8") as f:
        regions = sorted((r for r in json.load(f)["regions"] if r.get("regionId")), key=lambda r: r["regionId"])
    bbox = {r["regionId"]: tuple(r["map"]["source"][k] for k in ("minLon", "minLat", "maxLon", "maxLat")) for r in regions}

    out = open(args.out, "w", encoding="utf-8") if args.out else sys.stdout
    out.write("regione\trelazione\tlato\tkm2\tkm\trettangolo\tantimeridiano" + ("\tluoghi" if args.places else "") + "\n")
    relazione = {r["regionId"]: r["poi"].get("borderRelation") for r in regions}
    per_relazione = {}
    for rid, rel in relazione.items():
        if isinstance(rel, int):
            per_relazione.setdefault(rel, {})[rid] = bbox[rid]
    poligoni = {}

    def confine(rel):
        if rel not in poligoni:
            geometry = country_border.poligono_completo(rel, args.cache, args.user_agent)
            poligoni[rel] = geometry and Confine(geometry)
        return poligoni[rel]

    for relation, rettangoli in per_relazione.items():
        if confine(relation) is None:
            print(f"-- {', '.join(rettangoli)}: poligono della relazione {relation} non disponibile", file=sys.stderr, flush=True)
            continue

        def coperto(p, rettangoli=rettangoli):
            # Dentro il rettangolo di un'altra regione e dentro il suo confine, o regione senza confine (Canarie). Un
            # rettangolo da solo non basta: le Aleutine occidentali stanno in quello della Russia, Estremo Oriente.
            for rid, (minx, miny, maxx, maxy) in bbox.items():
                if rid not in rettangoli and minx <= p[0] <= maxx and miny <= p[1] <= maxy:
                    rel = relazione[rid]
                    if not isinstance(rel, int) or (confine(rel) and confine(rel).dentro(*p)):
                        return True
            return False

        for e in eccedenze(country_border.poligono_completo(relation, args.cache, args.user_agent), rettangoli, args.min_km, coperto):
            riga = [e["regione"], str(relation), e["lato"], f"{e['km2']:.0f}", f"{e['km']:.0f}",
                    ",".join(f"{v:.2f}" for v in e["rettangolo"]), "si" if e["antimeridiano"] else "no"]
            if args.places:
                time.sleep(1)
                riga.append(luoghi(relation, e["rettangolo"], args.user_agent))
            out.write("\t".join(riga) + "\n")
            out.flush()
    if args.out:
        out.close()


if __name__ == "__main__":
    main()
