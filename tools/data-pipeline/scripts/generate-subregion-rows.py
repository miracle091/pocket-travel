#!/usr/bin/env python3
"""Righe di pilot-regions.sh per le suddivisioni di un paese (stati, province...), dai confini
Natural Earth "admin 1" (dominio pubblico), per dividere una regione troppo grande.

Si lancia a mano quando si divide un paese; l'output si incolla in pilot-regions.sh al posto della
regione intera. Solo libreria standard.

Uso:
  generate-subregion-rows.py --geojson ne_50m_admin_1_states_provinces.geojson --country USA \
      --id-prefix stati-uniti --display-prefix "Stati Uniti" --flag us \
      --group "Stati Uniti d'America" --continent "Nord America" \
      --exclude Alaska,Hawaii \
      --name "District of Columbia=Distretto di Columbia" \
      --wiki "Georgia=Georgia_(U.S._state)" [--check-wikivoyage]

Formato della riga (vedi pilot-regions.sh):
  regionId|displayName|minLon|minLat|maxLon|maxLat|wikivoyagePageTitle|flagCode|groupName|groupLabel|continent
"""
import argparse
import json
import math
import re
import sys
import unicodedata
import urllib.parse
import urllib.request


def bbox(geometry):
    """Riquadro di Polygon/MultiPolygon, arrotondato verso l'esterno a 0,01 gradi."""
    xs, ys = [], []

    def walk(coords):
        if isinstance(coords[0], (int, float)):
            xs.append(coords[0])
            ys.append(coords[1])
        else:
            for c in coords:
                walk(c)

    walk(geometry["coordinates"])
    return (
        math.floor(min(xs) * 100) / 100,
        math.floor(min(ys) * 100) / 100,
        math.ceil(max(xs) * 100) / 100,
        math.ceil(max(ys) * 100) / 100,
    )


def slug(text):
    ascii_text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_text.lower()).strip("-")


def pairs(values):
    """"A=B" ripetuti -> dizionario."""
    return dict(v.split("=", 1) for v in values or [])


def wikivoyage_missing(titles):
    """Titoli che non esistono su Wikivoyage EN o non hanno la pagina italiana (langlink)."""
    missing = []
    for title in titles:
        query = urllib.parse.urlencode({
            "action": "query", "format": "json", "titles": title.replace("_", " "), "redirects": 1,
            "prop": "langlinks", "lllang": "it",
        })
        request = urllib.request.Request(
            "https://en.wikivoyage.org/w/api.php?" + query,
            headers={"User-Agent": "PocketTravelDataPipeline/1.0 (https://github.com/miracle091/pocket-travel)"},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            pages = json.load(response)["query"]["pages"]
        page = next(iter(pages.values()))
        if "missing" in page:
            missing.append(f"{title}: pagina EN assente")
        elif not page.get("langlinks"):
            missing.append(f"{title}: nessuna pagina IT (la guida usera' quella inglese)")
    return missing


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--geojson", required=True)
    parser.add_argument("--country", required=True, help="adm0_a3 di Natural Earth, es. USA")
    parser.add_argument("--id-prefix", required=True)
    parser.add_argument("--display-prefix", required=True)
    parser.add_argument("--flag", required=True)
    parser.add_argument("--group", required=True)
    parser.add_argument("--continent", required=True)
    parser.add_argument("--exclude", default="", help="nomi (inglesi) da saltare, separati da virgola")
    parser.add_argument("--name", action="append", help="nome inglese=nome italiano, per sostituire name_it")
    parser.add_argument("--wiki", action="append", help="nome inglese=titolo Wikivoyage EN")
    parser.add_argument("--check-wikivoyage", action="store_true")
    args = parser.parse_args()

    names = pairs(args.name)
    wiki = pairs(args.wiki)
    exclude = {n.strip() for n in args.exclude.split(",") if n.strip()}
    with open(args.geojson, encoding="utf-8") as f:
        features = json.load(f)["features"]

    rows, titles = [], []
    for feature in features:
        props = feature["properties"]
        if props.get("adm0_a3") != args.country or props["name"] in exclude:
            continue
        name_it = names.get(props["name"]) or props.get("name_it") or props["name"]
        title = wiki.get(props["name"], props["name"].replace(" ", "_"))
        min_lon, min_lat, max_lon, max_lat = bbox(feature["geometry"])
        rows.append((
            f"{args.id_prefix}-{slug(name_it)}", f"{args.display_prefix} - {name_it}",
            f"{min_lon:.2f}", f"{min_lat:.2f}", f"{max_lon:.2f}", f"{max_lat:.2f}",
            title, args.flag, args.group, name_it, args.continent,
        ))
        titles.append(title)

    for row in sorted(rows, key=lambda r: r[0]):
        print(f'  "{"|".join(row)}"')
    if args.check_wikivoyage:
        for problem in wikivoyage_missing(titles):
            print(problem, file=sys.stderr)


if __name__ == "__main__":
    main()
