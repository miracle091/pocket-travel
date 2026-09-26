#!/usr/bin/env python3
"""Ritaglia un segmento BRouter (.rd5, tile 5x5 gradi) sul riquadro di una regione.

I .rd5 di brouter.de coprono 5x5 gradi: il Lussemburgo scarica 435 MB per due tile di cui usa l'1%.
Il formato (third-party/brouter-core: PhysicalFile, OsmFile) permette di togliere dati senza
ricalcolarli:
  - 200 byte di indice: 25 long big-endian, uno per sotto-tile di 1x1 grado (indice lon%5*5 + lat%5),
    16 bit alti = versione del lookup, 48 bassi = fine del blocco della sotto-tile;
  - i 25 blocchi uno dopo l'altro (lunghezza zero = sotto-tile vuota). Ogni blocco inizia con un
    indice di divisor x divisor int (fine di ogni micro-cella, relativa all'inizio del blocco; la
    micro-cella subIdx copre la riga lat subIdx // divisor e la colonna lon subIdx % divisor) seguito
    dalle micro-celle, ognuna con il proprio CRC in coda (quindi spostabile senza ricalcolo);
  - in coda: creationTime (long), CRC dell'indice (int; xor 2 se divisor 32), 25 CRC degli indici
    dei blocchi (int), tipo di elevazione (byte) ed eventuali estensioni.
Qui si tengono solo le micro-celle che toccano il riquadro della regione allargato di --margin
gradi (le strade di confine restano percorribili per qualche chilometro), si svuotano le altre e
si riscrivono indici e CRC. I dati delle micro-celle tenute restano identici byte per byte.

Uso: python clip_rd5.py <in.rd5> <out.rd5> --bbox minLon,minLat,maxLon,maxLat [--margin 0.1]
"""
import argparse
import re
import struct
import sys
import zlib
from pathlib import Path


def brouter_crc(data):
    """btools.util.Crc32: CRC-32 standard senza lo xor finale."""
    return (zlib.crc32(data) ^ 0xFFFFFFFF) & 0xFFFFFFFF


def tile_origin(name):
    """'E5_N45.rd5' -> (5, 45); 'W10_S20.rd5' -> (-10, -20)."""
    m = re.fullmatch(r"([EW])(\d+)_([NS])(\d+)\.rd5", name)
    if not m:
        raise ValueError(f"nome di tile inatteso: {name}")
    lon = int(m.group(2)) * (1 if m.group(1) == "E" else -1)
    lat = int(m.group(4)) * (1 if m.group(3) == "N" else -1)
    return lon, lat


def overlaps(a_min_lon, a_min_lat, a_max_lon, a_max_lat, b):
    return a_min_lon < b[2] and a_max_lon > b[0] and a_min_lat < b[3] and a_max_lat > b[1]


def clip(data, name, bbox):
    """(nuovo contenuto, micro-celle tenute, micro-celle con dati) del .rd5 ritagliato su bbox."""
    index = struct.unpack(">25q", data[:200])
    versions = [v >> 48 for v in index]
    ends = [v & 0xFFFFFFFFFFFF for v in index]
    extra = data[ends[24]:]
    creation_time, crc_index = struct.unpack(">qI", extra[:12])
    tail = extra[112:]  # tipo di elevazione ed eventuali estensioni: copiati cosi' come sono
    if crc_index == brouter_crc(data[:200]):
        divisor = 80
    elif crc_index ^ 2 == brouter_crc(data[:200]):
        divisor = 32
    else:
        raise ValueError(f"{name}: CRC dell'indice non valido")

    lon0, lat0 = tile_origin(name)
    cell = 1.0 / divisor
    index_size = divisor * divisor * 4
    blocks, header_crcs, kept, total = [], [], 0, 0
    for i in range(25):
        start = ends[i - 1] if i else 200
        block = data[start:ends[i]]
        lon_deg, lat_deg = lon0 + i // 5, lat0 + i % 5
        if not block or not overlaps(lon_deg, lat_deg, lon_deg + 1, lat_deg + 1, bbox):
            if block:
                total += count_cells(block, divisor)
            blocks.append(b""); header_crcs.append(0)
            continue
        positions = struct.unpack(f">{divisor * divisor}i", block[:index_size])
        new_positions, payload, prev = [], bytearray(), index_size
        for sub, end in enumerate(positions):
            size = end - prev
            if size > 0:
                total += 1
                lon_min = lon_deg + (sub % divisor) * cell
                lat_min = lat_deg + (sub // divisor) * cell
                if overlaps(lon_min, lat_min, lon_min + cell, lat_min + cell, bbox):
                    payload += block[prev:end]
                    kept += 1
            prev = end
            new_positions.append(index_size + len(payload))
        if not payload:
            blocks.append(b""); header_crcs.append(0)
            continue
        new_index = struct.pack(f">{divisor * divisor}i", *new_positions)
        blocks.append(new_index + bytes(payload))
        header_crcs.append(brouter_crc(new_index))

    out_index, pos = [], 200
    for version, block in zip(versions, blocks):
        pos += len(block)
        out_index.append((version << 48) | pos)
    head = struct.pack(">25q", *out_index)
    crc = brouter_crc(head) ^ (2 if divisor == 32 else 0)
    footer = struct.pack(">qI", creation_time, crc) + struct.pack(">25I", *header_crcs) + tail
    return head + b"".join(blocks) + footer, kept, total


def count_cells(block, divisor):
    positions = struct.unpack(f">{divisor * divisor}i", block[:divisor * divisor * 4])
    prev, n = divisor * divisor * 4, 0
    for end in positions:
        n += end > prev
        prev = end
    return n


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", type=Path)
    ap.add_argument("dst", type=Path)
    ap.add_argument("--bbox", required=True, help="minLon,minLat,maxLon,maxLat della regione")
    ap.add_argument("--margin", type=float, default=0.1, help="gradi aggiunti al riquadro su ogni lato (0,1 = ~11 km)")
    a = ap.parse_args()
    min_lon, min_lat, max_lon, max_lat = map(float, a.bbox.split(","))
    bbox = (min_lon - a.margin, min_lat - a.margin, max_lon + a.margin, max_lat + a.margin)
    data = a.src.read_bytes()
    out, kept, total = clip(data, a.src.name, bbox)
    a.dst.write_bytes(out)
    print(f"{a.src.name}: {len(data) / 2**20:.1f} -> {len(out) / 2**20:.1f} MiB "
          f"({kept}/{total} micro-celle con dati tenute)", file=sys.stderr)


if __name__ == "__main__":
    main()
