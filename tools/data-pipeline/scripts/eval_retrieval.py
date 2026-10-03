#!/usr/bin/env python3
"""Misura la ricerca dell'assistente sul dispositivo (RAG sulle guide) su dati pubblicati, senza modello e senza app.

Replica in Python TravelAssistant.kt (buildFtsQuery, namedCity, rankSections, focusStems, selectContext) e
FtsRanking.kt (BM25 su matchinfo 'pcxnal' con le statistiche delle due tabelle sommate): se cambiano li', va cambiato
anche qui. Per ogni regione scarica guides.db e il cities.db pubblicati (manifest), aggiunge Storia e Clima da
Wikipedia (city_wikipedia.py + generateCities, come build-cities.sh) se il cities.db pubblicato non li ha ancora,
e fa domande costruite da modelli fissi:
- citta', domande pratiche ("Dove dormire a {citta'}?"): attesa la sezione della categoria di quella citta';
- citta', storia e clima ("Com'e' il clima a {citta'}?"): attesa la sezione STORIA o CLIMA di quella citta';
- paese ("Serve il passaporto per entrare?"): attesa la sezione della categoria nella guida del paese.
Metriche: sezione attesa tra le 3 del contesto ("trovata") e al primo posto, Storia/Clima tra le 3 quando la domanda
non ne parla, e il paragrafo della sezione attesa con piu' parole della domanda presente nel contesto (questa
favorisce la scelta dei paragrafi dell'app, che usa lo stesso criterio). Con --lang en le guide inglesi (guidesEn,
citiesEn, Storia e Clima da Wikipedia EN) e le domande in inglese, come l'app in inglese.

Uso: python eval_retrieval.py [--lang it|en] [--regions italia portogallo ...] [--cache DIR] [--no-wikipedia]
(la cache dei file scaricati sta di default nella cartella temporanea di sistema)
Richiede rete (manifest, release, API di Wikipedia) e, per Storia e Clima, il gradle wrapper dalla root del repo.
"""
import argparse
import json
import lzma
import math
import random
import re
import sqlite3
import struct
import subprocess
import sys
import tempfile
import unicodedata
import urllib.request
from collections import defaultdict
from contextlib import closing
from pathlib import Path

import city_wikipedia

MANIFEST_URL = "https://miracle091.github.io/pocket-travel/manifest.json"
REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_REGIONS = ["italia", "portogallo", "grecia", "svizzera", "croazia"]
CITIES_PER_REGION = {"italia": 40}
DEFAULT_CITIES = 15

# --- costanti dell'app ---------------------------------------------------------------------------------------------
MAX_SECTIONS = 3                 # TravelAssistant.MAX_SECTIONS
CANDIDATE_CAP = 30               # GuideRepository/CityRepository.CANDIDATE_CAP
CITY_CANDIDATE_CAP = 100         # CityRepository.CITY_CANDIDATE_CAP
MAX_CONTEXT = 2000               # buildOnDeviceContext(maxChars)
MIN_SECTION_CHARS = 50           # TravelAssistant.MIN_SECTION_CHARS
MIN_CITY_NAME_CHARS = 3          # TravelAssistant.MIN_CITY_NAME_CHARS
STEM_CHARS = 5                   # TravelAssistant.STEM_CHARS
TITLE_WEIGHT, BODY_WEIGHT = 3.0, 1.0
BM25_K1, BM25_B, BM25_IDF_SMOOTHING = 1.2, 0.75, 0.5
WIKI = {"STORIA", "CLIMA"}
WIKIPEDIA_OFF_TOPIC_WEIGHT = 0.1  # TravelAssistant.WIKIPEDIA_OFF_TOPIC_WEIGHT
# TravelAssistant.historyClimateWords
HISTORY_CLIMATE_WORDS = re.compile(
    r"\b(stori|fondat|fondò|secol|guerr|antic|roman[oaie]?\b|mediev|clima|temperatur|piov|piogg|neve|nevic|cald|fredd|estat|"
    r"invern|meteo|stagion|histor|found|centur|wars?\b|ancient|medieval|weather|rain|snow|hot\b|cold\b|summer|winter|season)",
    re.IGNORECASE)

CITY_Q = {
    "COSA_VEDERE": ["Cosa vedere a {c}?", "Quali monumenti visitare a {c}?", "Quali musei ci sono a {c}?"],
    "TRASPORTI": ["Come arrivare a {c} in treno?", "Come muoversi a {c} con l'autobus?"],
    "ALLOGGIO": ["Dove dormire a {c}?", "Quali alberghi ci sono a {c}?"],
    "CIBO_BEVANDE": ["Dove mangiare a {c}?", "Quali ristoranti consigli a {c}?"],
    "ACQUISTI": ["Dove fare acquisti a {c}?"],
}
WIKI_Q = {
    "STORIA": ["Qual è la storia di {c}?", "Chi ha fondato {c}?", "Cosa è successo a {c} durante la seconda guerra mondiale?",
               "{c} era una città romana?"],
    "CLIMA": ["Com'è il clima a {c}?", "Fa caldo a {c} d'estate?", "Quanto piove a {c}?", "Nevica a {c} in inverno?"],
}
COUNTRY_Q = {
    "DOGANE": ["Serve il passaporto per entrare?", "Cosa si può portare in dogana?"],
    "SALUTE": ["Quali vaccinazioni servono?", "Com'è l'assistenza sanitaria per i turisti?"],
    "SICUREZZA": ["È pericoloso girare di notte?", "Ci sono borseggiatori?"],
    "ACQUISTI": ["Si può pagare con la carta di credito?", "Quanto si lascia di mancia?"],
    "CONNETTIVITA": ["Come comprare una SIM per internet?"],
    "TRASPORTI": ["Come funzionano i treni nel paese?", "Conviene noleggiare un'auto?"],
    "CIBO_BEVANDE": ["Quali piatti tipici assaggiare?"],
}
CITY_Q_EN = {
    "COSA_VEDERE": ["What to see in {c}?", "Which monuments should I visit in {c}?", "What museums are there in {c}?"],
    "TRASPORTI": ["How do I get to {c} by train?", "How do I get around {c} by bus?"],
    "ALLOGGIO": ["Where to stay in {c}?", "Which hotels are there in {c}?"],
    "CIBO_BEVANDE": ["Where to eat in {c}?", "Which restaurants do you recommend in {c}?"],
    "ACQUISTI": ["Where to go shopping in {c}?"],
}
WIKI_Q_EN = {
    "STORIA": ["What is the history of {c}?", "Who founded {c}?", "What happened in {c} during the Second World War?",
               "Was {c} a Roman town?"],
    "CLIMA": ["What is the climate like in {c}?", "Is it hot in {c} in summer?", "How much does it rain in {c}?",
              "Does it snow in {c} in winter?"],
}
COUNTRY_Q_EN = {
    "DOGANE": ["Do I need a passport to enter?", "What can I bring through customs?"],
    "SALUTE": ["Which vaccinations do I need?", "What is healthcare like for tourists?"],
    "SICUREZZA": ["Is it dangerous to walk around at night?", "Are there pickpockets?"],
    "ACQUISTI": ["Can I pay by credit card?", "How much should I tip?"],
    "CONNETTIVITA": ["How do I buy a SIM card for mobile internet?"],
    "TRASPORTI": ["How do trains work in the country?", "Is it worth renting a car?"],
    "CIBO_BEVANDE": ["Which typical dishes should I try?"],
}
# Per lingua: domande (citta', storia e clima, paese), chiavi del manifest e suffisso dei file in cache.
LANGS = {
    "it": {"questions": (CITY_Q, WIKI_Q, COUNTRY_Q), "guides": "guides", "cities": "cities", "suffix": ""},
    "en": {"questions": (CITY_Q_EN, WIKI_Q_EN, COUNTRY_Q_EN), "guides": "guidesEn", "cities": "citiesEn", "suffix": "-en"},
}
KINDS = ["citta', domande pratiche", "paese", "citta', storia e clima"]


# --- replica dell'app ------------------------------------------------------------------------------------------------
def fts_query(question, region_id):
    """buildFtsQuery: parole (divise su tutto cio' che non e' lettera o cifra, come l'indice) di almeno 4 caratteri in
    minuscolo, senza quelle del nome della regione, in OR."""
    region_tokens = {t.lower() for t in re.split(r"[^\w]+", region_id) if t}
    tokens = [t.lower() for t in re.split(r"[^\w]+|_", question) if t]
    return " OR ".join(t for t in tokens if len(t) >= 4 and t not in region_tokens)


def spoken_city_name(city):
    """spokenCityName: senza il disambiguatore di Wikivoyage ("Porto (Portogallo)" -> "Porto")."""
    return city.split(" (")[0].strip()


def folded(text):
    """folded: minuscolo e senza accenti."""
    return "".join(ch for ch in unicodedata.normalize("NFD", text.lower()) if unicodedata.category(ch) != "Mn")


def normalized_words(text):
    """normalizedWords: minuscolo, senza accenti, parole separate da un solo spazio anche agli estremi."""
    return " " + " ".join(w for w in re.split(r"[^\w]+|_", folded(text)) if w) + " "


def named_city(question, cities):
    """namedCity: la citta' nominata nella domanda (il nome piu' lungo, a parole intere), o None."""
    words = normalized_words(question)
    found = [c for c in cities
             if len(spoken_city_name(c)) >= MIN_CITY_NAME_CHARS and normalized_words(spoken_city_name(c)) in words]
    return max(found, key=lambda c: len(spoken_city_name(c)), default=None)


def focus_stems(fts, city):
    """focusStems: prime 5 lettere delle parole della query, senza quelle della citta' nominata."""
    city_stems = {w[:STEM_CHARS] for w in normalized_words(spoken_city_name(city)).split() if len(w) >= 4} if city else set()
    return {folded(t)[:STEM_CHARS] for t in fts.split(" OR ") if t} - city_stems


def parse_matchinfo(blob):
    """FtsMatchInfo.parse: matchinfo(..., 'pcxnal')."""
    ints = struct.unpack(f"<{len(blob) // 4}I", blob)
    p, c = ints[0], ints[1]
    tail = 2 + p * c * 3
    return {
        "p": p, "c": c,
        "hits": [[ints[2 + (ph * c + col) * 3] for col in range(c)] for ph in range(p)],
        "docs": [[ints[2 + (ph * c + col) * 3 + 2] for col in range(c)] for ph in range(p)],
        "n": ints[tail], "avg": list(ints[tail + 1:tail + 1 + c]), "len": list(ints[tail + 1 + c:tail + 1 + 2 * c]),
    }


def corpus_stats(tables):
    """FtsCorpusStats.of: righe, lunghezza media e righe con ogni frase sommate su un matchinfo per tabella."""
    n = sum(t["n"] for t in tables)
    c = tables[0]["c"]
    return {
        "n": n,
        "avg": [sum(t["n"] * t["avg"][col] for t in tables) / max(n, 1) for col in range(c)],
        "docs": [[sum(t["docs"][ph][col] for t in tables) for col in range(c)] for ph in range(tables[0]["p"])],
    }


def bm25(info, stats):
    """bm25Score."""
    score = 0.0
    for ph in range(info["p"]):
        for col in range(info["c"]):
            tf = info["hits"][ph][col]
            if not tf:
                continue
            docs = max(stats["docs"][ph][col], 1)
            idf = math.log(1 + (stats["n"] - docs + BM25_IDF_SMOOTHING) / (docs + BM25_IDF_SMOOTHING))
            norm = 1 - BM25_B + BM25_B * info["len"][col] / max(stats["avg"][col], 1.0)
            score += (TITLE_WEIGHT if col == 0 else BODY_WEIGHT) * idf * tf * (BM25_K1 + 1) / (tf + BM25_K1 * norm)
    return score


def rank_sections(guide, city, country_first, history_or_climate=True):
    """rankSections: [(sezione, matchinfo)] della guida del paese e delle citta' -> le MAX_SECTIONS migliori."""
    tables = [rows[0][1] for rows in (guide, city) if rows]
    if not tables:
        return []
    stats = corpus_stats(tables)
    weight = lambda s: WIKIPEDIA_OFF_TOPIC_WEIGHT if not history_or_climate and s["category"] in WIKI else 1.0
    ranked = [(bm25(info, stats), True, s) for s, info in guide] + [(bm25(info, stats) * weight(s), False, s) for s, info in city]
    ranked.sort(key=lambda r: (country_first and not r[1], -r[0]))
    return [s for _, _, s in ranked[:MAX_SECTIONS]]


def relevant_paragraphs(body, stems, budget):
    """relevantParagraphs."""
    if len(body) <= budget:
        return body
    paragraphs = [p for p in body.split("\n") if p.strip()]
    hits = [sum(s in folded(p) for s in stems) for p in paragraphs]
    kept, used = [], 0
    for i in sorted(range(len(paragraphs)), key=lambda i: (-hits[i], i)):
        if used + len(paragraphs[i]) + 1 <= budget:
            kept.append(i)
            used += len(paragraphs[i]) + 1
    return "\n".join(paragraphs[i] for i in sorted(kept)) if kept else body[:budget]


def select_context(bodies, stems, max_chars=MAX_CONTEXT):
    """selectContext."""
    parts, remaining = [], max_chars
    for body in bodies:
        if remaining < MIN_SECTION_CHARS:
            break
        part = relevant_paragraphs(body, stems, remaining)
        if part.strip():
            parts.append(part)
            remaining -= len(part) + 2
    return "\n\n".join(parts)[:max_chars]


# --- database come sul telefono --------------------------------------------------------------------------------------
def build_db(guides_db, region, city_rows):
    """region.db in memoria con le tabelle FTS4 come le crea Room (content esterno, unicode61). [city_rows]:
    (citta', categoria, titolo, corpo, url) nell'ordine di import."""
    db = sqlite3.connect(":memory:")
    for t, extra in (("guide_sections", ""), ("city_sections", "city TEXT,")):
        db.execute(f"CREATE TABLE {t} (id INTEGER PRIMARY KEY, regionId TEXT, {extra} category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
        db.execute(f"CREATE VIRTUAL TABLE {t}_fts USING fts4(title, body, content='{t}', tokenize=unicode61)")
    with closing(sqlite3.connect(guides_db)) as guides:
        for row in guides.execute("SELECT category, title, body, sourceUrl FROM guide_sections WHERE regionId = ?", (region,)):
            db.execute("INSERT INTO guide_sections (regionId, category, title, body, sourceUrl) VALUES (?,?,?,?,?)", (region, *row))
    for row in city_rows:
        db.execute("INSERT INTO city_sections (regionId, city, category, title, body, sourceUrl) VALUES (?,?,?,?,?,?)", (region, *row))
    for t in ("guide_sections", "city_sections"):
        db.execute(f"INSERT INTO {t}_fts({t}_fts) VALUES('rebuild')")
    return db


def search(db, region, question, cities):
    """TravelAssistant.ask fino alle sezioni del contesto: (sezioni, radici della domanda)."""
    fts = fts_query(question, region)
    if not fts:
        return [], set()
    city = named_city(question, cities)
    guide = [({"city": None, "category": k, "body": b}, parse_matchinfo(mi)) for k, b, mi in db.execute(
        "SELECT s.category, s.body, matchinfo(guide_sections_fts, 'pcxnal') FROM guide_sections s "
        "JOIN guide_sections_fts ON s.id = guide_sections_fts.rowid "
        f"WHERE guide_sections_fts MATCH ? AND s.regionId = ? LIMIT {CANDIDATE_CAP}", (fts, region))]
    city_rows = [({"city": c, "category": k, "body": b}, parse_matchinfo(mi)) for c, k, b, mi in db.execute(
        "SELECT s.city, s.category, s.body, matchinfo(city_sections_fts, 'pcxnal') FROM city_sections s "
        "JOIN city_sections_fts ON s.id = city_sections_fts.rowid "
        "WHERE city_sections_fts MATCH ? AND s.regionId = ? AND (? IS NULL OR s.city = ?) LIMIT ?",
        (fts, region, city, city, CITY_CANDIDATE_CAP if city else CANDIDATE_CAP))]
    history_or_climate = bool(HISTORY_CLIMATE_WORDS.search(question))
    return rank_sections(guide, city_rows, country_first=city is None, history_or_climate=history_or_climate), focus_stems(fts, city)


def answer_paragraph(body, stems):
    """Il paragrafo della sezione attesa con piu' radici della domanda (il primo se nessuno ne ha)."""
    paragraphs = [p for p in body.split("\n") if p.strip()]
    return max(paragraphs, key=lambda p: sum(s in p.lower() for s in stems))


# --- dati --------------------------------------------------------------------------------------------------------------
def download(url, out):
    if not out.exists():
        data = urllib.request.urlopen(url).read()
        out.write_bytes(lzma.decompress(data) if url.endswith(".xz") else data)
    return out


def fetch_data(regions, cache, lang):
    """guides<suffisso>.db e <regione>.cities<suffisso>.db pubblicati, piu' <regione>.wp<suffisso>.db con Storia e
    Clima se i cities.db pubblicati non li hanno ancora."""
    conf = LANGS[lang]
    sfx = conf["suffix"]
    cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(urllib.request.urlopen(MANIFEST_URL).read())
    download(manifest[conf["guides"]]["fileXz"]["url"], cache / f"guides{sfx}.db")
    by_id = {r["regionId"]: r for r in manifest["regions"]}
    for region in regions:
        cities_db = download(by_id[region][conf["cities"]]["fileXz"]["url"], cache / f"{region}.cities{sfx}.db")
        wp_db = cache / f"{region}.wp{sfx}.db"
        with closing(sqlite3.connect(cities_db)) as db:
            published = {k for (k,) in db.execute("SELECT DISTINCT category FROM city_sections")}
            names = [c for (c,) in db.execute("SELECT DISTINCT city FROM city_sections")]
        if wp_db.exists() or published & WIKI:
            continue
        jsonl = cache / f"{region}.wp{sfx}.jsonl"
        jsonl.write_text("".join(json.dumps({"city": c, "text": ""}, ensure_ascii=False) + "\n" for c in names), encoding="utf-8")
        city_wikipedia.annotate([jsonl], lang)
        gradlew = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
        lang_arg = "--lang en " if lang == "en" else ""
        subprocess.run([str(gradlew), "-q", ":tools:data-pipeline:content:generateCities", f"--args={lang_arg}{jsonl} {wp_db}"],
                       cwd=REPO_ROOT, check=True)


def city_rows_of(cache, region, with_wikipedia, lang="it"):
    """Sezioni delle citta' nell'ordine di import di un cities.db della pipeline: per citta', Wikivoyage poi Wikipedia."""
    sfx = LANGS[lang]["suffix"]
    rows = defaultdict(list)
    for row in sqlite3.connect(cache / f"{region}.cities{sfx}.db").execute("SELECT city, category, title, body, sourceUrl FROM city_sections"):
        if with_wikipedia or row[1] not in WIKI:
            rows[row[0]].append(row)
    wp_db = cache / f"{region}.wp{sfx}.db"
    if with_wikipedia and wp_db.exists():
        for row in sqlite3.connect(wp_db).execute("SELECT city, category, title, body, sourceUrl FROM city_sections"):
            if row[0] in rows:
                rows[row[0]].append(row)
    return [r for city_rows in rows.values() for r in city_rows]


def make_plan(region, city_rows, rng, lang="it"):
    """(tipo, categoria attesa, citta' attesa o None, domanda) per la regione."""
    city_q, wiki_q, country_q = LANGS[lang]["questions"]
    cats = defaultdict(set)
    for city, cat, *_ in city_rows:
        cats[city].add(cat)
    eligible = sorted(c for c in cats if {"COSA_VEDERE", "TRASPORTI"} <= cats[c] and cats[c] & WIKI)
    plan = []
    for c in rng.sample(eligible, min(len(eligible), CITIES_PER_REGION.get(region, DEFAULT_CITIES))):
        for cat, qs in city_q.items():
            if cat in cats[c]:
                plan.append((KINDS[0], cat, c, rng.choice(qs).format(c=spoken_city_name(c))))
        for cat, qs in wiki_q.items():
            if cat in cats[c]:
                plan.append((KINDS[2], cat, c, rng.choice(qs).format(c=spoken_city_name(c))))
    for cat, qs in country_q.items():
        plan += [(KINDS[1], cat, None, q) for q in qs]
    return plan


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--lang", choices=sorted(LANGS), default="it")
    ap.add_argument("--regions", nargs="+", default=DEFAULT_REGIONS)
    ap.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "pocket-travel-eval-retrieval")
    ap.add_argument("--no-wikipedia", action="store_true", help="senza Storia e Clima (per confronto)")
    a = ap.parse_args()
    fetch_data(a.regions, a.cache, a.lang)
    rng = random.Random(7)
    res = defaultdict(lambda: defaultdict(int))
    for region in a.regions:
        # Le domande si scelgono sempre con Storia e Clima, cosi' con e senza --no-wikipedia sono le stesse.
        plan = make_plan(region, city_rows_of(a.cache, region, True, a.lang), rng, a.lang)
        rows = city_rows_of(a.cache, region, not a.no_wikipedia, a.lang)
        db = build_db(a.cache / f"guides{LANGS[a.lang]['suffix']}.db", region, rows)
        cities = sorted({r[0] for r in rows})
        for kind, cat, city, q in plan:
            top, stems = search(db, region, q, cities)
            r = res[kind]
            r["n"] += 1
            match = [s for s in top if s["category"] == cat and (city is None or s["city"] == city)]
            r["trovata"] += bool(match)
            r["prima"] += bool(top) and top[0] in match
            r["Storia/Clima di troppo"] += kind != KINDS[2] and any(s["category"] in WIKI for s in top)
            if match:
                r["risposta nel contesto"] += answer_paragraph(match[0]["body"], stems)[:150] in select_context([s["body"] for s in top], stems)
    for kind in KINDS:
        r = res[kind]
        n = max(r["n"], 1)
        print(f"{kind} ({r['n']} domande): trovata {100 * r['trovata'] / n:.1f}%, prima {100 * r['prima'] / n:.1f}%, "
              f"Storia/Clima di troppo {100 * r['Storia/Clima di troppo'] / n:.1f}%, "
              f"risposta nel contesto {100 * r['risposta nel contesto'] / max(r['trovata'], 1):.1f}% delle trovate")


if __name__ == "__main__":
    main()
