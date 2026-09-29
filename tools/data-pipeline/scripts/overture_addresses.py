#!/usr/bin/env python3
"""Punti Overture (tema addresses) di una cella della griglia adattiva,
filtrati per bbox e per la lista bianca dei dataset
(tools/data-pipeline/overture-address-sources.tsv): scrive "lat<TAB>lon<TAB>numero<TAB>dataset" per
ogni indirizzo ammesso in <output>, una riga per indirizzo, per GenerateAddresses (--overture).

La lista bianca elenca coppie esatte (sources[1].dataset, sources[1].license), una per dataset
letto a mano: i nomi dei dataset non seguono sempre il
codice del paese ("NAD" per gli Stati Uniti, "BAG Light" per i Paesi Bassi...), e un dataset nuovo
o con una licenza cambiata in un rilascio successivo resta fuori finche' non viene rivisto. Il
filtro su "country" (colonna semplice, con statistiche per row group) va prima, per scartare i row
group fuori lista senza scaricarli.

Uso: overture_addresses.py <minLon> <minLat> <maxLon> <maxLat> <whitelist.tsv> <output.tsv>
       [--release latest|2026-09-23.1]
     overture_addresses.py --check-whitelist <whitelist.tsv> <report.md> [--release ...]
       confronta tutte le coppie (paese, dataset, licenza) del rilascio con la lista (ammesse ed
       escluse) e scrive in <report.md> le coppie nuove e quelle sparite; file vuoto se non ce ne sono.

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


def load_excluded_areas(path):
    """Aree escluse dentro una fonte ammessa (sesta colonna facoltativa, poligono WKT in gradi):
    [(dataset, licenza, wkt)]. Esempio: Lake County dentro us/mn/statewide."""
    areas = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            fields = [field.strip() for field in line.split("\t")]
            if len(fields) < 6 or fields[3] != "allow" or not fields[5]:
                continue
            if not fields[5].startswith(("POLYGON", "MULTIPOLYGON")):
                raise ValueError(f"area esclusa non valida per {fields[1]}: serve un POLYGON o MULTIPOLYGON WKT")
            areas.append((fields[1], fields[2], fields[5]))
    return areas


def excluded_areas_clause(areas):
    """Condizione SQL che scarta i punti di una fonte dentro la sua area esclusa (vuota senza aree)."""
    return "".join(
        f"\n          AND NOT (sources[1].dataset = {sql_string(dataset)}"
        f" AND sources[1].license = {sql_string(license_id)}"
        f" AND ST_Within(geometry, ST_GeomFromText({sql_string(wkt)})))"
        for dataset, license_id, wkt in areas
    )


def load_reviewed(path):
    """Tutte le coppie gia' riviste della lista bianca, ammesse ed escluse: {(paese, dataset, licenza)}."""
    reviewed = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            fields = [field.strip() for field in line.split("\t")]
            if len(fields) >= 4 and all(fields[:3]):
                reviewed.add(tuple(fields[:3]))
    return reviewed


def compare_with_whitelist(release_pairs, reviewed):
    """Coppie del rilascio mai riviste e coppie della lista sparite dal rilascio.

    release_pairs: [(paese, dataset, licenza, righe)]. Una fonte che cambia licenza compare in
    entrambe le liste: nuova con la licenza nuova, sparita con quella vecchia.
    """
    in_release = {(c, d, l) for c, d, l, _ in release_pairs}
    new = sorted((p for p in release_pairs if p[:3] not in reviewed), key=lambda p: (-p[3], p[:3]))
    missing = sorted(reviewed - in_release)
    return new, missing


def check_report(release, new, missing):
    """Testo Markdown per la issue e il riepilogo del job; vuoto se non c'e' niente da rivedere."""
    if not new and not missing:
        return ""
    lines = [
        f"Rilascio Overture `{release}`, tema addresses, confrontato con "
        "`tools/data-pipeline/overture-address-sources.tsv`.",
        "",
        "Le coppie nuove restano fuori dalle celle dei civici finche' non si aggiunge una riga "
        "`allow` o `exclude` con l'attribuzione, dopo aver letto i termini dell'ente.",
    ]
    if new:
        lines += ["", f"### Coppie nuove ({len(new)}, mai riviste)", "",
                  "| Paese | Dataset | Licenza | Righe |", "|---|---|---|--:|"]
        lines += [f"| {c} | {d} | {l} | {n} |" for c, d, l, n in new]
    if missing:
        lines += ["", f"### Coppie della lista sparite dal rilascio ({len(missing)})", "",
                  "Spesso la stessa fonte con una licenza diversa (vedi sopra) o un dataset rinominato.", "",
                  "| Paese | Dataset | Licenza |", "|---|---|---|"]
        lines += [f"| {c} | {d} | {l} |" for c, d, l in missing]
    return "\n".join(lines) + "\n"


def merge_pair_counts(totals, rows):
    """Somma in [totals] le righe (paese, dataset, licenza, righe) di un file del rilascio."""
    for country, dataset, license_id, count in rows:
        key = (country, dataset, license_id)
        totals[key] = totals.get(key, 0) + count
    return totals


def fetch_release_pairs(release, log=lambda message: print(message, file=sys.stderr, flush=True)):
    """(paese, dataset, licenza, righe) di tutto il tema addresses del rilascio: solo le colonne
    country e sources. Un file parquet alla volta (64 nel rilascio 2026-09-23.1), con una riga di
    log per file: la lettura dura decine di minuti e senza log non si capisce a che punto e'."""
    import time

    con = duckdb.connect()
    con.sql("INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2';")
    pattern = f"s3://overturemaps-us-west-2/release/{release}/theme=addresses/type=address/*.parquet"
    files = [row[0] for row in con.sql(f"SELECT file FROM glob({sql_string(pattern)}) ORDER BY file").fetchall()]
    if not files:
        raise RuntimeError(f"nessun file parquet per il rilascio {release}")
    log(f"overture_addresses: rilascio {release}, {len(files)} file da leggere (colonne country e sources)")
    totals = {}
    rows_read = 0
    start = time.monotonic()
    for index, path in enumerate(files, start=1):
        rows = con.sql(f"""
            SELECT country, sources[1].dataset, sources[1].license, count(*)
            FROM read_parquet({sql_string(path)})
            WHERE country IS NOT NULL AND sources[1].dataset IS NOT NULL AND sources[1].license IS NOT NULL
            GROUP BY ALL
        """).fetchall()
        merge_pair_counts(totals, rows)
        rows_read += sum(row[3] for row in rows)
        elapsed = time.monotonic() - start
        remaining = elapsed / index * (len(files) - index)
        log(f"  file {index}/{len(files)} ({path.rsplit('/', 1)[-1][:10]}): {sum(row[3] for row in rows):,} righe;"
            f" finora {rows_read:,} righe, {len(totals)} coppie, {elapsed / 60:.1f} min, ne mancano circa {remaining / 60:.0f}")
    return [(c, d, l, n) for (c, d, l), n in totals.items()]


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


def fetch_addresses(release, min_lon, min_lat, max_lon, max_lat, allowed, excluded_areas=()):
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
          AND sources[1].dataset || '|' || sources[1].license IN ({pairs}){excluded_areas_clause(excluded_areas)}
    """
    return con.sql(query).fetchall()


def main():
    if sys.argv[1:] == ["--print-release"]:
        print(latest_release())
        return
    if sys.argv[1:2] == ["--check-whitelist"]:
        check = argparse.ArgumentParser()
        check.add_argument("--check-whitelist", dest="whitelist", required=True)
        check.add_argument("report")
        check.add_argument("--release", default="latest")
        args = check.parse_args()
        release = latest_release() if args.release == "latest" else args.release
        reviewed = load_reviewed(args.whitelist)
        print(f"overture_addresses: lista bianca con {len(reviewed)} coppie riviste", file=sys.stderr, flush=True)
        new, missing = compare_with_whitelist(fetch_release_pairs(release), reviewed)
        with open(args.report, "w", encoding="utf-8") as out:
            out.write(check_report(release, new, missing))
        print(f"overture_addresses: rilascio {release}, {len(new)} coppie nuove, {len(missing)} sparite",
              file=sys.stderr)
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
    rows = fetch_addresses(release, args.min_lon, args.min_lat, args.max_lon, args.max_lat, allowed,
                           load_excluded_areas(args.whitelist))
    with open(args.output, "w", encoding="utf-8") as out:
        for lat, lon, number, dataset in rows:
            if lat is None or lon is None or number is None:
                continue
            out.write(f"{lat}\t{lon}\t{number}\t{dataset}\n")
    print(f"overture_addresses: {len(rows)} punti scritti in {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
