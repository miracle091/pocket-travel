#!/usr/bin/env python3
"""Regione dei feed GTFS che nel catalogo del Mobility Database hanno un rettangolo inutilizzabile.

Uso: transit_feeds_bbox.py [--user-agent UA] [--catalog feeds_v2.csv] [--country FR] [--out tabella.tsv]

Per i paesi divisi in regioni, la regione di una rete si sceglie dal centro del rettangolo del catalogo
(`location.bounding_box.*`). In Francia il rettangolo manca, ha uno zero al posto di una coordinata, ha il
minimo sopra il massimo o ha il centro fuori da tutte le regioni del paese (regions.sh, comprese quelle
d'oltremare): per questi feed GTFS statici `active` lo script legge solo stops.txt da `urls.latest` (o da
`urls.direct_download` quando manca), con richieste Range se il server le accetta, e assegna la regione il
cui rettangolo contiene piu' fermate (a parita', il rettangolo piu' piccolo). Non il centro delle fermate:
i rettangoli delle regioni francesi si sovrappongono, e con il centro l'Ardeche finiva in Provenza-Alpi-Costa
Azzurra e l'aggregato del Grand Est in Centro-Valle della Loira. Legge anche i giorni di validita' rimasti
(fine di feed_info.txt, o ultima data di calendar.txt e calendar_dates.txt).

Esce con una tabella TSV: id, nome, regione, licenza del catalogo, giorni rimasti, fermate nella regione
scelta su quelle lette (poche: rete su piu' regioni o internazionale, o coordinate sbagliate), motivo quando
la regione manca. Serve a scegliere a mano le reti da aggiungere a transit-feeds.tsv; non e' usato dalla
pipeline.
"""

import argparse
import csv
import datetime
import io
import re
import sys
import urllib.request
import zipfile
from collections import Counter
from pathlib import Path

CATALOG_URL = "https://files.mobilitydatabase.org/feeds_v2.csv"
USER_AGENT = "pocket-travel-transit-feeds-bbox/1.0 (https://github.com/miracle091/pocket-travel)"
REGIONS_SH = Path(__file__).resolve().parent / "regions.sh"
BBOX_KEYS = ("minimum_latitude", "maximum_latitude", "minimum_longitude", "maximum_longitude")
_ROW = re.compile(r'^\s*"([^"]+\|[^"]+)"\s*$', re.M)


def load_regions(text, country):
    """Regioni di un paese da regions.sh: (id, ovest, sud, est, nord). Prende quelle con il codice del paese
    e quelle d'oltremare, che hanno un codice proprio e il paese tra parentesi nel nome ("Riunione (Francia)")."""
    rows = [r.split("|") for r in _ROW.findall(text)]
    rows = [r for r in rows if len(r) >= 8]
    names = {r[7]: r[8] for r in rows if len(r) > 8 and r[8]}
    name = names.get(country.lower())
    return [(r[0], *map(float, r[2:6])) for r in rows
            if r[7] == country.lower() or (name and r[1].endswith(f"({name})"))]


def region_at(regions, lat, lon):
    """Regione il cui rettangolo contiene il punto; tra piu' rettangoli, il piu' piccolo."""
    hits = [r for r in regions if r[1] <= lon <= r[3] and r[2] <= lat <= r[4]]
    return min(hits, key=lambda r: (r[3] - r[1]) * (r[4] - r[2]))[0] if hits else None


def catalog_bbox(row):
    """(sud, nord, ovest, est) del catalogo, o None se manca, ha uno zero o ha il minimo sopra il massimo."""
    try:
        s, n, w, e = (float(row[f"location.bounding_box.{k}"]) for k in BBOX_KEYS)
    except ValueError:
        return None
    if 0.0 in (s, n, w, e) or s > n or w > e:
        return None
    return s, n, w, e


def unusable(row, regions):
    bbox = catalog_bbox(row)
    return bbox is None or region_at(regions, (bbox[0] + bbox[1]) / 2, (bbox[2] + bbox[3]) / 2) is None


class RangeFile(io.RawIOBase):
    """File remoto letto a pezzi con richieste Range: zipfile legge solo la directory e i file chiesti."""

    def __init__(self, url, size, headers):
        self.url, self.size, self.headers, self.pos = url, size, headers, 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.pos

    def seek(self, offset, whence=io.SEEK_SET):
        self.pos = {io.SEEK_SET: 0, io.SEEK_CUR: self.pos, io.SEEK_END: self.size}[whence] + offset
        return self.pos

    def read(self, n=-1):
        end = self.size if n is None or n < 0 else min(self.size, self.pos + n)
        if end <= self.pos:
            return b""
        req = urllib.request.Request(self.url, headers={**self.headers, "Range": f"bytes={self.pos}-{end - 1}"})
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
        self.pos += len(data)
        return data


def open_zip(url, user_agent):
    """Lo zip del feed: a pezzi se il server risponde 206 a una Range, altrimenti scaricato intero."""
    headers = {"User-Agent": user_agent}
    req = urllib.request.Request(url, headers={**headers, "Range": "bytes=0-0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        total = r.headers.get("Content-Range", "").rpartition("/")[2]
        if r.status == 206 and total.isdigit():
            return zipfile.ZipFile(RangeFile(r.geturl(), int(total), headers))
        data = r.read()
    return zipfile.ZipFile(io.BytesIO(data))


def read_csv(zf, name):
    """Righe di un file del feed (anche in una sottocartella dello zip), [] se manca."""
    member = next((m for m in zf.namelist() if m.rsplit("/", 1)[-1] == name), None)
    if member is None:
        return []
    with zf.open(member) as f:
        return list(csv.DictReader(io.TextIOWrapper(f, encoding="utf-8-sig", newline="")))


def stops_region(stops, regions):
    """(regione o None, fermate nel suo rettangolo, fermate lette). Le fermate fuori da tutte le regioni non
    contano: coordinate scambiate o a zero, o fermate all'estero delle reti internazionali."""
    counts = Counter()
    total = 0
    for s in stops:
        try:
            lat, lon = float(s.get("stop_lat") or ""), float(s.get("stop_lon") or "")
        except ValueError:
            continue
        total += 1
        counts.update(r for r in regions if r[1] <= lon <= r[3] and r[2] <= lat <= r[4])
    if not counts:
        return None, 0, total
    best = max(counts, key=lambda r: (counts[r], -(r[3] - r[1]) * (r[4] - r[2])))
    return best[0], counts[best], total


def last_day(zf):
    """Ultimo giorno di servizio: feed_end_date di feed_info.txt, se no l'ultima data di calendar.txt e delle
    aggiunte (exception_type 1) di calendar_dates.txt."""
    dates = [r.get("feed_end_date", "") for r in read_csv(zf, "feed_info.txt")]
    if not any(dates):
        dates = [r.get("end_date", "") for r in read_csv(zf, "calendar.txt")]
        dates += [r.get("date", "") for r in read_csv(zf, "calendar_dates.txt") if r.get("exception_type") == "1"]
    valid = [d.strip() for d in dates if re.fullmatch(r"\d{8}", (d or "").strip())]
    return datetime.datetime.strptime(max(valid), "%Y%m%d").date() if valid else None


def describe(row, regions, user_agent, today, opener=open_zip):
    """Riga della tabella per un feed: id, nome, regione, licenza, giorni rimasti, fermate tenute/lette, motivo."""
    out = [row["id"], row["name"] or row["provider"], "", row["urls.license"], "", "", ""]
    url = row["urls.latest"] or row["urls.direct_download"]
    if not url:
        out[6] = "nessun url nel catalogo"
        return out
    try:
        with opener(url, user_agent) as zf:
            region, inside, total = stops_region(read_csv(zf, "stops.txt"), regions)
            end = last_day(zf)
    except Exception as exc:  # noqa: BLE001 - un feed rotto non ferma la tabella, il motivo va nella riga
        out[6] = f"feed non letto: {type(exc).__name__}: {exc}"
        return out
    if end is not None:
        out[4] = str((end - today).days)
    out[5] = f"{inside}/{total}"
    if region is None:
        out[6] = "nessuna fermata dentro le regioni del paese"
    else:
        out[2] = region
    return out


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--user-agent", default=USER_AGENT)
    p.add_argument("--catalog", help=f"feeds_v2.csv gia' scaricato (se no {CATALOG_URL})")
    p.add_argument("--country", default="FR")
    p.add_argument("--out", help="file TSV (se no lo standard output)")
    args = p.parse_args(argv)

    if args.catalog:
        text = Path(args.catalog).read_text(encoding="utf-8")
    else:
        req = urllib.request.Request(CATALOG_URL, headers={"User-Agent": args.user_agent})
        with urllib.request.urlopen(req, timeout=120) as r:
            text = r.read().decode("utf-8")
    regions = load_regions(REGIONS_SH.read_text(encoding="utf-8"), args.country)
    if not regions:
        sys.exit(f"nessuna regione di {args.country} in regions.sh")
    feeds = [r for r in csv.DictReader(io.StringIO(text))
             if r["data_type"] == "gtfs" and r["status"] == "active"
             and r["location.country_code"] == args.country.upper() and unusable(r, regions)]
    today = datetime.date.today()
    rows = []
    for i, row in enumerate(feeds, 1):
        print(f"[{i}/{len(feeds)}] {row['id']}", file=sys.stderr)
        rows.append(describe(row, regions, args.user_agent, today))

    with (open(args.out, "w", encoding="utf-8", newline="") if args.out else
          io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", newline="", write_through=True)) as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["id", "nome", "regione", "licenza", "giorni", "fermate", "motivo"])
        w.writerows(rows)


if __name__ == "__main__":
    main()
