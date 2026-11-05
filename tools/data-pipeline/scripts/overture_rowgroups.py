#!/usr/bin/env python3
"""Statistiche dei row group Parquet del tema addresses di Overture, nel CSV che legge
seed-address-grid.py: una riga per row group con le colonne "row_group_num_rows", "bbox, xmin_mn",
"bbox, xmax_mx", "bbox, ymin_mn", "bbox, ymax_mx" (minimo di xmin, massimo di xmax, minimo di ymin e
massimo di ymax del row group, in gradi).

Legge solo i metadati dei file (parquet_metadata di DuckDB, qualche richiesta Range per file sul footer):
mai i dati.

Uso: overture_rowgroups.py <out.csv> [--release latest|2026-09-23.1]

Richiede il pacchetto duckdb (con l'estensione httpfs, caricata qui).
"""
import argparse
import csv
import sys
import time

import overture_addresses

COLUMNS = ["row_group_num_rows", "bbox, xmin_mn", "bbox, xmax_mx", "bbox, ymin_mn", "bbox, ymax_mx"]

# (path_in_schema della colonna, colonna del CSV, statistica da usare)
_BBOX_FIELDS = {
    "bbox, xmin": ("bbox, xmin_mn", "min"),
    "bbox, xmax": ("bbox, xmax_mx", "max"),
    "bbox, ymin": ("bbox, ymin_mn", "min"),
    "bbox, ymax": ("bbox, ymax_mx", "max"),
}


def rowgroup_rows(metadata):
    """Righe del CSV dai metadati: metadata e' un elenco di (file, row group, righe del row group,
    path_in_schema, statistica minima, statistica massima), una per colonna bbox di ogni row group.
    I row group senza tutte e quattro le statistiche (o con valori non numerici) restano fuori.
    Ordine: per file e row group."""
    groups = {}
    for file_name, group_id, num_rows, path, stat_min, stat_max in metadata:
        field = _BBOX_FIELDS.get(path)
        if field is None:
            continue
        column, which = field
        raw = stat_min if which == "min" else stat_max
        try:
            value = float(raw)
        except (TypeError, ValueError):
            continue
        group = groups.setdefault((file_name, group_id), {"row_group_num_rows": int(num_rows)})
        group[column] = value
    return [groups[key] for key in sorted(groups) if all(column in groups[key] for column in COLUMNS)]


def write_csv(rows, out_path):
    with open(out_path, "w", encoding="utf-8", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


def fetch_metadata(release):
    """Metadati bbox dei row group del tema addresses del rilascio, nella forma di rowgroup_rows()."""
    import duckdb

    con = duckdb.connect()
    con.sql("INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2';")
    pattern = f"s3://overturemaps-us-west-2/release/{release}/theme=addresses/type=address/*.parquet"
    paths = ", ".join(overture_addresses.sql_string(path) for path in _BBOX_FIELDS)
    return con.sql(f"""
        SELECT file_name, row_group_id, row_group_num_rows, path_in_schema, stats_min, stats_max
        FROM parquet_metadata({overture_addresses.sql_string(pattern)})
        WHERE path_in_schema IN ({paths})
    """).fetchall()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output")
    parser.add_argument("--release", default="latest")
    args = parser.parse_args()

    release = overture_addresses.latest_release() if args.release == "latest" else args.release
    start = time.monotonic()
    rows = rowgroup_rows(fetch_metadata(release))
    if not rows:
        raise RuntimeError(f"nessun row group con statistiche bbox nel rilascio {release}")
    write_csv(rows, args.output)
    print(f"overture_rowgroups: rilascio {release}, {len(rows)} row group, "
          f"{sum(row['row_group_num_rows'] for row in rows):,} righe, {time.monotonic() - start:.0f} s, "
          f"scritti in {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
