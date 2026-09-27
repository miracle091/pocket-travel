#!/usr/bin/env python3
"""Genera il seme della griglia adattiva dei civici (address-grid-seed.tsv, una riga "z/x/y" per
cella iniziale, vedi .claude/docs/address-grid-plan.md): quadtree Web Mercator, diviso finche' la
dimensione stimata di ogni cella sta sotto <cap> (byte) o si arriva a <zoom> (default 12) - oltre,
la pipeline stessa divide ulteriormente le celle davvero troppo pesanti fino a z14 quando le
costruisce (build-address-cell.sh); il seme e' solo il punto di partenza, non il tetto vero della
griglia (quello e' z<=14, vedi ValidateManifest).

Porta qui le misure del 2026-09-27, fatte con script di prova fuori dal repo, in un unico script
senza dipendenze esterne:
  1. civici gia' pubblicati: la directory del PMTiles di ogni regione con "addresses.file.url" nel
     manifest si legge via richieste HTTP Range (solo intestazione e indici, mai il file intero,
     vedi tiles()) per il peso di ogni tile z14; il massimo tra regioni diverse toglie i doppioni ai
     confini (una tile puo' comparire nell'estratto di piu' regioni vicine);
  2. Overture: statistiche dei row group Parquet del tema addresses (un CSV con le colonne del
     comando "aws s3api" / query DuckDB su parquet_metadata, vedi overture-and-map-diff-research.md
     passo 2), sommando le righe dei row group nelle celle z<zoom> che il loro bbox tocca;
  3. combinato = max(byte OSM, righe Overture * BYTES_PER_ADDR) * MARGIN per cella, stessa stima del
     piano (address-grid-plan.md).

Uso:
  seed-address-grid.py <manifest.json (path o URL)> <overture-rowgroups.csv> <output.tsv>
    [--cap-mb 10] [--zoom 12] [--bytes-per-addr 10] [--margin 1.1]

Richiede solo la libreria standard (urllib per l'HTTP Range e per scaricare manifest.json se e' un URL).
"""
import argparse
import csv
import gzip
import json
import math
import struct
import sys
import urllib.request
from collections import defaultdict

USER_AGENT = "pocket-travel-seed-address-grid/1.0"


# --- Lettura della directory di un PMTiles remoto via richieste HTTP Range (porta pmdir.py) --------

def _http_range(url, start, length):
    req = urllib.request.Request(url, headers={"Range": f"bytes={start}-{start + length - 1}", "User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def _varint(b, i):
    shift = 0
    value = 0
    while True:
        byte = b[i]
        i += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return value, i


def _parse_directory(b):
    n, i = _varint(b, 0)
    ids, run_lengths, lengths, offsets = [], [], [], []
    last_id = 0
    for _ in range(n):
        delta, i = _varint(b, i)
        last_id += delta
        ids.append(last_id)
    for _ in range(n):
        v, i = _varint(b, i)
        run_lengths.append(v)
    for _ in range(n):
        v, i = _varint(b, i)
        lengths.append(v)
    for k in range(n):
        v, i = _varint(b, i)
        offsets.append(offsets[k - 1] + lengths[k - 1] if v == 0 and k > 0 else v - 1)
    return list(zip(ids, run_lengths, lengths, offsets))


def _decompress(b, compression):
    return gzip.decompress(b) if compression == 2 else b


def pmtiles_z14_lengths(url):
    """{tileId z14: lunghezza in byte} di un PMTiles remoto v3, letto solo per intestazione e indici."""
    header = _http_range(url, 0, 127)
    if header[:7] != b"PMTiles" or header[7] != 3:
        raise ValueError(f"{url}: non e' un PMTiles v3")
    root_off, root_len, _meta_off, _meta_len, leaf_off, _leaf_len, _data_off, _data_len = struct.unpack("<8Q", header[8:72])
    compression = header[97]
    lengths = {}

    def walk(offset, length):
        for tile_id, run_length, tile_len, tile_off in _parse_directory(_decompress(_http_range(url, offset, length), compression)):
            if run_length == 0:
                walk(leaf_off + tile_off, tile_len)
            else:
                for k in range(run_length):
                    lengths[tile_id + k] = tile_len

    walk(root_off, root_len)
    return lengths


def tile_id_to_zxy(tile_id):
    """Id lineare della curva Hilbert (schema PMTiles) -> (z, x, y)."""
    accumulated = 0
    z = 0
    while True:
        tiles_at_z = 1 << (2 * z)
        if accumulated + tiles_at_z > tile_id:
            break
        accumulated += tiles_at_z
        z += 1
    pos = tile_id - accumulated
    x = y = 0
    s = 1
    n = 1 << z
    while s < n:
        rx = 1 & (pos // 2)
        ry = 1 & (pos ^ rx)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        x += s * rx
        y += s * ry
        pos //= 4
        s *= 2
    return z, x, y


# --- Proiezione Web Mercator (stessa di GenerateAddresses.kt/simulate.py) ---------------------------

def lon_to_x(lon, zoom):
    return int((lon + 180) / 360 * (1 << zoom))


def lat_to_y(lat, zoom):
    lat = max(min(lat, 85.05), -85.05)
    r = math.radians(lat)
    return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * (1 << zoom))


# --- Passo 1: byte OSM gia' pubblicati, per cella z<zoom> (massimo tra regioni, niente doppioni) ----

def osm_bytes_per_cell(manifest, zoom):
    addresses_urls = [
        region["addresses"]["file"]["url"]
        for region in manifest.get("regions", [])
        if (region.get("addresses") or {}).get("file")
    ]
    print(f"-- {len(addresses_urls)} regioni con civici pubblicati", file=sys.stderr)
    tile_max_length = {}
    for i, url in enumerate(addresses_urls):
        try:
            lengths = pmtiles_z14_lengths(url)
        except Exception as e:  # rete non disponibile per questa regione: non fatale, si continua
            print(f"-- civici {url}: {e}", file=sys.stderr)
            continue
        for tile_id, length in lengths.items():
            if length > tile_max_length.get(tile_id, 0):
                tile_max_length[tile_id] = length
        print(f"-- [{i + 1}/{len(addresses_urls)}] {url}: {len(lengths)} tile z14", file=sys.stderr)

    per_cell = defaultdict(int)
    shift_from_z14 = 14 - zoom
    for tile_id, length in tile_max_length.items():
        z, x, y = tile_id_to_zxy(tile_id)
        if z != 14:
            continue
        per_cell[(x >> shift_from_z14, y >> shift_from_z14)] += length
    return per_cell


# --- Passo 2: righe Overture stimate per cella z<zoom>, dai row group Parquet -----------------------

def overture_rows_per_cell(rowgroups_csv, zoom):
    per_cell = defaultdict(float)
    total_rows = 0
    with open(rowgroups_csv, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            rows = int(row["row_group_num_rows"])
            total_rows += rows
            x0, x1 = lon_to_x(float(row["bbox, xmin_mn"]), zoom), lon_to_x(float(row["bbox, xmax_mx"]), zoom)
            y0, y1 = lat_to_y(float(row["bbox, ymax_mx"]), zoom), lat_to_y(float(row["bbox, ymin_mn"]), zoom)
            if x1 - x0 > 200 or y1 - y0 > 200:  # riquadro anomalo (dataset intercontinentale): al centro
                x1, y1 = x0, y0
            cells = (x1 - x0 + 1) * (y1 - y0 + 1)
            share = rows / cells
            for x in range(x0, x1 + 1):
                for y in range(y0, y1 + 1):
                    per_cell[(x, y)] += share
    print(f"-- Overture: {total_rows / 1e6:.0f} M righe nei row group", file=sys.stderr)
    return per_cell


# --- Passo 3: quadtree, diviso finche' sotto <cap> o a <zoom> ---------------------------------------

def quadtree_seed(combined_bytes_per_cell, zoom, cap_bytes):
    cells = []

    def recurse(z, x, y, items):
        total = sum(v for _, v in items)
        if total == 0:
            return
        if total <= cap_bytes or z == zoom:
            cells.append((z, x, y))
            return
        shift = zoom - z - 1
        children = defaultdict(list)
        for (tx, ty), v in items:
            children[((tx >> shift) & 1, (ty >> shift) & 1)].append(((tx, ty), v))
        for (dx, dy), sub_items in children.items():
            recurse(z + 1, 2 * x + dx, 2 * y + dy, sub_items)

    recurse(0, 0, 0, list(combined_bytes_per_cell.items()))
    return cells


def load_manifest(path_or_url):
    if path_or_url.startswith("http://") or path_or_url.startswith("https://"):
        req = urllib.request.Request(path_or_url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    with open(path_or_url, encoding="utf-8") as f:
        return json.load(f)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", help="manifest.json pubblicato (path o URL)")
    parser.add_argument("rowgroups_csv", help="statistiche dei row group Overture (colonne di parquet_metadata)")
    parser.add_argument("output", help="address-grid-seed.tsv da scrivere")
    parser.add_argument("--cap-mb", type=float, default=10.0)
    parser.add_argument("--zoom", type=int, default=12)
    parser.add_argument("--bytes-per-addr", type=float, default=10.0)
    parser.add_argument("--margin", type=float, default=1.1)
    args = parser.parse_args()

    manifest = load_manifest(args.manifest)
    osm = osm_bytes_per_cell(manifest, args.zoom)
    overture = overture_rows_per_cell(args.rowgroups_csv, args.zoom)

    combined = {}
    for key in set(osm) | set(overture):
        combined[key] = max(osm.get(key, 0), overture.get(key, 0) * args.bytes_per_addr) * args.margin

    cells = quadtree_seed(combined, args.zoom, args.cap_mb * 1e6)
    cells.sort()
    with open(args.output, "w", encoding="utf-8") as out:
        for z, x, y in cells:
            out.write(f"{z}/{x}/{y}\n")
    over_cap = sum(1 for z, x, y in cells if z == args.zoom)
    print(f"seme: {len(cells)} celle (tetto {args.cap_mb} MB, zoom {args.zoom}; {over_cap} gia' a z{args.zoom}) scritte in {args.output}")


if __name__ == "__main__":
    main()
