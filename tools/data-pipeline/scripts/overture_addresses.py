#!/usr/bin/env python3
"""Punti Overture (tema addresses) di una cella della griglia adattiva (vedi
.claude/docs/address-grid-plan.md), filtrati per bbox e per la lista bianca dei dataset
(tools/data-pipeline/overture-address-sources.tsv): scrive "lat<TAB>lon<TAB>numero<TAB>dataset" per
ogni indirizzo ammesso in <output>, una riga per indirizzo, per GenerateAddresses (--overture).

La lista bianca elenca coppie esatte (sources[1].dataset, sources[1].license), una per dataset
letto a mano (.claude/docs/overture-address-licences.md): i nomi dei dataset non seguono sempre il
codice del paese ("NAD" per gli Stati Uniti, "BAG Light" per i Paesi Bassi...), e un dataset nuovo
o con una licenza cambiata in un rilascio successivo resta fuori finche' non viene rivisto. Il
filtro su "country" (colonna semplice, con statistiche per row group) va prima, per scartare i row
group fuori lista senza scaricarli.

Uso: overture_addresses.py <minLon> <minLat> <maxLon> <maxLat> <whitelist.tsv> <output.tsv>
       [--release latest|2026-09-23.1]

Con "latest" (default) si usa il rilascio piu' recente elencato nel bucket pubblico di Overture;
il workflow lo risolve una volta per run (--print-release) e lo passa a ogni cella.

Richiede il pacchetto duckdb (con le estensioni httpfs e spatial, caricate qui).
"""
import argparse
import sys

import duckdb


def load_whitelist(path):
    """Coppie "allow" della lista bianca: (paese, dataset, licenza). Righe commentate con # e vuote ignorate.

    Colonne: paese, dataset, licenza, decisione, attribuzione.
    """
    allowed = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) < 4:
                continue
            country, dataset, license_id, decision = (field.strip() for field in fields[:4])
            if decision == "allow" and country and dataset and license_id:
                allowed.append((country, dataset, license_id))
    return allowed


def sql_string(value):
    return "'" + value.replace("'", "''") + "'"


def latest_release():
    """Rilascio piu' recente nel bucket pubblico (i nomi sono date ISO con un suffisso .N: basta l'ordine)."""
    import re
    import urllib.request

    url = "https://overturemaps-us-west-2.s3.amazonaws.com/?list-type=2&prefix=release/&delimiter=/"
    with urllib.request.urlopen(url, timeout=60) as response:
        listing = response.read().decode("utf-8")
    releases = re.findall(r"<Prefix>release/(\d{4}-\d{2}-\d{2}\.\d+)/</Prefix>", listing)
    if not releases:
        raise RuntimeError("nessun rilascio Overture nell'elenco del bucket")
    return max(releases, key=lambda r: (r.split(".")[0], int(r.split(".")[1])))


def fetch_addresses(release, min_lon, min_lat, max_lon, max_lat, allowed):
    countries = sorted({country for country, _, _ in allowed})
    pairs = ", ".join(sql_string(f"{dataset}|{license_id}") for _, dataset, license_id in allowed)
    country_clause = ", ".join(sql_string(c) for c in countries)

    con = duckdb.connect()
    # INSTALL prima di LOAD: sul runner di CI (ogni volta pulito) le estensioni non ci sono ancora.
    con.sql("INSTALL httpfs; INSTALL spatial; LOAD httpfs; LOAD spatial; SET s3_region='us-west-2';")
    src = (
        f"read_parquet('s3://overturemaps-us-west-2/release/{release}"
        "/theme=addresses/type=address/*.parquet')"
    )
    query = f"""
        SELECT ST_Y(geometry), ST_X(geometry), number, sources[1].dataset
        FROM {src}
        WHERE bbox.xmin BETWEEN {min_lon} AND {max_lon}
          AND bbox.ymin BETWEEN {min_lat} AND {max_lat}
          AND number IS NOT NULL AND number != ''
          AND country IN ({country_clause})
          AND sources[1].dataset || '|' || sources[1].license IN ({pairs})
    """
    return con.sql(query).fetchall()


def main():
    if sys.argv[1:] == ["--print-release"]:
        print(latest_release())
        return
    parser = argparse.ArgumentParser()
    parser.add_argument("min_lon", type=float)
    parser.add_argument("min_lat", type=float)
    parser.add_argument("max_lon", type=float)
    parser.add_argument("max_lat", type=float)
    parser.add_argument("whitelist")
    parser.add_argument("output")
    parser.add_argument("--release", default="latest")
    args = parser.parse_args()

    allowed = load_whitelist(args.whitelist)
    if not allowed:
        open(args.output, "w", encoding="utf-8").close()
        print("overture_addresses: lista bianca vuota, 0 punti", file=sys.stderr)
        return

    release = latest_release() if args.release == "latest" else args.release
    rows = fetch_addresses(release, args.min_lon, args.min_lat, args.max_lon, args.max_lat, allowed)
    with open(args.output, "w", encoding="utf-8") as out:
        for lat, lon, number, dataset in rows:
            if lat is None or lon is None or number is None:
                continue
            out.write(f"{lat}\t{lon}\t{number}\t{dataset}\n")
    print(f"overture_addresses: {len(rows)} punti scritti in {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
