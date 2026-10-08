#!/usr/bin/env python3
"""Feed GTFS degli autobus IETT di Istanbul, ricomposto dai CSV sciolti del portale dati aperti dell'IBB.

Uso: iett_gtfs.py --out feed.zip [--user-agent UA]

Il dataset "iett-gtfs-verisi" di data.ibb.gov.tr (licenza aperta dell'IBB: uso anche commerciale e
modifiche, citando la fonte) pubblica i file GTFS come risorse CSV separate, non come zip, e con difetti
da foglio di calcolo che qui si correggono:
- separatore ";" in calendar, routes, stops, trips (stop_times, in uno zip a parte, usa ",");
- testi delle linee con la codifica doppia (UTF-8 letto come cp1252 e riscritto: "KADIKÃ–Y" per
  "KADIKÖY"), con alcuni byte di controllo rimasti come caratteri latin-1;
- coordinate con i punti delle migliaia ("410.191.700.005.564" per 41.0191700005564): Istanbul ha
  latitudine e longitudine con due cifre intere, la virgola va dopo le prime due; le poche in notazione
  esponenziale ("4,12E+14", precisione di un chilometro) si scartano, con i passaggi alle loro fermate;
- route_type vuoto: 3 (autobus);
- orari solo ai punti di controllo (timepoint=1, circa il 4% dei passaggi): gli altri si stimano
  in proporzione alla distanza in linea d'aria fra due punti di controllo, come chiede GTFS per
  timepoint=0 (orari approssimati), perche' generateTransit scarta i passaggi senza orario.

Esce con codice 1 se un file manca o non si scarica.
"""

import argparse
import csv
import io
import json
import math
import re
import sys
import urllib.request
import zipfile

PACKAGE_URL = "https://data.ibb.gov.tr/api/3/action/package_show?id=iett-gtfs-verisi"
CSV_FILES = ["agency", "calendar", "routes", "stops", "trips"]
LAT_RANGE = (40.0, 42.0)
LON_RANGE = (27.0, 30.5)
_PLAIN = re.compile(r"^\d+\.\d+$")
_THOUSANDS = re.compile(r"^\d+(\.\d+)+$")


def fix_mojibake(text):
    """'KADIKÃ–Y' -> 'KADIKÖY'; il testo gia' corretto resta com'e'."""
    if not any(ch in text for ch in "ÃÄÅ"):
        return text
    raw = bytearray()
    for ch in text:
        if ord(ch) < 256:
            raw.append(ord(ch))
        else:
            try:
                raw.extend(ch.encode("cp1252"))
            except UnicodeEncodeError:
                return text
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return text


def parse_coord(value, valid_range):
    """Coordinata in gradi, o None se illeggibile o fuori da valid_range."""
    token = re.match(r"^[\d.]+", value.strip())
    if not token:
        return None
    text = token.group(0).strip(".")
    candidates = []
    if _PLAIN.match(text):
        candidates.append(float(text))
    if _THOUSANDS.match(text) or text.isdigit():
        digits = text.replace(".", "")
        candidates.append(float(digits[:2] + "." + (digits[2:] or "0")))
    return next((n for n in candidates if valid_range[0] <= n <= valid_range[1]), None)


def read_rows(text):
    """Righe come dizionari, con il separatore (";" o ",") preso dall'intestazione."""
    text = text.lstrip("﻿")
    header = text.split("\n", 1)[0]
    delimiter = ";" if header.count(";") > header.count(",") else ","
    reader = csv.reader(io.StringIO(text), delimiter=delimiter)
    names = [n.strip() for n in next(reader)]
    for row in reader:
        if row:
            yield {name: (row[i].strip() if i < len(row) else "") for i, name in enumerate(names) if name}


def gtfs_seconds(value):
    if not value:
        return None
    h, m, s = (int(x) for x in value.split(":"))
    return h * 3600 + m * 60 + s


def gtfs_time(seconds):
    return "%02d:%02d:%02d" % (seconds // 3600, seconds % 3600 // 60, seconds % 60)


def distance_m(a, b):
    lat = math.radians((a[0] + b[0]) / 2)
    return math.hypot((a[0] - b[0]) * 111_195, (a[1] - b[1]) * 111_195 * math.cos(lat))


def interpolate_trip(calls, coords):
    """calls: [(seq, stop_id, secondi o None)] ordinati. Stima gli orari mancanti fra due noti in
    base alla distanza percorsa; quelli prima del primo o dopo l'ultimo orario noto restano None."""
    cumulative = [0.0]
    for prev, cur in zip(calls, calls[1:]):
        cumulative.append(cumulative[-1] + distance_m(coords[prev[1]], coords[cur[1]]))
    known = [i for i, call in enumerate(calls) if call[2] is not None]
    result = [call[2] for call in calls]
    for a, b in zip(known, known[1:]):
        span = cumulative[b] - cumulative[a]
        for i in range(a + 1, b):
            share = (cumulative[i] - cumulative[a]) / span if span > 0 else (i - a) / (b - a)
            result[i] = round(calls[a][2] + share * (calls[b][2] - calls[a][2]))
    return result


def convert_stop_times(lines, coords, writer):
    """Passaggi di stop_times.txt (ordinati per corsa) con gli orari stimati; scarta quelli verso
    fermate senza coordinate e quelli a cui non si puo' dare un orario. Ritorna (scritti, stimati)."""
    written = estimated = 0
    trip, calls = None, []

    def flush():
        nonlocal written, estimated
        calls.sort(key=lambda call: call[0])
        for call, seconds in zip(calls, interpolate_trip(calls, coords)):
            if seconds is None:
                continue
            if call[2] is None:
                estimated += 1
            writer.writerow([trip, call[1], call[0], gtfs_time(seconds), gtfs_time(seconds)])
            written += 1

    for row in read_rows_stream(lines):
        if row["trip_id"] != trip:
            if calls:
                flush()
            trip, calls = row["trip_id"], []
        if row["stop_id"] not in coords:
            continue
        seconds = gtfs_seconds(row.get("departure_time") or row.get("arrival_time"))
        calls.append((int(row["stop_sequence"]), row["stop_id"], seconds))
    if calls:
        flush()
    return written, estimated


def read_rows_stream(lines):
    reader = csv.reader(lines)
    names = [n.strip().lstrip("﻿") for n in next(reader)]
    for row in reader:
        if row:
            yield dict(zip(names, (value.strip() for value in row)))


def entry(name):
    """Voce dello zip con data fissa: stessi dati, stesso zip (e stesso sha256 per build-transit.sh)."""
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    return info


def write_csv(archive, name, header, rows):
    with archive.open(entry(name), "w") as raw, io.TextIOWrapper(raw, encoding="utf-8", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(header)
        writer.writerows(rows)


def fetch(url, user_agent):
    request = urllib.request.Request(url, headers={"User-Agent": user_agent})
    with urllib.request.urlopen(request, timeout=300) as response:
        return response.read()


def resource_urls(package_json):
    """URL delle risorse per nome: i CSV, e stop_times dallo zip (il CSV omonimo e' lo stesso file)."""
    urls = {}
    for resource in json.loads(package_json)["result"]["resources"]:
        name, fmt = resource.get("name", "").strip().lower(), resource.get("format", "").upper()
        if name == "stop_times" and fmt == "ZIP" or name in CSV_FILES and fmt == "CSV":
            urls[name] = resource["url"]
    missing = [n for n in CSV_FILES + ["stop_times"] if n not in urls]
    if missing:
        raise ValueError("risorse mancanti nel dataset IETT: " + ", ".join(missing))
    return urls


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--out", required=True)
    parser.add_argument("--user-agent", default="pocket-travel-iett-gtfs/1.0 (https://github.com/miracle091/pocket-travel)")
    args = parser.parse_args()

    try:
        urls = resource_urls(fetch(PACKAGE_URL, args.user_agent))
        texts = {name: fetch(urls[name], args.user_agent).decode("utf-8") for name in CSV_FILES}
        stop_times_zip = zipfile.ZipFile(io.BytesIO(fetch(urls["stop_times"], args.user_agent)))
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"iett_gtfs: {error}", file=sys.stderr)
        return 1

    coords, stops = {}, []
    for row in read_rows(texts["stops"]):
        lat, lon = parse_coord(row.get("stop_lat", ""), LAT_RANGE), parse_coord(row.get("stop_lon", ""), LON_RANGE)
        if lat is None or lon is None:
            continue
        coords[row["stop_id"]] = (lat, lon)
        stops.append([row["stop_id"], row.get("stop_code", ""), fix_mojibake(row.get("stop_name", "")), lat, lon])

    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as archive:
        agency = list(read_rows(texts["agency"]))
        write_csv(archive, "agency.txt", ["agency_id", "agency_name", "agency_url", "agency_timezone", "agency_lang"],
                  [[a["agency_id"], a["agency_name"], a["agency_url"], a["agency_timezone"], a.get("agency_lang", "")] for a in agency])
        days = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
        write_csv(archive, "calendar.txt", ["service_id"] + days + ["start_date", "end_date"],
                  [[c["service_id"]] + [c[d] for d in days] + [c["start_date"], c["end_date"]] for c in read_rows(texts["calendar"])])
        write_csv(archive, "routes.txt", ["route_id", "agency_id", "route_short_name", "route_long_name", "route_type"],
                  [[r["route_id"], r["agency_id"], fix_mojibake(r["route_short_name"]), fix_mojibake(r["route_long_name"]), r["route_type"] or "3"]
                   for r in read_rows(texts["routes"])])
        write_csv(archive, "stops.txt", ["stop_id", "stop_code", "stop_name", "stop_lat", "stop_lon"], stops)
        write_csv(archive, "trips.txt", ["trip_id", "route_id", "service_id", "trip_headsign", "direction_id"],
                  [[t["trip_id"], t["route_id"], t["service_id"], fix_mojibake(t.get("trip_headsign", "")), t.get("direction_id", "")]
                   for t in read_rows(texts["trips"])])
        source = stop_times_zip.namelist()[0]
        with stop_times_zip.open(source) as raw, archive.open(entry("stop_times.txt"), "w", force_zip64=True) as out_raw, \
                io.TextIOWrapper(raw, encoding="utf-8-sig", newline="") as lines, \
                io.TextIOWrapper(out_raw, encoding="utf-8", newline="") as out:
            writer = csv.writer(out)
            writer.writerow(["trip_id", "stop_id", "stop_sequence", "arrival_time", "departure_time"])
            written, estimated = convert_stop_times(lines, coords, writer)
    print(f"iett_gtfs: {len(stops)} fermate, {written} passaggi ({estimated} con orario stimato)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
