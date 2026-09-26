#!/usr/bin/env python3
"""Ritaglia un segmento BRouter (.rd5, tile 5x5 gradi) sul riquadro di una regione.

I .rd5 di brouter.de coprono 5x5 gradi: il Lussemburgo ha due tile da 435 MB di cui usa l'1%.
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
Si leggono solo le parti necessarie (intestazione, coda, indici delle sotto-tile nel riquadro,
micro-celle tenute): con un URL di brouter.de si scaricano pochi MB invece dell'intera tile.

Uso: python clip_rd5.py <in.rd5 | URL> <out.rd5> --bbox minLon,minLat,maxLon,maxLat --margin 0.1
Stampa su stdout la dimensione del file originale.
"""
import argparse
import http.client
import re
import struct
import sys
import time
import zlib
from pathlib import Path
from urllib.parse import urlsplit


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


def overlaps(lon, lat, size, bbox):
    """La cella di lato size con angolo in basso a sinistra (lon, lat) tocca bbox?"""
    return lon < bbox[2] and lon + size > bbox[0] and lat < bbox[3] and lat + size > bbox[1]


class FileSource:
    """Legge intervalli di byte da un .rd5 locale."""

    def __init__(self, path):
        self.name, self.file = Path(path).name, open(path, "rb")
        self.size, self.fetched = Path(path).stat().st_size, 0

    def read(self, start, end=None):
        self.file.seek(start)
        chunk = self.file.read(-1 if end is None else end - start)
        self.fetched += len(chunk)
        return chunk


class HttpSource:
    """Legge intervalli di byte da un .rd5 remoto con richieste HTTP Range su una sola connessione
    (brouter.de le supporta): si scaricano solo le parti che finiscono nel file ritagliato."""

    def __init__(self, url, user_agent):
        u = urlsplit(url)
        self.name, self.url, self.user_agent = Path(u.path).name, u, user_agent
        self.conn, self.size, self.fetched = None, None, 0

    def read(self, start, end=None):
        wanted = f"bytes={start}-{'' if end is None else end - 1}"
        for attempt in range(5):
            try:
                if self.conn is None:
                    conn_class = http.client.HTTPSConnection if self.url.scheme == "https" else http.client.HTTPConnection
                    self.conn = conn_class(self.url.netloc, timeout=60)
                self.conn.request("GET", self.url.path, headers={"Range": wanted, "User-Agent": self.user_agent})
                resp = self.conn.getresponse()
                body = resp.read()
            except (OSError, http.client.HTTPException) as e:
                error = e
            else:
                if resp.status == 206:
                    self.size = int(resp.getheader("Content-Range").rsplit("/", 1)[1])
                    self.fetched += len(body)
                    return body
                error = OSError(f"{self.name}: HTTP {resp.status} per {wanted}")
                if resp.status < 500:  # 200 = Range ignorato (scaricherebbe tutto), 404, ...: inutile riprovare
                    raise error
            self.conn.close()
            self.conn = None
            time.sleep(2 ** attempt)
        raise error


def clip(src, bbox):
    """(nuovo contenuto, micro-celle tenute) del .rd5 letto da src, ritagliato su bbox."""
    head = src.read(0, 200)
    index = struct.unpack(">25q", head)
    versions = [v >> 48 for v in index]
    ends = [v & 0xFFFFFFFFFFFF for v in index]
    extra = src.read(ends[24])
    creation_time, crc_index = struct.unpack(">qI", extra[:12])
    tail = extra[112:]  # tipo di elevazione ed eventuali estensioni: copiati cosi' come sono
    crc = brouter_crc(head)
    if crc_index == crc:
        divisor = 80
    elif crc_index ^ 2 == crc:
        divisor = 32
    else:
        raise ValueError(f"{src.name}: CRC dell'indice non valido")

    lon0, lat0 = tile_origin(src.name)
    cell = 1.0 / divisor
    index_size = divisor * divisor * 4
    blocks, header_crcs, kept = [], [], 0
    for i in range(25):
        start = ends[i - 1] if i else 200
        lon_deg, lat_deg = lon0 + i // 5, lat0 + i % 5
        # Sotto-tile vuota o fuori dal riquadro: non si legge nemmeno il suo indice.
        if ends[i] == start or not overlaps(lon_deg, lat_deg, 1, bbox):
            blocks.append(b""); header_crcs.append(0)
            continue
        positions = struct.unpack(f">{divisor * divisor}i", src.read(start, start + index_size))
        # Micro-celle tenute contigue (quelle di una riga del riquadro): un solo intervallo da leggere.
        new_positions, runs, size, prev = [], [], 0, index_size
        for sub, end in enumerate(positions):
            if end > prev and overlaps(lon_deg + (sub % divisor) * cell, lat_deg + (sub // divisor) * cell, cell, bbox):
                if runs and runs[-1][1] == start + prev:
                    runs[-1][1] = start + end
                else:
                    runs.append([start + prev, start + end])
                size += end - prev
                kept += 1
            prev = end
            new_positions.append(index_size + size)
        if not runs:
            blocks.append(b""); header_crcs.append(0)
            continue
        new_index = struct.pack(f">{divisor * divisor}i", *new_positions)
        blocks.append(new_index + b"".join(src.read(a, b) for a, b in runs))
        header_crcs.append(brouter_crc(new_index))

    out_index, pos = [], 200
    for version, block in zip(versions, blocks):
        pos += len(block)
        out_index.append((version << 48) | pos)
    head = struct.pack(">25q", *out_index)
    crc = brouter_crc(head) ^ (2 if divisor == 32 else 0)
    footer = struct.pack(">qI", creation_time, crc) + struct.pack(">25I", *header_crcs) + tail
    return head + b"".join(blocks) + footer, kept


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", help="file .rd5 locale o URL (http/https, letto con richieste Range)")
    ap.add_argument("dst", type=Path)
    ap.add_argument("--bbox", required=True, help="minLon,minLat,maxLon,maxLat della regione")
    ap.add_argument("--margin", type=float, required=True, help="gradi aggiunti al riquadro su ogni lato (0,1 = ~11 km)")
    ap.add_argument("--user-agent", default="PocketTravelDataPipeline clip_rd5.py", help="per le richieste HTTP")
    a = ap.parse_args()
    min_lon, min_lat, max_lon, max_lat = map(float, a.bbox.split(","))
    bbox = (min_lon - a.margin, min_lat - a.margin, max_lon + a.margin, max_lat + a.margin)
    remote = a.src.startswith(("http://", "https://"))
    src = HttpSource(a.src, a.user_agent) if remote else FileSource(a.src)
    out, kept = clip(src, bbox)
    a.dst.write_bytes(out)
    print(f"{src.name}: {src.size / 2**20:.1f} -> {len(out) / 2**20:.1f} MiB, {kept} micro-celle tenute, "
          f"{src.fetched / 2**20:.1f} MiB {'scaricati' if remote else 'letti'}", file=sys.stderr)
    print(src.size)  # dimensione dell'originale, per il sourceKey del manifest (build-region.sh)


if __name__ == "__main__":
    main()
