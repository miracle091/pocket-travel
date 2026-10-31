#!/usr/bin/env python3
"""Set di domande a piu' passaggi con risposta verificabile, dagli orari di una rete GTFS: per misurare il ragionamento
dei modelli (thinking acceso o spento) senza un modello come giudice.

Ogni domanda ha nel contesto un estratto degli orari di un giorno, come il tabellone delle partenze dell'app, e una
risposta calcolata dagli stessi orari. Cinque tipi, in parti uguali, meta' in italiano e meta' in inglese:
- next: il prossimo passaggio di una linea a una fermata dopo un'ora data (orario);
- duration: i minuti tra due fermate di una corsa (numero);
- transfer: la coincidenza con un'altra linea almeno 3 minuti dopo l'arrivo (orario);
- deadline: con la prima corsa dopo un'ora data si arriva entro un'altra ora? (sì/no);
- count: quante corse di una linea partono da una fermata tra due ore, comprese (numero).
La domanda chiede di chiudere con "Risposta:" (o "Answer:") e il solo risultato: score_answer legge quello.

Le corse a frequenza (frequencies.txt) restano fuori: hanno orari indicativi.

Esempio: python generate_reasoning_set.py --gtfs tld-958.zip --count 100 --out reasoning.jsonl
"""
import argparse
import csv
import datetime
import io
import json
import random
import re
import zipfile
from collections import defaultdict

KINDS = ("next", "duration", "transfer", "deadline", "count")
MIN_TRANSFER = 3  # minuti
BOARD_BEFORE = 30 * 60  # secondi di tabellone prima dell'ora della domanda
BOARD_AFTER = 90 * 60  # e dopo
MAX_STOPS_SHOWN = 12

TEXT = {
    "it": {
        "board": "Fermata {stop}, partenze del {date}:",
        "line": "- Linea {route} per {headsign}: {times}",
        "trip": "Corsa della linea {route} per {headsign} delle {start}:",
        "trip_stop": "- {stop} {time}",
        "trips_between": "Linea {route}, partenze da {a} e arrivi a {b} del {date}:",
        "trip_pair": "- parte alle {dep}, arriva alle {arr}",
        "next": "Sono alla fermata {stop} alle {t}. A che ora passa la prossima corsa della linea {route}?",
        "duration": "Prendo la corsa delle {start} della linea {route} da {a}. Quanti minuti ci vogliono per arrivare a {b}?",
        "transfer": ("Prendo la corsa delle {start} della linea {route} da {a} e scendo a {b}. Lì prendo la prima corsa "
                     "della linea {route2} che parte almeno {gap} minuti dopo il mio arrivo. A che ora parto da {b}?"),
        "deadline": ("Arrivo alla fermata {a} alle {t} e prendo la prima corsa della linea {route} che parte da li'. "
                     "Arrivo a {b} entro le {d}?"),
        "count": "Quante corse della linea {route} partono da {stop} tra le {t1} e le {t2}, comprese?",
        "format": {"time": "Alla fine scrivi \"Risposta:\" seguito dal solo orario (HH:MM).",
                   "number": "Alla fine scrivi \"Risposta:\" seguito dal solo numero.",
                   "yesno": "Alla fine scrivi \"Risposta:\" seguito da sì o no."},
        "yes": "sì", "no": "no",
    },
    "en": {
        "board": "Stop {stop}, departures on {date}:",
        "line": "- Line {route} to {headsign}: {times}",
        "trip": "Line {route} trip to {headsign} at {start}:",
        "trip_stop": "- {stop} {time}",
        "trips_between": "Line {route}, departures from {a} and arrivals at {b} on {date}:",
        "trip_pair": "- leaves at {dep}, arrives at {arr}",
        "next": "I am at the {stop} stop at {t}. What time does the next line {route} bus come?",
        "duration": "I take the {start} line {route} bus from {a}. How many minutes does it take to reach {b}?",
        "transfer": ("I take the {start} line {route} bus from {a} and get off at {b}. There I take the first line "
                     "{route2} bus leaving at least {gap} minutes after I arrive. What time do I leave {b}?"),
        "deadline": ("I get to the {a} stop at {t} and take the first line {route} bus leaving from there. "
                     "Do I reach {b} by {d}?"),
        "count": "How many line {route} buses leave {stop} between {t1} and {t2}, inclusive?",
        "format": {"time": "At the end write \"Answer:\" followed only by the time (HH:MM).",
                   "number": "At the end write \"Answer:\" followed only by the number.",
                   "yesno": "At the end write \"Answer:\" followed only by yes or no."},
        "yes": "yes", "no": "no",
    },
}
ANSWER_TYPE = {"next": "time", "duration": "number", "transfer": "time", "deadline": "yesno", "count": "number"}


def hhmm(seconds):
    return f"{seconds // 3600:02d}:{seconds % 3600 // 60:02d}"


def seconds(gtfs_time):
    h, m, s = (int(x) for x in gtfs_time.split(":"))
    return h * 3600 + m * 60 + s


def read_csv(zf, name):
    if name not in zf.namelist():
        return []
    with zf.open(name) as f:
        return list(csv.DictReader(io.TextIOWrapper(f, "utf-8-sig")))


def active_services(calendar, calendar_dates, date):
    """I service_id attivi il giorno date (datetime.date): calendar.txt per giorno della settimana e intervallo, poi le
    eccezioni di calendar_dates.txt (1 aggiunge, 2 toglie)."""
    day = date.strftime("%Y%m%d")
    weekday = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")[date.weekday()]
    active = {c["service_id"] for c in calendar if c["start_date"] <= day <= c["end_date"] and c[weekday] == "1"}
    for c in calendar_dates:
        if c["date"] == day:
            (active.add if c["exception_type"] == "1" else active.discard)(c["service_id"])
    return active


def busiest_weekday(calendar, calendar_dates):
    """Il giorno feriale (lun-ven) con piu' servizi attivi tra le date del feed: un giorno con l'orario pieno."""
    days = {c["date"] for c in calendar_dates}
    for c in calendar:
        start = datetime.datetime.strptime(c["start_date"], "%Y%m%d").date()
        end = datetime.datetime.strptime(c["end_date"], "%Y%m%d").date()
        days.update((start + datetime.timedelta(n)).strftime("%Y%m%d") for n in range(min((end - start).days + 1, 60)))
    dates = sorted(datetime.datetime.strptime(d, "%Y%m%d").date() for d in days)
    weekdays = [d for d in dates if d.weekday() < 5] or dates
    return max(weekdays, key=lambda d: (len(active_services(calendar, calendar_dates, d)), -d.toordinal()))


class Timetable:
    """Le corse di un giorno: per corsa le fermate con l'orario di partenza, per (fermata, linea) le partenze."""

    def __init__(self, zf, date):
        self.date = date
        calendar, calendar_dates = read_csv(zf, "calendar.txt"), read_csv(zf, "calendar_dates.txt")
        services = active_services(calendar, calendar_dates, date)
        frequent = {f["trip_id"] for f in read_csv(zf, "frequencies.txt")}
        self.stop_name = {s["stop_id"]: s["stop_name"].strip() for s in read_csv(zf, "stops.txt")}
        self.route_name = {r["route_id"]: (r["route_short_name"] or r["route_long_name"]).strip()
                           for r in read_csv(zf, "routes.txt")}
        self.trip = {t["trip_id"]: (t["route_id"], (t.get("trip_headsign") or "").strip())
                     for t in read_csv(zf, "trips.txt") if t["service_id"] in services and t["trip_id"] not in frequent}
        stops = defaultdict(list)
        with zf.open("stop_times.txt") as f:
            for st in csv.DictReader(io.TextIOWrapper(f, "utf-8-sig")):
                if st["trip_id"] in self.trip and st["departure_time"]:
                    stops[st["trip_id"]].append((int(st["stop_sequence"]), st["stop_id"], seconds(st["departure_time"])))
        self.stops = {t: [(s, d) for _, s, d in sorted(v)] for t, v in stops.items() if len(v) >= 2}
        self.departures = defaultdict(list)  # (fermata, linea) -> [(partenza, corsa)]
        for t, seq in self.stops.items():
            for s, d in seq[:-1]:
                self.departures[(s, self.trip[t][0])].append((d, t))
        for v in self.departures.values():
            v.sort()

    def name(self, stop):
        return self.stop_name.get(stop, stop)

    def headsign(self, trip):
        return self.trip[trip][1] or self.name(self.stops[trip][-1][0])

    def time_at(self, trip, stop):
        return next(d for s, d in self.stops[trip] if s == stop)


def board(tt, lang, stop, routes, t):
    """Il tabellone di stop per le linee routes nella finestra intorno a t."""
    tx = TEXT[lang]
    lines = [tx["board"].format(stop=tt.name(stop), date=tt.date.isoformat())]
    for route in routes:
        times = [d for d, _ in tt.departures[(stop, route)] if t - BOARD_BEFORE <= d <= t + BOARD_AFTER]
        if times:
            trip = next(tr for d, tr in tt.departures[(stop, route)] if d == times[0])
            lines.append(tx["line"].format(route=tt.route_name[route], headsign=tt.headsign(trip),
                                           times=", ".join(hhmm(d) for d in times)))
    return "\n".join(lines)


def trip_block(tt, lang, trip, a, b):
    """Le fermate della corsa da a a b con gli orari (al massimo MAX_STOPS_SHOWN, sempre a e b)."""
    tx = TEXT[lang]
    seq = tt.stops[trip]
    i = next(k for k, (s, _) in enumerate(seq) if s == a)
    j = next(k for k, (s, _) in enumerate(seq) if s == b)
    shown = seq[i:j + 1]
    if len(shown) > MAX_STOPS_SHOWN:
        step = (len(shown) - 1) / (MAX_STOPS_SHOWN - 1)
        shown = [shown[round(k * step)] for k in range(MAX_STOPS_SHOWN)]
    head = tx["trip"].format(route=tt.route_name[tt.trip[trip][0]], headsign=tt.headsign(trip), start=hhmm(seq[i][1]))
    return "\n".join([head] + [tx["trip_stop"].format(stop=tt.name(s), time=hhmm(d)) for s, d in shown])


def odd_minute(rng, after, before, taken):
    """Un'ora (secondi, minuto intero) tra after e before che non coincide con nessuna partenza di taken."""
    for _ in range(50):
        t = rng.randrange(after // 60, before // 60) * 60
        if t not in taken:
            return t
    return None


def pick_leg(tt, rng):
    """Una corsa e due sue fermate a e b (b almeno 4 fermate dopo a), di giorno."""
    trip = rng.choice(sorted(tt.stops))
    seq = tt.stops[trip]
    if len(seq) < 6 or not 6 * 3600 <= seq[0][1] <= 20 * 3600:
        return None
    i = rng.randrange(0, len(seq) - 5)
    j = rng.randrange(i + 4, len(seq))
    a, b = seq[i][0], seq[j][0]
    if a == b or tt.name(a) == tt.name(b):
        return None
    return trip, a, b


def make(tt, rng, kind, lang):
    """Una riga del set di tipo kind, o None se la corsa estratta non va bene (si riprova)."""
    tx = TEXT[lang]
    leg = pick_leg(tt, rng)
    if leg is None:
        return None
    trip, a, b = leg
    route = tt.trip[trip][0]
    dep_a = tt.time_at(trip, a)
    fill = {"route": tt.route_name[route], "a": tt.name(a), "b": tt.name(b), "start": hhmm(dep_a), "stop": tt.name(a)}
    deps = tt.departures[(a, route)]
    if kind == "next":
        t = odd_minute(rng, dep_a - 20 * 60, dep_a, {d for d, _ in deps})
        if t is None:
            return None
        answer = hhmm(next(d for d, _ in deps if d > t))
        routes = sorted({r for (s, r) in tt.departures if s == a})[:6]
        context = board(tt, lang, a, routes if route in routes else routes[:5] + [route], t)
        question = tx["next"].format(**fill, t=hhmm(t))
    elif kind == "duration":
        answer = str((tt.time_at(trip, b) - dep_a) // 60)
        context = trip_block(tt, lang, trip, a, b)
        question = tx["duration"].format(**fill)
    elif kind == "transfer":
        arrive = tt.time_at(trip, b)
        others = [r for (s, r) in tt.departures if s == b and r != route]
        if not others:
            return None
        route2 = rng.choice(sorted(others))
        later = [d for d, _ in tt.departures[(b, route2)] if d >= arrive + MIN_TRANSFER * 60]
        if not later or later[0] > arrive + BOARD_AFTER:
            return None
        answer = hhmm(later[0])
        context = trip_block(tt, lang, trip, a, b) + "\n\n" + board(tt, lang, b, [route2], arrive)
        question = tx["transfer"].format(**fill, route2=tt.route_name[route2], gap=MIN_TRANSFER)
    elif kind == "deadline":
        pairs = [(d, tt.time_at(tr, b)) for d, tr in deps if b in {s for s, _ in tt.stops[tr]}
                 and dep_a - 60 * 60 <= d <= dep_a + 60 * 60 and tt.time_at(tr, b) > d]
        t = odd_minute(rng, dep_a - 15 * 60, dep_a, {d for d, _ in pairs})
        if t is None or len(pairs) < 3:
            return None
        arrive = next(arr for d, arr in pairs if d > t)
        yes = rng.random() < 0.5
        deadline = arrive + rng.randrange(0, 5) * 60 if yes else arrive - rng.randrange(1, 6) * 60
        answer = tx["yes"] if yes else tx["no"]
        lines = [tx["trips_between"].format(**fill, date=tt.date.isoformat())]
        lines += [tx["trip_pair"].format(dep=hhmm(d), arr=hhmm(arr)) for d, arr in pairs]
        context = "\n".join(lines)
        question = tx["deadline"].format(**fill, t=hhmm(t), d=hhmm(deadline))
    else:  # count
        window = [d for d, _ in deps if dep_a - BOARD_BEFORE <= d <= dep_a + BOARD_AFTER]
        if len(window) < 4:
            return None
        lo, hi = sorted(rng.sample(range(len(window)), 2))
        t1 = window[lo] - rng.randrange(0, 2) * 60
        t2 = window[hi] + rng.randrange(0, 2) * 60
        answer = str(sum(t1 <= d <= t2 for d in window))
        context = board(tt, lang, a, [route], dep_a)
        question = tx["count"].format(**fill, t1=hhmm(t1), t2=hhmm(t2))
    answer_type = ANSWER_TYPE[kind]
    return {"kind": kind, "lang": lang, "answer_type": answer_type, "context": context,
            "question": f"{question} {tx['format'][answer_type]}", "answer": answer}


def generate(tt, count, seed):
    """count righe, i tipi in parti uguali e le lingue alternate; stesso seed, stesso set."""
    rng = random.Random(seed)
    rows, seen = [], set()
    for n in range(count):
        kind, lang = KINDS[n % len(KINDS)], ("it", "en")[n // len(KINDS) % 2]
        for _ in range(500):
            row = make(tt, rng, kind, lang)
            if row and row["question"] not in seen:
                seen.add(row["question"])
                rows.append({"id": f"{kind}-{n}", **row})
                break
        else:
            raise SystemExit(f"nessuna domanda di tipo {kind} dopo 500 tentativi")
    return rows


FINAL = re.compile(r"(?:risposta|answer)\s*:\s*(.*)", re.I)
TIME = re.compile(r"\b(\d{1,2})[:.](\d{2})\b")
NUMBER = re.compile(r"\b\d+\b")
YES = re.compile(r"^\W*(s[iì]|yes)\b", re.I)
NO = re.compile(r"^\W*no\b", re.I)


def parse_answer(text, answer_type):
    """Il risultato dopo l'ultimo "Risposta:"/"Answer:" (dopo il blocco <think>, se c'e'), normalizzato: HH:MM, numero
    o yes/no. None se manca o non si legge."""
    text = text.split("</think>")[-1]
    finals = FINAL.findall(text)
    if not finals:
        return None
    final = finals[-1].strip()
    if answer_type == "time":
        m = TIME.search(final)
        return f"{int(m.group(1)):02d}:{m.group(2)}" if m else None
    if answer_type == "number":
        m = NUMBER.search(final)
        return str(int(m.group(0))) if m else None
    return "yes" if YES.search(final) else "no" if NO.search(final) else None


def score_answer(row, got):
    """True se got chiude con il risultato giusto per la riga row."""
    expected = row["answer"]
    if row["answer_type"] == "yesno":
        expected = "yes" if expected in ("sì", "yes") else "no"
    return parse_answer(got, row["answer_type"]) == expected


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gtfs", required=True, help="feed GTFS (zip)")
    ap.add_argument("--date", help="giorno degli orari, AAAA-MM-GG (default: il feriale con piu' servizi del feed)")
    ap.add_argument("--count", type=int, default=100)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    with zipfile.ZipFile(a.gtfs) as zf:
        date = (datetime.date.fromisoformat(a.date) if a.date
                else busiest_weekday(read_csv(zf, "calendar.txt"), read_csv(zf, "calendar_dates.txt")))
        tt = Timetable(zf, date)
    rows = generate(tt, a.count, a.seed)
    with open(a.out, "w", encoding="utf-8", newline="\n") as out:
        for row in rows:
            out.write(json.dumps({**row, "date": date.isoformat()}, ensure_ascii=False) + "\n")
    print(f"{len(rows)} domande del {date.isoformat()} ({len(tt.stops)} corse) in {a.out}")


if __name__ == "__main__":
    main()
