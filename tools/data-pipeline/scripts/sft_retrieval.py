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

Per il dataset v12 (generate_sft.py): con general_first il contesto ha l'ordine dell'app, una quota delle righe ha una
pagina della guida generale in testa e ci sono anche domande sul paese, le sole in cui la ricerca porta la guida generale
(city_examples); task_rows da' le righe del riordino delle sezioni candidate e delle
parole di ricerca, con domande dirette, riformulate e nell'altra lingua.

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

import audit_sft
import eval_retrieval as er
import generate_sft as gs
import generate_sft_dataset as it
from eval_common import TEST_REGIONS

MIN_ANSWER = 40
CITIES_PER_REGION = 15
NEGATIVES = 0.33  # rifiuti per positivo con [refusal], come --negatives di generate_sft.py
# Con general_first (v12) anche domande sul paese, dove la guida generale entra nel contesto: righe positive in piu', in
# quota delle righe richieste, e categorie della guida chieste per regione
COUNTRY_SHARE = 0.5
COUNTRY_CATS = 3

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
    """La licenza di una sezione per ATTRIBUTION, come generate_sft.py; None per le fonti che il dataset non usa e per
    le sezioni tradotte da un modello senza licenza verificata o non dichiarato. [translated]: il modello che ha tradotto
    la sezione (translatedBy, "" se non dichiarato) o False."""
    host = urllib.parse.urlsplit(url).netloc
    lic = (gs.GUIDE_LICENSES.get(host) or (gs.WIKI_LICENSE if host.endswith(".wikipedia.org") else None)
           or ("CC BY-SA 4.0" if host.endswith(".wikivoyage.org") else None))
    if lic and translated is not False:
        if gs.translation_license(translated) is None:
            return None
        lic += gs.translation_note(translated, gs.LANGS[lang])
    return lic


def sources_of(db_path, table, key_column):
    """{(chiave, categoria, corpo): (sourceUrl, tradotta)} delle sezioni di [table] in [db_path]; chiave e' la
    regione (guide_sections) o la citta' (city_sections); tradotta e' il modello di translatedBy ("" se la colonna
    manca o e' vuota) o False."""
    with closing(sqlite3.connect(db_path)) as db:
        columns = {col[1] for col in db.execute(f"PRAGMA table_info({table})")}
        return {(k, c, b): (u, (m or "") if t else False) for k, c, b, u, t, m in db.execute(
            f"SELECT {key_column}, category, body, sourceUrl, {'IFNULL(translated, 0)' if 'translated' in columns else '0'}, "
            f"{'translatedBy' if 'translatedBy' in columns else 'NULL'} FROM {table}")}


def in_context(section, context):
    """True se almeno un paragrafo di [section] e' nel contesto (come section_in_context di eval_retrieval.py)."""
    return any(p[:150] in context for p in section["body"].split("\n") if p.strip())


def licensed(sections, city_sources, guide_sources, lang):
    """[(sezione, sourceUrl, tradotta, licenza)] delle [sections], None se una viene da una fonte che il dataset non usa.
    La prima pagina della guida generale, ridotta alla sua quota del contesto (general_guide_share), si cerca col corpo
    intero (section_body)."""
    out = []
    for s in sections:
        url, tr = (city_sources if s["city"] else guide_sources).get(
            (s["city"] or s["region"], s["category"], s.get("section_body", s["body"])), (None, False))
        lic = section_license(url or "", tr, lang)
        if lic is None:
            return None
        out.append((s, url, tr, lic))
    return out


def attribution_of(lics, by_id):
    """Le righe di ATTRIBUTION (regionId, displayName, sourceUrl, licenza) delle sezioni di licensed()."""
    return [((f"citta:{s['city']}", er.spoken_city_name(s["city"])) if s["city"] else
             (s["region"], by_id.get(s["region"], {}).get("displayName", s.get("title", s["region"])))) + (url, lic)
            for s, url, _, lic in lics]


def general_first_context(db, region, q, cities, lang, top, stems):
    """(contesto, sezioni) con una pagina della guida generale in testa e dopo le altre sezioni di [top] nell'ordine della
    ricerca, come il caso della Corsica (righe del v12): la pagina della ricerca o, se non ce n'e', la migliore tra le
    candidate della guida generale; None se la guida generale non ha pagine con le parole della domanda."""
    general = next((s for s in top if s.get("general_guide")), None)
    if general is None:
        pool, _ = er.search(db, region, q, cities, lang, word_lists=True, pool=er.MAX_SECTIONS, general_extra=1)
        general = next((s for s in pool if s.get("general_guide")), None)
        if general is None:
            return None
    sections = er.general_guide_share([general] + [s for s in top if not s.get("general_guide")][:er.MAX_SECTIONS - 1], stems)
    return er.select_context([s["body"] for s in sections], stems), sections


def city_examples(db, region, city, cities, cats, lang, rng, answer_for, keywords, general_first=None, country=None):
    """[(tipo, categoria, domanda, contesto, risposta o None, sezioni nel contesto, motivo)] per le categorie [cats]
    della citta' [city]: una domanda per categoria, ricerca dell'app con liste di parole e guida generale. Con
    [general_first] (righe del v12) il contesto ha l'ordine dell'app (on_device_context: la pagina della guida generale
    dopo la prima sezione), e una quota [general_first] delle righe con la sezione attesa ha invece una pagina della
    guida generale in testa (general_first_context), se la sezione attesa resta nel contesto; senza, l'ordine della
    ricerca (select_context). Con [country] (nome del paese, [city] None) domande sul paese con gli schemi di
    generate_sft.py: la sezione attesa e' quella della guida di [region]."""
    name = country or er.spoken_city_name(city)
    expected = lambda s: (s["category"] == cat and s["city"] == city and not s.get("general_guide")
                          and (city is not None or s["region"] == region))
    out = []
    for cat in cats:
        template = rng.choice(gs.LANGS[lang]["questions"][cat] if country else QUESTIONS[lang][cat])
        q = template.format(c=name, r=name)
        top, stems = er.search(db, region, q, cities, lang, word_lists=True)
        if general_first is None:
            context = er.select_context([s["body"] for s in top], stems)
        else:
            context = er.on_device_context(top, stems)
        used = [s for s in top if in_context(s, context)]
        match = [s for s in used if expected(s)]
        if match and general_first is not None and rng.random() < general_first:
            head = general_first_context(db, region, q, cities, lang, top, stems)
            head_used = [s for s in head[1] if in_context(s, head[0])] if head else []
            head_match = [s for s in head_used if expected(s)]
            if head_match and head_used[0].get("general_guide"):
                context, used, match = head[0], head_used, head_match
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
                   negatives=NEGATIVES, general_first=None, country_names=None):
    """(righe, attribuzioni) per il dataset in [lang]: circa [n_rows] righe positive nel formato di row() di
    generate_sft.py, con il contesto della ricerca dell'app, e le righe (regionId, displayName, sourceUrl, licenza)
    delle sezioni nei loro contesti. Regioni di training_regions (o [regions]) in ordine casuale con [rng], al massimo
    [cities_per_region] citta' ciascuna, finche' ogni categoria ha la sua quota ([n_rows] diviso per le categorie): una
    categoria con poche sezioni (Clima) puo' restare sotto quota quando le regioni finiscono. Con [refusal] (argomento -> testo del rifiuto, come
    in generate_sft.py) anche fino a [negatives] rifiuti per positivo, sulle domande il cui contesto non ha nessuna
    sezione della categoria (nelle due lingue). Con [general_first] l'ordine dei contesti del v12 (city_examples) e, con
    [country_names] ({regionId: nome nelle domande nella lingua del dataset, come le righe del paese di generate_sft.py}),
    anche domande sul paese per le regioni che vi compaiono. Usa le tabelle di generate_sft.LANGS al momento della chiamata."""
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
    country_quota = int(n_rows * COUNTRY_SHARE) if general_first is not None and country_names else 0
    rows, negs, attribution, seen, stats = [], [], [], set(), Counter()
    for region in pool:
        open_cats = {c for c in CATEGORIES if stats[c] < quota}
        if not open_cats and stats["paese"] >= country_quota:
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

        def keep(examples, city=None):
            """Le righe di [examples] (city_examples) delle citta' (stats per categoria) o del paese ("paese:<categoria>")."""
            for kind, cat, q, context, answer, used, reason in examples:
                key = cat if city else f"paese:{cat}"
                stats[key, reason or "tenuta"] += 1
                if kind == "neg" and reason == "categoria-assente" and refusal and context:
                    answer = refusal(L["topic"][cat])
                elif reason:
                    continue
                if (context, q) in seen:
                    stats[key, "doppione"] += 1
                    continue
                seen.add((context, q))
                lics = licensed(used, city_sources, guide_sources, lang)
                if lics is None:
                    stats[key, "fonte-sconosciuta"] += 1
                    continue
                row = {"messages": [{"role": "user", "content": L["prompt"](context, q)}, {"role": "assistant", "content": answer}],
                       "kind": kind, "region": f"citta:{city}" if city else region, "category": cat,
                       "translated": any(tr for *_, tr, _ in lics)}
                (rows if kind == "pos" else negs).append(row)
                stats[cat if city else "paese"] += kind == "pos"
                # le sezioni nell'ordine del contesto (used e' nell'ordine della ricerca)
                ordered = sorted(used, key=lambda s: min(context.find(p[:150]) for p in s["body"].split("\n") if p.strip() and p[:150] in context))
                stats["pagina generale in testa"] += kind == "pos" and ordered[0].get("general_guide", False)
                stats["pagina generale dopo"] += kind == "pos" and any(s.get("general_guide") for s in ordered[1:])
                attribution.extend(attribution_of(lics, by_id))
        for city in rng.sample(sorted(cats_of), min(len(cats_of), cities_per_region)):
            keep(city_examples(db, region, city, cities, sorted(c for c in cats_of[city] if stats[c] < quota), lang, rng, answer_for,
                               keywords, general_first), city)
        if stats["paese"] < country_quota and region in country_names:
            # Domande sul paese (nessuna citta' nominata): sono quelle in cui la guida generale entra nel contesto
            cats = sorted({c for r, c, _ in guide_sources if r == region} & set(L["questions"]))
            name = country_names[region]
            keep(city_examples(db, region, None, cities, rng.sample(cats, min(len(cats), COUNTRY_CATS)), lang, rng, answer_for,
                               keywords, general_first, country=name))
        db.close()
    rows += negs[:int(len(rows) * negatives)]
    attribution = list(dict.fromkeys(attribution))
    asked = Counter()  # una domanda per motivo; doppioni e fonti sconosciute sono tra le "tenute"
    for (cat, reason), n in ((k, v) for k, v in stats.items() if isinstance(k, tuple)):
        asked[cat] += n * (reason not in ("doppione", "fonte-sconosciuta"))
    print(f"sft_retrieval {lang}: {stats['regioni']} regioni ({stats['regioni senza Storia e Clima']} senza Storia e Clima), "
          f"{sum(r['kind'] == 'pos' for r in rows)} positivi, {sum(r['kind'] == 'neg' for r in rows)} rifiuti, "
          f"{len(attribution)} fonti, {time.monotonic() - started:.0f} s; positivi con una pagina della guida generale in "
          f"testa {stats['pagina generale in testa']}, dopo la prima sezione {stats['pagina generale dopo']}")
    for cat in CATEGORIES + tuple(sorted(c for c in asked if c.startswith("paese:"))):
        reasons = {r: stats[cat, r] for r in ("tenuta", "sezione-fuori", "categoria-assente", "risposta-vuota",
                                              "risposta-senza-parole-della-domanda", "doppione", "fonte-sconosciuta")}
        print(f"  {cat}: {asked[cat]} domande, " + ", ".join(f"{r} {n} ({100 * n / max(asked[cat], 1):.0f}%)" for r, n in reasons.items()))
    return rows, attribution


# Righe dei compiti del v12 (task_rows): riordino delle sezioni candidate e parole di ricerca. Candidate come
# eval_retrieval.py --rerank: le prime RERANK["candidates"] della ricerca piu' le RERANK["general"] pagine migliori della
# guida generale; della candidata i paragrafi con piu' radici della domanda, 300 caratteri (la proposta per l'app, 4
# candidate) o 600 (la misura senza training).
TASK_SNIPPETS = (300, 600)
TASK_NO_PER_QUESTION = 2  # "no" al massimo per domanda, di motivi diversi se possibile: il "sì" e' al piu' uno
QUESTION_KINDS = ("diretta", "riformulata", "altra-lingua")
SEARCH_WORD = re.compile(r"[^\W\d_]{4,}")
# Parole delle domande di QUESTIONS che non dicono il tema (tempi, verbi generici, ausiliari): mai parole di ricerca
SEARCH_STOPWORDS = {
    "it": set("adatto andare anno assolutamente buon cerco città durante esiste esisteva fermarsi furono giornata giorno "
              "ideale libera mezza nata perdere poche poco pomeriggio posti qual quanti scegliere sola solo spendendo "
              "stata tempo trovare vacanza vedo viaggio weekend".split()),
    "en": set("afternoon already better city evening exist holiday hours ideal looking miss must only places sees somewhere "
              "spend time times town travel trip trying visitors were whom worth year weekend".split()),
}


def task_question(lang, cat, rng):
    """(tipo, schema, domanda con {c}, schema equivalente nella lingua della guida o None) per la categoria [cat]:
    diretta (gli schemi delle citta' di generate_sft.py), riformulata (QUESTIONS) o nell'altra lingua (QUESTIONS
    dell'altra lingua, con lo schema allo stesso posto nella lingua della guida per le parole di ricerca)."""
    L, kind = gs.LANGS[lang], rng.choice(QUESTION_KINDS)
    if kind == "diretta":
        template = rng.choice({**L["city_questions"], **L["wiki_questions"]}[cat])
        return kind, template, template.replace("{r}", "{c}"), None
    if kind == "riformulata":
        template = rng.choice(QUESTIONS[lang][cat])
        return kind, template, template, None
    i = rng.randrange(len(QUESTIONS[gs.OTHER[lang]][cat]))
    return kind, QUESTIONS[gs.OTHER[lang]][cat][i], QUESTIONS[gs.OTHER[lang]][cat][i], QUESTIONS[lang][cat][i]


def search_words(counterpart, body, stems, stopwords, city):
    """Da 3 a 5 parole di ricerca nella lingua della guida, ricavate dalla sezione [body] che risponde: le parole della
    domanda equivalente [counterpart] (nella lingua della guida, senza [stopwords] e il nome della citta') che la sezione
    contiene, poi le parole della sezione che cominciano con le radici della categoria [stems], dalla piu' frequente;
    una parola per radice di STEM_CHARS lettere. [] se ne restano meno di 3."""
    folded_body, out = er.folded(body), []
    city_words = set(er.folded(city).split())

    def add(word):
        if all(er.folded(w)[:er.STEM_CHARS] != er.folded(word)[:er.STEM_CHARS] for w in out):
            out.append(word)
    for w in SEARCH_WORD.findall(counterpart.replace("{c}", " ").lower()):
        if w not in stopwords and er.folded(w) not in city_words and er.folded(w)[:er.STEM_CHARS] in folded_body:
            add(w)
    counts = Counter(w.lower() for w in SEARCH_WORD.findall(body) if er.folded(w) not in city_words)
    for w, _ in counts.most_common():
        if any(er.folded(w).startswith(er.folded(s)) for s in stems if re.fullmatch(r"[^\W\d_]+", s)):
            add(w)
    return out[:audit_sft.SEARCH_WORDS[1]] if len(out) >= audit_sft.SEARCH_WORDS[0] else []


def candidate_label(s, city, cat, keywords):
    """Il motivo del "no" per una candidata che non e' la sezione attesa, None se non e' sicuro che non risponda:
    un'altra sezione della stessa citta' che non tratta la categoria, la stessa categoria di un'altra citta', una pagina
    della guida generale (le domande nominano una citta'). Le sezioni del paese possono rispondere: niente etichetta."""
    if s.get("general_guide"):
        return "guida-generale"
    if s["city"] == city:
        return None if any(it.covers(cat, s["body"], kw) for kw in keywords) else "stesso-luogo"
    return "altra-citta" if s["city"] and s["category"] == cat else None


def task_rows(lang, n_rerank, n_words, rng, cache_dir, regions=None, cities_per_region=CITIES_PER_REGION):
    """(righe, attribuzioni) dei compiti del v12 in [lang], sulle citta' delle regioni di training_regions (o [regions]):
    circa [n_rerank] righe del riordino (prompt RERANK_PROMPT di eval_retrieval.py con una candidata, risposta "sì" per la
    sezione attesa con la risposta nel testo mostrato, "no" per le negative difficili di candidate_label) e fino a
    [n_words] righe delle parole di ricerca (SEARCH_WORDS_PROMPT) per le domande nell'altra lingua con la sezione attesa
    tra le candidate. Le righe hanno "task" ("riordino" o "parole-di-ricerca") e categoria RIORDINO o PAROLE_DI_RICERCA."""
    started = time.monotonic()
    L, other = gs.LANGS[lang], gs.LANGS[gs.OTHER[lang]]
    answer_for = gs.make_answer_for(L)
    keywords = [gs.all_keywords(L), gs.all_keywords(other)]
    conf, cache = er.LANGS[lang], Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(er.fetch(er.MANIFEST_URL))
    by_id = {r["regionId"]: r for r in manifest["regions"]}
    guides_db = er.download(manifest[conf["guides"]]["fileXz"]["url"], cache / f"guides{conf['suffix']}.db")
    guide_sources = sources_of(guides_db, "guide_sections", "regionId")
    pool = list(regions) if regions else training_regions(manifest, lang)
    rng.shuffle(pool)
    yes, no = er.RERANK_ANSWERS[lang]
    rows, attribution, seen, stats = [], [], set(), Counter()
    for region in pool:
        if stats["riordino"] >= n_rerank and stats["parole-di-ricerca"] >= n_words:
            break
        cities_db = er.download(by_id[region][conf["cities"]]["fileXz"]["url"], cache / f"{region}.cities{conf['suffix']}.db")
        city_rows = er.city_rows_of(cache, region, True, lang)
        city_sources = sources_of(cities_db, "city_sections", "city")
        cats_of = {}
        for city, cat, *_ in city_rows:
            if cat in CATEGORIES and len(er.spoken_city_name(city)) >= er.MIN_CITY_NAME_CHARS:
                cats_of.setdefault(city, set()).add(cat)
        db = er.build_db(guides_db, region, city_rows)
        cities = sorted({r[0] for r in city_rows})
        stats["regioni"] += 1
        for city in rng.sample(sorted(cats_of), min(len(cats_of), cities_per_region)):
            name = er.spoken_city_name(city)
            for cat in sorted(cats_of[city]):
                kind, template, q_template, counterpart = task_question(lang, cat, rng)
                q = q_template.format(c=name)
                if q in seen:
                    continue
                seen.add(q)
                stats["domande", kind] += 1
                candidates, stems = er.search(db, region, q, cities, lang, word_lists=True, pool=er.RERANK["candidates"],
                                              general_extra=er.RERANK["general"])
                # Con una citta' nominata la ricerca non porta le altre: la stessa categoria di un'altra citta' si aggiunge
                if others := [r for r in city_rows if r[1] == cat and r[0] != city]:
                    o = rng.choice(others)
                    candidates.append({"city": o[0], "region": region, "category": cat, "title": o[2], "body": o[3],
                                       "general_guide": False})
                labelled = []
                for s in candidates:
                    text = er.relevant_paragraphs(s["body"], stems, rng.choice(TASK_SNIPPETS))
                    if s["city"] == city and s["category"] == cat and not s.get("general_guide"):
                        answer = answer_for(text, s["body"], q, cat, name)
                        reason = "sezione-attesa" if len(answer) >= MIN_ANSWER and answer_has_needs(template, answer) else None
                        stats["sezione attesa senza risposta nel testo"] += reason is None
                    else:
                        reason = candidate_label(s, city, cat, keywords)
                        stats["candidata senza etichetta"] += reason is None
                    if reason:
                        labelled.append((reason, s, text))
                rng.shuffle(labelled)
                firsts, rest, reasons = [], [], set()  # i "no": prima uno per motivo, poi gli altri
                for x in labelled:
                    if x[0] != "sezione-attesa":
                        (rest if x[0] in reasons else firsts).append(x)
                        reasons.add(x[0])
                picked = [x for x in labelled if x[0] == "sezione-attesa"] + (firsts + rest)[:TASK_NO_PER_QUESTION]
                if stats["riordino"] < n_rerank:
                    for reason, s, text in picked:
                        lics = licensed([s], city_sources, guide_sources, lang)
                        if lics is None:
                            stats["fonte-sconosciuta"] += 1
                            continue
                        prompt = er.RERANK_PROMPT[lang].format(q=q, place=er.rerank_place(s), text=text)
                        positive = reason == "sezione-attesa"
                        rows.append({"messages": [{"role": "user", "content": prompt},
                                                  {"role": "assistant", "content": yes if positive else no}],
                                     "kind": "pos" if positive else "neg", "region": f"citta:{city}", "category": "RIORDINO",
                                     "task": "riordino", "translated": bool(lics[0][2])})
                        attribution += attribution_of(lics, by_id)
                        stats["riordino"] += 1
                        stats["riordino", reason] += 1
                expected = next((s for reason, s, _ in labelled if reason == "sezione-attesa"), None)
                if counterpart and expected and stats["parole-di-ricerca"] < n_words:
                    words = search_words(counterpart, expected["body"], gs.all_keywords(L)[cat], set(L["stopwords"]) | SEARCH_STOPWORDS[lang], name)
                    lics = licensed([expected], city_sources, guide_sources, lang)
                    if not words or lics is None:
                        stats["parole di ricerca: meno di 3 parole o fonte sconosciuta"] += 1
                    else:
                        rows.append({"messages": [{"role": "user", "content": er.SEARCH_WORDS_PROMPT[lang].format(q=q)},
                                                  {"role": "assistant", "content": ", ".join(words)}],
                                     "kind": "pos", "region": f"citta:{city}", "category": "PAROLE_DI_RICERCA",
                                     "task": "parole-di-ricerca", "translated": bool(lics[0][2])})
                        attribution += attribution_of(lics, by_id)
                        stats["parole-di-ricerca"] += 1
        db.close()
    print(f"compiti del v12 {lang}: {stats['regioni']} regioni, domande "
          + ", ".join(f"{k} {stats['domande', k]}" for k in QUESTION_KINDS)
          + f"; riordino {stats['riordino']} (" + ", ".join(f"{r} {stats['riordino', r]}" for r in
                                                           ("sezione-attesa", "stesso-luogo", "altra-citta", "guida-generale"))
          + f"), parole di ricerca {stats['parole-di-ricerca']}; scartate: "
          + ", ".join(f"{k} {stats[k]}" for k in ("sezione attesa senza risposta nel testo", "candidata senza etichetta",
                                                  "fonte-sconosciuta", "parole di ricerca: meno di 3 parole o fonte sconosciuta"))
          + f"; {time.monotonic() - started:.0f} s")
    return rows, list(dict.fromkeys(attribution))


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
