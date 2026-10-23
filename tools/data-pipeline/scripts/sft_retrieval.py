#!/usr/bin/env python3
"""Righe SFT positive con il contesto che la ricerca dell'app mette nel prompt (eval_retrieval.py: buildFtsQuery,
rankSections, selectContext, liste di parole accese, guida generale), sulle citta' dei cities.db pubblicati.

Le domande hanno i giri di frase che il modello 0.8B rifiuta anche con la sezione giusta nel contesto (una giornata a X,
X era romana?, quando andare a X, dove pernottare, specialita' da assaggiare), con parole diverse da quelle delle serie
di eval_retrieval.py e dei test di generate_eval_set*.py, che misurano il modello addestrato. Le regioni sono quelle
pubblicate fuori dalle regioni di test (e dalle loro nazioni) e fuori da DEFAULT_REGIONS di eval_retrieval.py, cosi'
le citta' sono diverse da quelle della misura.

Una riga si tiene solo se la sezione attesa (categoria e citta' della domanda) e' nel contesto; la risposta sono frasi
della sezione presenti nel contesto, scelte come in generate_sft.py (make_answer_for), di almeno MIN_ANSWER caratteri.
Le domande puntuali (ANSWER_NEEDS) tengono la risposta solo se contiene le parole che la domanda chiede: senza, il
modello imparerebbe a rispondere anche quando la sezione non ne parla. Con [refusal] le domande in cui la ricerca non
porta nel contesto nessuna sezione della categoria danno il rifiuto, come il contesto vero dell'app.

Uso: python sft_retrieval.py --lang it|en --cache DIR --out FILE [--rows 600] [--seed 42] [--refusals]
Richiede rete (`manifest.json`, guides.db e cities.db pubblicati); non chiama Wikipedia ne' Gradle: le regioni il cui
cities.db non ha Storia e Clima si saltano.
"""
import argparse
import json
import random
import re
import sqlite3
import time
import urllib.parse
from collections import Counter
from contextlib import closing
from pathlib import Path

import eval_retrieval as er
import generate_sft as gs
import generate_sft_dataset as it
from eval_common import TEST_REGIONS
from translate_dataset import LICENSE as MT_LICENSE, MODELS as MT_MODELS

MIN_ANSWER = 40
CITIES_PER_REGION = 15
NEGATIVES = 0.33  # rifiuti per positivo con [refusal], come --negatives di generate_sft.py

QUESTIONS = {
    "it": {
        "COSA_VEDERE": ["Ho una sola giornata a {c}: cosa vedo?", "Cosa visitare a {c} in un pomeriggio?",
                        "Un weekend a {c}: quali attrazioni non perdere?", "Cosa fare a {c} in poche ore?",
                        "Ho mezza giornata libera a {c}, cosa mi consigli di vedere?", "Un itinerario di un giorno a {c}?",
                        "Cosa fare a {c} durante un weekend?", "Cosa vedere a {c} in un giorno solo?"],
        "STORIA": ["{c} ha origini romane?", "{c} esisteva già in epoca romana?", "{c} è una città medievale?",
                   "In che anno è stata fondata {c}?", "In quale secolo è nata {c}?", "Da quanti secoli esiste {c}?",
                   "Da chi è stata fondata {c}?", "Chi furono i fondatori di {c}?", "A quando risalgono le origini di {c}?"],
        "CLIMA": ["In che stagione conviene andare a {c}?", "In quali mesi conviene andare a {c}?",
                  "In quale stagione è meglio visitare {c}?", "Quando andare a {c} per trovare bel tempo?",
                  "Qual è il periodo ideale per una vacanza a {c}?", "Che stagione scegliere per un viaggio a {c}?",
                  "Meglio visitare {c} d'estate o d'inverno?", "Qual è il periodo più adatto per andare a {c}?"],
        "ALLOGGIO": ["Dove passo la notte a {c}?", "Dove si può pernottare a {c}?", "Cerco dove dormire spendendo poco a {c}",
                     "Un posto per dormire a buon prezzo a {c}?", "Dove trovo una sistemazione per una notte a {c}?",
                     "Dove fermarsi a dormire a {c}?", "Mi serve un alloggio economico a {c}",
                     "Ci sono posti economici dove pernottare a {c}?"],
        "CIBO_BEVANDE": ["Quali specialità devo assaggiare a {c}?", "Che piatti tipici si mangiano a {c}?",
                         "Com'è la cucina locale a {c}?", "Dove andare a cena a {c}?", "Quali prodotti locali assaggiare a {c}?",
                         "Cosa devo assolutamente mangiare a {c}?", "Dove cenare a {c}?",
                         "Quali sono le specialità gastronomiche di {c}?"],
    },
    "en": {
        "COSA_VEDERE": ["I have one day in {c}, what should I see?", "What can I do in {c} in an afternoon?",
                        "How should I spend a weekend in {c}?", "What to do in {c} with only a few hours?",
                        "A one-day itinerary for {c}?", "What can I visit in {c} on a day trip?",
                        "What are the must-sees in {c} if I only have a day?", "How do I spend 24 hours in {c}?"],
        "STORIA": ["Does {c} have Roman origins?", "Did {c} already exist in Roman times?", "Is {c} a medieval town?",
                   "In what year was {c} founded?", "In which century was {c} founded?", "How many centuries old is {c}?",
                   "Who were the founders of {c}?", "By whom was {c} founded?", "How old is the city of {c}?"],
        "CLIMA": ["What is the best time of year to visit {c}?", "Which months are best for a trip to {c}?",
                  "When should I go to {c} for good weather?", "What season is ideal for visiting {c}?",
                  "Is it better to visit {c} in summer or in winter?", "What is the ideal period for a holiday in {c}?",
                  "When is the best season to travel to {c}?", "In which months is the weather best in {c}?"],
        "ALLOGGIO": ["Where could I stay overnight in {c}?", "Any budget places to sleep in {c}?",
                     "I'm looking for somewhere cheap to stay in {c}", "Where can I find a bed for the night in {c}?",
                     "Where do visitors stay in {c}?", "Any affordable accommodation in {c}?",
                     "Where can I sleep cheaply in {c}?", "Is there inexpensive lodging in {c}?"],
        "CIBO_BEVANDE": ["What local specialties are worth trying in {c}?", "Which traditional dishes does {c} have?",
                         "What is the local cuisine of {c} like?", "Where should I go for dinner in {c}?",
                         "What food should I not miss in {c}?", "Where can I eat out in the evening in {c}?",
                         "What are the typical foods to try in {c}?", "Which regional specialities should I taste in {c}?"],
    },
}
CATEGORIES = tuple(QUESTIONS["it"])

# Domande puntuali: la risposta deve contenere ognuna di queste espressioni (sulla risposta in minuscolo). Il Sacro
# Romano Impero, lo stile romanico e il romanticismo non dicono nulla sulle origini romane; "fondato" e una data da soli
# non bastano ("nel 1888 fu fondato il birrificio"): servono le origini o la fondazione della citta'.
ROMAN = r"(?<!sacro )(?<!holy )roman(?!ic|tic|esque|z)"
MEDIEVAL = r"mediev|medioev|middle ages"
ORIGIN = (r"fondat[ao] (?:nel|nell|intorno|attorno|verso|tra|da|dai|dagli|dal|dalla|come)\b|fondò|fondazione|risal|insediament|"
          r"origin|nacque|sorse|founded (?:in|by|around|about|as|during|on)\b|establish|settle|earliest")
FOUNDER = r"fondat[ao] (?:da|dai|dagli|dal|dalla|dall)\b|fondò|fondator|founded by|founder|established by|settled by"
DATE = r"(?<![\d.,])\d{3,4}(?![\d.,]?\d)|secol|centur|\ba\. ?c\.|\bb\.?c\.?\b"
SPECIALTIES = r"special|tipic|tradiz|local|piatt|cucin|typical|tradition|dish|cuisine"
ANSWER_NEEDS = {
    "{c} ha origini romane?": (ROMAN,), "{c} esisteva già in epoca romana?": (ROMAN,), "{c} è una città medievale?": (MEDIEVAL,),
    "In che anno è stata fondata {c}?": (ORIGIN, DATE), "In quale secolo è nata {c}?": (ORIGIN, DATE),
    "Da quanti secoli esiste {c}?": (ORIGIN,), "Da chi è stata fondata {c}?": (FOUNDER,),
    "Chi furono i fondatori di {c}?": (FOUNDER,), "A quando risalgono le origini di {c}?": (ORIGIN,),
    "Does {c} have Roman origins?": (ROMAN,), "Did {c} already exist in Roman times?": (ROMAN,),
    "Is {c} a medieval town?": (MEDIEVAL,), "In what year was {c} founded?": (ORIGIN, DATE),
    "In which century was {c} founded?": (ORIGIN, DATE), "How many centuries old is {c}?": (ORIGIN,),
    "Who were the founders of {c}?": (FOUNDER,), "By whom was {c} founded?": (FOUNDER,), "How old is the city of {c}?": (ORIGIN,),
    "Quali specialità devo assaggiare a {c}?": (SPECIALTIES,), "Quali sono le specialità gastronomiche di {c}?": (SPECIALTIES,),
    "What local specialties are worth trying in {c}?": (SPECIALTIES,),
    "Which regional specialities should I taste in {c}?": (SPECIALTIES,),
}


def training_regions(manifest, lang):
    """Le regioni pubblicate con il cities.db di [lang], fuori dalle regioni di test, da quelle che vi appartengono
    (regionId che comincia con quello di una regione di test, stessa nazione in regions.sh) e dalle nazioni di
    DEFAULT_REGIONS di eval_retrieval.py, che misurano il modello addestrato."""
    key = er.LANGS[lang]["cities"]
    blocked = {er.REGION_COUNTRY.get(r) for r in [*TEST_REGIONS, *er.DEFAULT_REGIONS]} - {None}
    return sorted(r["regionId"] for r in manifest["regions"]
                  if key in r and r["regionId"] not in TEST_REGIONS and r["regionId"] not in er.DEFAULT_REGIONS
                  and not any(r["regionId"].startswith(f"{t}-") for t in TEST_REGIONS)
                  and er.REGION_COUNTRY.get(r["regionId"]) not in blocked)


def answer_has_needs(template, answer):
    """True se [answer] contiene le espressioni che la domanda di [template] chiede (ANSWER_NEEDS), o se non ne chiede."""
    return all(re.search(rx, answer.lower()) for rx in ANSWER_NEEDS.get(template, ()))


def section_license(url, translated, lang):
    """La licenza di una sezione per ATTRIBUTION, come generate_sft.py; None per le fonti che il dataset non usa."""
    host = urllib.parse.urlsplit(url).netloc
    lic = (gs.GUIDE_LICENSES.get(host) or (gs.WIKI_LICENSE if host.endswith(".wikipedia.org") else None)
           or ("CC BY-SA 4.0" if host.endswith(".wikivoyage.org") else None))
    if lic and translated:
        lic += f" ({gs.LANGS[lang]['translated']}, {MT_MODELS[(gs.OTHER[lang], lang)]}, {MT_LICENSE})"
    return lic


def sources_of(db_path, table, key_column):
    """{(chiave, categoria, corpo): (sourceUrl, tradotta)} delle sezioni di [table] in [db_path]; chiave e' la
    regione (guide_sections) o la citta' (city_sections)."""
    with closing(sqlite3.connect(db_path)) as db:
        has_translated = any(col[1] == "translated" for col in db.execute(f"PRAGMA table_info({table})"))
        return {(k, c, b): (u, bool(t)) for k, c, b, u, t in db.execute(
            f"SELECT {key_column}, category, body, sourceUrl, {'IFNULL(translated, 0)' if has_translated else '0'} FROM {table}")}


def in_context(section, context):
    """True se almeno un paragrafo di [section] e' nel contesto (come section_in_context di eval_retrieval.py)."""
    return any(p[:150] in context for p in section["body"].split("\n") if p.strip())


def city_examples(db, region, city, cities, cats, lang, rng, answer_for, keywords):
    """[(tipo, categoria, domanda, contesto, risposta o None, sezioni nel contesto, motivo)] per le categorie [cats]
    della citta' [city]: una domanda per categoria, ricerca dell'app con liste di parole e guida generale."""
    name = er.spoken_city_name(city)
    out = []
    for cat in cats:
        template = rng.choice(QUESTIONS[lang][cat])
        q = template.format(c=name)
        top, stems = er.search(db, region, q, cities, lang, word_lists=True)
        context = er.select_context([s["body"] for s in top], stems)
        used = [s for s in top if in_context(s, context)]
        match = [s for s in used if s["category"] == cat and s["city"] == city and not s.get("general_guide")]
        if not match:
            covered = any(it.covers(cat, s["body"], kw) for s in used for kw in keywords)
            out.append(("neg", cat, q, context, None, used, "sezione-fuori" if covered or not used else "categoria-assente"))
            continue
        answer = answer_for(context, match[0]["body"], q, cat, name)
        if len(answer) < MIN_ANSWER:
            out.append(("pos", cat, q, context, None, used, "risposta-vuota"))
        elif not answer_has_needs(template, answer):
            out.append(("pos", cat, q, context, None, used, "risposta-senza-parole-della-domanda"))
        else:
            out.append(("pos", cat, q, context, answer, used, None))
    return out


def retrieval_rows(lang, n_rows, rng, cache_dir, refusal=None, regions=None, cities_per_region=CITIES_PER_REGION,
                   negatives=NEGATIVES):
    """(righe, attribuzioni) per il dataset in [lang]: circa [n_rows] righe positive nel formato di row() di
    generate_sft.py, con il contesto della ricerca dell'app, e le righe (regionId, displayName, sourceUrl, licenza)
    delle sezioni nei loro contesti. Regioni di training_regions (o [regions]) in ordine casuale con [rng], al massimo
    [cities_per_region] citta' ciascuna, finche' ogni categoria ha la sua quota ([n_rows] diviso per le categorie): una
    categoria con poche sezioni (Clima) puo' restare sotto quota quando le regioni finiscono. Con [refusal] (argomento -> testo del rifiuto, come
    in generate_sft.py) anche fino a [negatives] rifiuti per positivo, sulle domande il cui contesto non ha nessuna
    sezione della categoria (nelle due lingue). Usa le tabelle di generate_sft.LANGS al momento della chiamata."""
    started = time.monotonic()
    L = gs.LANGS[lang]
    answer_for = gs.make_answer_for(L)
    keywords = [gs.all_keywords(L), gs.all_keywords(gs.LANGS[gs.OTHER[lang]])]
    conf, cache = er.LANGS[lang], Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(er.fetch(er.MANIFEST_URL))
    by_id = {r["regionId"]: r for r in manifest["regions"]}
    guides_db = er.download(manifest[conf["guides"]]["fileXz"]["url"], cache / f"guides{conf['suffix']}.db")
    guide_sources = sources_of(guides_db, "guide_sections", "regionId")
    pool = list(regions) if regions else training_regions(manifest, lang)
    rng.shuffle(pool)
    quota = -(-n_rows // len(CATEGORIES))
    rows, negs, attribution, seen, stats = [], [], [], set(), Counter()
    for region in pool:
        open_cats = {c for c in CATEGORIES if stats[c] < quota}
        if not open_cats:
            break
        cities_db = er.download(by_id[region][conf["cities"]]["fileXz"]["url"], cache / f"{region}.cities{conf['suffix']}.db")
        city_rows = er.city_rows_of(cache, region, True, lang)
        if not {r[1] for r in city_rows} & er.WIKI:
            stats["regioni senza Storia e Clima"] += 1
            continue
        city_sources = sources_of(cities_db, "city_sections", "city")
        cats_of = {}
        for city, cat, *_ in city_rows:
            if cat in open_cats and len(er.spoken_city_name(city)) >= er.MIN_CITY_NAME_CHARS:
                cats_of.setdefault(city, set()).add(cat)
        db = er.build_db(guides_db, region, city_rows)
        cities = sorted({r[0] for r in city_rows})
        stats["regioni"] += 1
        for city in rng.sample(sorted(cats_of), min(len(cats_of), cities_per_region)):
            for kind, cat, q, context, answer, used, reason in city_examples(
                    db, region, city, cities, sorted(c for c in cats_of[city] if stats[c] < quota), lang, rng, answer_for, keywords):
                stats[cat, reason or "tenuta"] += 1
                if kind == "neg" and reason == "categoria-assente" and refusal and context:
                    answer = refusal(L["topic"][cat])
                elif reason:
                    continue
                if (context, q) in seen:
                    stats[cat, "doppione"] += 1
                    continue
                seen.add((context, q))
                lics = [(s, *(city_sources if s["city"] else guide_sources).get(
                    (s["city"] or s["region"], s["category"], s["body"]), (None, False))) for s in used]
                lics = [(s, url, tr, section_license(url or "", tr, lang)) for s, url, tr in lics]
                if any(lic is None for *_, lic in lics):
                    stats[cat, "fonte-sconosciuta"] += 1
                    continue
                row = {"messages": [{"role": "user", "content": L["prompt"](context, q)}, {"role": "assistant", "content": answer}],
                       "kind": kind, "region": f"citta:{city}", "category": cat, "translated": any(tr for *_, tr, _ in lics)}
                (rows if kind == "pos" else negs).append(row)
                stats[cat] += kind == "pos"
                for s, url, _, lic in lics:
                    rid, display = ((f"citta:{s['city']}", er.spoken_city_name(s["city"])) if s["city"] else
                                    (s["region"], by_id.get(s["region"], {}).get("displayName", s.get("title", s["region"]))))
                    attribution.append((rid, display, url, lic))
        db.close()
    rows += negs[:int(len(rows) * negatives)]
    attribution = list(dict.fromkeys(attribution))
    asked = Counter()  # una domanda per motivo; doppioni e fonti sconosciute sono tra le "tenute"
    for (cat, reason), n in ((k, v) for k, v in stats.items() if isinstance(k, tuple)):
        asked[cat] += n * (reason not in ("doppione", "fonte-sconosciuta"))
    print(f"sft_retrieval {lang}: {stats['regioni']} regioni ({stats['regioni senza Storia e Clima']} senza Storia e Clima), "
          f"{sum(r['kind'] == 'pos' for r in rows)} positivi, {sum(r['kind'] == 'neg' for r in rows)} rifiuti, "
          f"{len(attribution)} fonti, {time.monotonic() - started:.0f} s")
    for cat in CATEGORIES:
        reasons = {r: stats[cat, r] for r in ("tenuta", "sezione-fuori", "categoria-assente", "risposta-vuota",
                                              "risposta-senza-parole-della-domanda", "doppione", "fonte-sconosciuta")}
        print(f"  {cat}: {asked[cat]} domande, " + ", ".join(f"{r} {n} ({100 * n / max(asked[cat], 1):.0f}%)" for r, n in reasons.items()))
    return rows, attribution


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--lang", choices=("it", "en"), required=True)
    ap.add_argument("--cache", type=Path, required=True, help="cartella dei file scaricati")
    ap.add_argument("--out", type=Path, required=True, help="JSONL delle righe (accanto, <nome>.attribution.tsv)")
    ap.add_argument("--rows", type=int, default=600)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--refusals", action="store_true", help="anche i rifiuti, come con refusal in generate_sft.py")
    a = ap.parse_args()
    rng = random.Random(a.seed)
    L = gs.LANGS[a.lang]
    refusal = (lambda topic: L["refusal"](topic, rng.choice(L["tails"]))) if a.refusals else None
    rows, attribution = retrieval_rows(a.lang, a.rows, rng, a.cache, refusal)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    a.out.with_suffix(".attribution.tsv").write_text(
        "regionId\tdisplayName\tsourceUrl\tlicense\n" + "".join("\t".join(r) + "\n" for r in attribution), encoding="utf-8")
    print(f"scritto {a.out}")


if __name__ == "__main__":
    main()
