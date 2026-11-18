"""Termini locali delle guide (piatti, bevande, feste, usanze, abiti, strumenti): per ogni regione, le voci di Wikipedia
del suo paese che la guida nomina, con l'introduzione della voce come spiegazione. L'app li rende toccabili nella guida e
li usa nell'assistente; la spiegazione viene sempre da una voce di Wikipedia, mai da un modello.

Da dove vengono (piano dei termini locali, 2026-11-12):
1. il paese della regione: elemento Wikidata della pagina di Wikivoyage EN, o il suo paese (P17) se la pagina e' una
   regione di un paese;
2. le categorie di Wikipedia del paese su questi argomenti: quelle che su Wikidata combinano (P971) il paese con un
   argomento di TOPICS, piu' la categoria principale (P910) della cucina nazionale; nomi ricavati da Wikidata, nessun
   elenco a mano;
3. le voci delle categorie e delle loro sottocategorie dirette, con i redirect come altri nomi; si scartano le pagine
   di disambiguazione, gli elenchi, le voci su persone, aziende, locali e opere (P31 in EXCLUDED_TYPES) e la voce
   principale della categoria (la cucina nazionale stessa);
4. un termine resta se uno dei suoi nomi compare come parola intera nel testo della guida (senza accenti ne' maiuscole).
   Un nome ricavato togliendo la parentesi ("Chupe (food)" -> "Chupe") vale solo se non e' il titolo di un'altra voce.

Seconda fonte (grassetti e corsivi, dopo la prima, stessa riga del JSONL):
5. candidati: testi in grassetto o corsivo del wikitext nelle sezioni di SECTION_STARTS (mangiare, bere, eventi e feste,
   acquisti, usanze), tolti prima template, commenti e note e saltate le sottosezioni di soldi (MONEY_HEADING). Rumore
   scartato senza rete: testi con due punti, punto, "!?;", barra o cifre (frasi, avvertenze come "Buyer beware:", importi),
   sigle in maiuscolo fino a 5 lettere (valute: PEN), meno di 4 lettere o piu' di 40 caratteri o 4 parole;
6. voce: titolo esatto o redirect su Wikipedia della lingua, senza disambiguazioni ed elenchi;
7. paese: su Wikidata la voce ha come paese d'origine (P495) il paese della regione o, senza P495, come paese (P17) e non e'
   un luogo (senza coordinate P625 ne' P131); mai il paese stesso, la cucina nazionale o i tipi di EXCLUDED_TYPES. Se ha tipi
   (P31), almeno uno e' sottoclasse di un argomento di TOPICS (scarta valute, sport, fumetti, epoche, scritture); senza tipo
   resta (Khink'ali, Tequila);
8. unione: stesso titolo = stesso termine, vince la prima fonte; il nome e' quello della guida e il titolo e' un alias.
   La seconda fonte smette quando resta il FIRST_SOURCE_RESERVE del tempo, e se fallisce o non finisce la regione esce con
   i soli termini della prima.

Wikipedia e' CC BY-SA 4.0 come Wikivoyage, Wikidata CC0: titolo, indirizzo e revisione della voce bastano per citarla.

Uso: local_terms.py <it|en> <regioni.tsv> <output.jsonl> [--published <guides.db pubblicato>]
  regioni.tsv: "regionId<TAB>titolo della pagina di Wikivoyage EN<TAB>file con il testo della guida" per riga.
  output.jsonl: una riga per regione letta, {"regionId", "terms": [{"term", "aliases", "title", "summary", "url",
  "revision"}]}, anche con "terms" vuoto.
  --published: le regioni si leggono nell'ordine di reading_order, dalla tabella guide_terms_read del guides.db
  pubblicato (data dell'ultima lettura di ogni regione, scritta da generateGuides).
Mai fatale: le regioni non lette (errore di rete, tempo esaurito, paese non trovato) mancano dal file e generateGuides
tiene i loro termini pubblicati.
"""
import json
import re
import sqlite3
import sys
import time
import unicodedata
import urllib.parse
from contextlib import closing
from pathlib import Path

import city_population
from untrusted_text import strip_markup

# Argomenti delle categorie da tenere, come elementi Wikidata esatti: con le sottoclassi (P279*) entrerebbero anche
# cantanti (tipi di voce come strumenti), medaglie (come abiti) e festival del cinema.
TOPICS = {
    "Q2095": "cibo", "Q40050": "bevanda", "Q746549": "piatto", "Q182940": "dolce", "Q154": "bevanda alcolica",
    "Q41415": "zuppa", "Q7802": "pane", "Q2596997": "condimento", "Q178780": "liquore", "Q56139": "distillato",
    "Q5159627": "dolciumi", "Q1628963": "antipasto", "Q27994917": "piatto di pollo", "Q11559422": "piatto di pesce",
    "Q28100194": "piatto di uova", "Q84431525": "piatto di verdure", "Q10943": "formaggio",
    "Q132241": "festa", "Q130748394": "festa religiosa", "Q13930359": "festa popolare", "Q1197685": "festa nazionale",
    "Q1445650": "festività", "Q375011": "festività religiosa", "Q2559933": "festività buddhista", "Q1046763": "matsuri",
    "Q16023945": "feria", "Q4618": "carnevale", "Q82821": "tradizione", "Q3742928": "cultura popolare",
    "Q11460": "abbigliamento", "Q3172759": "costume tradizionale", "Q80151": "copricapo", "Q34379": "strumento musicale",
}
# Tipi (P31) delle voci da scartare: persone, aziende, locali, opere, luoghi.
EXCLUDED_TYPES = {
    "Q5", "Q4830453", "Q6881511", "Q783794", "Q43229", "Q11707", "Q11424", "Q571", "Q47461344", "Q7725634", "Q482994",
    "Q515", "Q486972", "Q3624078", "Q6256", "Q167270", "Q431289", "Q215380", "Q1656682", "Q2416217",
}
NATIONAL_CUISINE = "Q1968435"
LIST_TITLE = re.compile(r"^(list of|lists of|lista d|elenco d|glossar)", re.I)
PARENTHESIS = re.compile(r"\s*\([^)]*\)\s*$")
NAMESPACE_LINK = re.compile(r"\[\[[A-Za-z]+:[^\]]*\]\]")
MIN_NAME = 4
# Sezioni di Wikivoyage (titolo senza accenti ne' maiuscole, inizio) da cui la seconda fonte prende grassetti e corsivi:
# mangiare, bere, eventi e feste, acquisti, usanze (EN e IT).
SECTION_STARTS = ("eat", "drink", "events", "festivals", "buy", "respect", "a tavola", "bevande", "eventi", "valuta e acquisti",
                  "acquisti", "comprare", "rispettare")
# Sottosezioni di soldi, mance e prezzi dentro quelle: valute e importi, nessun termine locale.
MONEY_HEADING = re.compile(r"^(money|tipping|costs?|currency|moneta|valuta|mance|costi|prezzi)\b")
# Quota del tempo (MAX_SECONDS) riservata alla prima fonte: la seconda smette di cercare quando ne resta questa.
FIRST_SOURCE_RESERVE = 0.25
SUMMARY_CHARS = 600
EXTRACT_BATCH = 20
MAX_SECONDS = 20 * 60
SPARQL = "https://query.wikidata.org/sparql?format=json&query="


def fold(text):
    """Testo senza accenti, in minuscolo, con ogni segno che non e' lettera o cifra come spazio, circondato da spazi."""
    plain = "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c)).lower()
    plain = plain.replace("ł", "l").replace("ø", "o").replace("đ", "d").replace("ð", "d").replace("ı", "i").replace("ß", "ss")
    return " " + " ".join(re.sub(r"[\W_]+", " ", plain).split()) + " "


def names_of(title, redirects, existing):
    """Nomi con cui una voce si cerca nella guida: titolo e redirect, anche senza la parentesi finale se quel nome non
    e' il titolo di un'altra voce ([existing]: titoli di voci vere, redirect esclusi). Troppo corti, scartati."""
    out = []
    for name in [title, *redirects]:
        bare = PARENTHESIS.sub("", name)
        if bare != name and bare in existing:
            continue
        if len(fold(bare).strip()) >= MIN_NAME and bare not in out:
            out.append(bare)
    return out


def matched(text, names):
    """I nomi che compaiono come parola intera nel testo (gia' passato da fold)."""
    return [n for n in names if fold(n) in text]


def cut_summary(text, limit=SUMMARY_CHARS):
    """Introduzione tagliata all'ultima frase intera entro [limit] caratteri; almeno la prima frase."""
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    ends = [m.end() for m in re.finditer(r"[.!?](?=\s+[A-ZÀ-ÖØ-Þ0-9«\"(])", text)]
    within = [e for e in ends if e <= limit]
    if within:
        return text[:within[-1]]
    return text[:ends[0]] if ends else text[:limit].rsplit(" ", 1)[0] + "…"


def _expired(deadline):
    return time.monotonic() > deadline


def _sparql(query):
    return city_population._get(SPARQL + urllib.parse.quote(query))["results"]["bindings"]


def countries(items):
    """{elemento della pagina Wikivoyage: elemento del paese}: se stesso se ha un codice ISO (P297), altrimenti P17."""
    values = " ".join(f"wd:{q}" for q in sorted(set(items)))
    rows = _sparql(f"SELECT ?r ?iso ?c WHERE {{ VALUES ?r {{ {values} }} OPTIONAL {{ ?r wdt:P297 ?iso }} OPTIONAL {{ ?r wdt:P17 ?c }} }}")
    out = {}
    for r in rows:
        item = r["r"]["value"].rsplit("/", 1)[-1]
        if "iso" in r:
            out[item] = item
        elif "c" in r and item not in out:
            out[item] = r["c"]["value"].rsplit("/", 1)[-1]
    return out


def categories(country, lang):
    """(categorie di Wikipedia [lang] del paese sugli argomenti di TOPICS, voci principali da scartare). Due query: unite
    in una (UNION) Wikidata Query Service va oltre il minuto e chiude la connessione."""
    topics = " ".join(f"wd:{q}" for q in TOPICS)
    wiki = f"<https://{lang}.wikipedia.org/>"
    rows = _sparql(f"""SELECT DISTINCT ?page WHERE {{
  VALUES ?topic {{ {topics} }} ?cat wdt:P971 ?topic . ?cat wdt:P971 wd:{country} .
  ?page schema:about ?cat ; schema:isPartOf {wiki} .
}}""") + _sparql(f"""SELECT DISTINCT ?page ?main WHERE {{
  ?cuisine wdt:P31 wd:{NATIONAL_CUISINE} ; wdt:P17 wd:{country} ; wdt:P910 ?cat .
  ?page schema:about ?cat ; schema:isPartOf {wiki} .
  OPTIONAL {{ ?mainPage schema:about ?cuisine ; schema:isPartOf {wiki} ; schema:name ?main . }}
}}""")
    cats = sorted({urllib.parse.unquote(r["page"]["value"].rsplit("/wiki/", 1)[-1]).replace("_", " ") for r in rows})
    mains = {r["main"]["value"] for r in rows if "main" in r}
    return cats, mains


def _api(lang, params):
    params = {"format": "json", "formatversion": "2", **params}
    return city_population._get(f"https://{lang}.wikipedia.org/w/api.php?" + urllib.parse.urlencode(params))


def members(cats, lang, deadline):
    """Titoli delle voci (namespace 0) delle categorie e delle loro sottocategorie dirette."""
    pages, subcats, seen = set(), [], set(cats)
    for depth, todo in enumerate((list(cats), subcats)):
        for cat in todo:
            if _expired(deadline):
                return pages
            params = {"action": "query", "list": "categorymembers", "cmtitle": cat, "cmtype": "page|subcat", "cmlimit": "500"}
            while True:
                data = _api(lang, params)
                for m in data.get("query", {}).get("categorymembers", []):
                    if m["ns"] == 0:
                        pages.add(m["title"])
                    elif m["ns"] == 14 and depth == 0 and m["title"] not in seen:
                        seen.add(m["title"])
                        subcats.append(m["title"])
                if "continue" not in data:
                    break
                params.update(data["continue"])
            time.sleep(0.1)
    return pages


def page_info(titles, lang, deadline):
    """{titolo: {"redirects", "item", "revision", "disambiguation"}} delle voci (le pagine di redirect seguite)."""
    out = {}
    titles = sorted(titles)
    for i in range(0, len(titles), city_population.BATCH):
        if _expired(deadline):
            break
        params = {"action": "query", "titles": "|".join(titles[i:i + city_population.BATCH]), "redirects": "1",
                  "prop": "redirects|pageprops|info", "rdnamespace": "0", "rdlimit": "max", "ppprop": "wikibase_item|disambiguation"}
        while True:
            data = _api(lang, params)
            for page in data.get("query", {}).get("pages", []):
                if page.get("missing") or page.get("ns") != 0:
                    continue
                info = out.setdefault(page["title"], {"redirects": [], "item": None, "revision": page.get("lastrevid"), "disambiguation": False})
                info["redirects"] += [r["title"] for r in page.get("redirects", [])]
                props = page.get("pageprops", {})
                info["item"] = info["item"] or props.get("wikibase_item")
                info["disambiguation"] = info["disambiguation"] or "disambiguation" in props
            if "continue" not in data:
                break
            params.update(data["continue"])
        time.sleep(0.1)
    return out


def existing_articles(titles, lang, deadline):
    """Quali [titles] sono voci vere (non redirect, non mancanti)."""
    out, titles = set(), sorted(titles)
    for i in range(0, len(titles), city_population.BATCH):
        if _expired(deadline):
            break
        data = _api(lang, {"action": "query", "titles": "|".join(titles[i:i + city_population.BATCH])})
        out |= {p["title"] for p in data.get("query", {}).get("pages", []) if not p.get("missing") and "redirect" not in p}
        time.sleep(0.1)
    return out


def excluded_items(items, deadline):
    """Elementi Wikidata con un tipo (P31) di EXCLUDED_TYPES."""
    out, items = set(), sorted(items)
    for i in range(0, len(items), city_population.BATCH):
        if _expired(deadline):
            break
        url = ("https://www.wikidata.org/w/api.php?action=wbgetentities&props=claims&format=json&ids="
               + "|".join(items[i:i + city_population.BATCH]))
        for q, entity in city_population._get(url).get("entities", {}).items():
            types = {c["mainsnak"].get("datavalue", {}).get("value", {}).get("id") for c in entity.get("claims", {}).get("P31", [])}
            if types & EXCLUDED_TYPES:
                out.add(q)
        time.sleep(0.2)
    return out


def summaries(titles, lang, deadline):
    """{titolo: introduzione in testo semplice} delle voci."""
    out, titles = {}, sorted(titles)
    for i in range(0, len(titles), EXTRACT_BATCH):
        if _expired(deadline):
            break
        params = {"action": "query", "prop": "extracts", "exintro": "1", "explaintext": "1", "exlimit": "max",
                  "titles": "|".join(titles[i:i + EXTRACT_BATCH])}
        while True:
            data = _api(lang, params)
            for page in data.get("query", {}).get("pages", []):
                if extract := page.get("extract", "").strip():
                    # wikitext rimasto nell'estratto (categorie di una voce rotta): "[[Categoria:Cucina ...]]"
                    out[page["title"]] = cut_summary(strip_markup(NAMESPACE_LINK.sub("", extract)).strip())
            if "continue" not in data:
                break
            params.update(data["continue"])
        time.sleep(0.1)
    return out


def country_terms(country, lang, deadline):
    """[(titolo, nomi, revisione)] dei termini candidati del paese, prima del confronto con le guide."""
    cats, mains = categories(country, lang)
    titles = {t for t in members(cats, lang, deadline) if not LIST_TITLE.match(t) and t not in mains}
    info = page_info(titles, lang, deadline)
    info = {t: i for t, i in info.items() if not i["disambiguation"] and t not in mains}
    excluded = excluded_items({i["item"] for i in info.values() if i["item"]}, deadline)
    bare = {PARENTHESIS.sub("", n) for t, i in info.items() for n in [t, *i["redirects"]]}
    existing = existing_articles({b for b in bare if b not in info}, lang, deadline) | set(info)
    return [(t, names_of(t, i["redirects"], existing), i["revision"]) for t, i in sorted(info.items()) if i["item"] not in excluded]


def markup_candidates(wikitext):
    """Candidati della seconda fonte: testi in grassetto ('''x''') o corsivo (''x'') del wikitext nelle sezioni
    SECTION_STARTS (e nelle loro sottosezioni, tranne quelle di MONEY_HEADING), prima della pulizia. Template (elenchi di
    negozi e locali), commenti e note sono tolti prima di cercare i segni. Scartati senza rete (NOISE nel docstring del
    modulo): frasi, avvertenze ("Buyer beware:"), cifre e valute, sigle, testi troppo corti o lunghi."""
    out, active, skip = [], None, None
    text = re.sub(r"<!--.*?-->|<ref[^>]*?/>|<ref\b.*?</ref>", "", wikitext, flags=re.S)
    while (flat := re.sub(r"\{\{[^{}]*\}\}", "", text)) != text:
        text = flat
    for line in text.splitlines():
        if heading := re.match(r"^(={2,6})\s*(.*?)\s*\1\s*$", line):
            level, title = len(heading.group(1)), fold(re.sub(r"'{2,}", "", heading.group(2))).strip()
            if skip is not None and level <= skip:
                skip = None
            if active is not None and level <= active:
                active = None
            if active is None and title.startswith(SECTION_STARTS):
                active = level
            elif active is not None and skip is None and MONEY_HEADING.match(title):
                skip = level
            continue
        if active is None or skip is not None:
            continue
        line = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", line)
        out += re.findall(r"'''(?!')(.+?)'''", line)
        out += re.findall(r"''(.+?)''", line.replace("'''", ""))
    cleaned, seen = [], set()
    for span in out:
        span = " ".join(re.sub(r"'+|<[^>]*>", "", span).split()).strip(" .,;()\"«»")
        key = fold(span).strip()
        if key in seen or not MIN_NAME <= len(key) <= 40 or len(key.split()) > 4:
            continue
        if re.search(r"[:.!?;/\d]", span) or (span.isupper() and len(span) <= 5):
            continue
        seen.add(key)
        cleaned.append(span)
    return cleaned


def _claims_of(items, deadline, cache):
    """{elemento: {"types", "origin", "country", "place"}} da Wikidata: tipi (P31), paesi d'origine (P495), paesi (P17) e
    se l'elemento e' un luogo (coordinate P625 o ente territoriale P131). [cache] tiene quelli gia' letti."""
    todo = sorted(set(items) - set(cache))
    for i in range(0, len(todo), city_population.BATCH):
        if _expired(deadline):
            break
        url = ("https://www.wikidata.org/w/api.php?action=wbgetentities&props=claims&format=json&ids="
               + "|".join(todo[i:i + city_population.BATCH]))
        for q, entity in city_population._get(url).get("entities", {}).items():
            claims = entity.get("claims", {})
            ids = lambda prop: {c["mainsnak"].get("datavalue", {}).get("value", {}).get("id") for c in claims.get(prop, [])}
            cache[q] = {"types": ids("P31"), "origin": ids("P495"), "country": ids("P17"), "place": "P625" in claims or "P131" in claims}
        time.sleep(0.2)
    return cache


def tied_to(claims, item, country):
    """La voce e' del paese: paese d'origine (P495) uguale al paese oppure, se la voce non ha un paese d'origine, paese
    (P17) uguale e non un luogo (feste e usanze hanno P17; citta', siti e regioni si riconoscono dalle coordinate o da
    P131; con un P17 su piu' paesi, come "fish and chips", senza origine non si sa). Mai il paese stesso, la sua cucina
    nazionale ne' un tipo di EXCLUDED_TYPES (persone, aziende, locali, opere)."""
    if item == country or claims["types"] & (EXCLUDED_TYPES | {NATIONAL_CUISINE}):
        return False
    return country in claims["origin"] or (not claims["origin"] and country in claims["country"] and not claims["place"])


def topic_types(types, cache):
    """Quali tipi (P31) di [types] sono sottoclasse (P279*) di un argomento di TOPICS: cibo, bevande, feste, abiti, strumenti.
    Valute, sport, fumetti, epoche e scritture non lo sono. [cache]: {tipo: bool} dei tipi gia' chiesti."""
    todo = sorted(set(types) - set(cache))
    if todo:
        topics = " ".join(f"wd:{q}" for q in TOPICS)
        rows = _sparql(f"SELECT DISTINCT ?t WHERE {{ VALUES ?t {{ {' '.join('wd:' + q for q in todo)} }} VALUES ?topic {{ {topics} }} ?t wdt:P279* ?topic }}")
        under = {r["t"]["value"].rsplit("/", 1)[-1] for r in rows}
        cache.update({q: q in under for q in todo})
    return {q for q in types if cache[q]}


def resolve(candidates, lang, deadline, cache):
    """{candidato: (titolo della voce, elemento Wikidata, revisione)} per i candidati che sono voci di Wikipedia [lang]
    (titolo esatto o redirect), senza le pagine di disambiguazione e gli elenchi. [cache]: candidati gia' cercati."""
    todo = sorted(set(candidates) - set(cache))
    for i in range(0, len(todo), city_population.BATCH):
        if _expired(deadline):
            break
        chunk = todo[i:i + city_population.BATCH]
        params = {"action": "query", "titles": "|".join(chunk), "redirects": "1", "prop": "pageprops|info",
                  "ppprop": "wikibase_item|disambiguation"}
        alias, pages = {}, {}
        while True:
            data = _api(lang, params)
            query = data.get("query", {})
            for kind in ("normalized", "redirects"):
                alias.update({n["from"]: n["to"] for n in query.get(kind, [])})
            for page in query.get("pages", []):
                info = pages.setdefault(page["title"], {"ok": not page.get("missing") and page.get("ns") == 0, "item": None,
                                                        "revision": page.get("lastrevid"), "disambiguation": False})
                props = page.get("pageprops", {})
                info["item"] = info["item"] or props.get("wikibase_item")
                info["disambiguation"] = info["disambiguation"] or "disambiguation" in props
            if "continue" not in data:
                break
            params.update(data["continue"])
        for candidate in chunk:
            title = candidate
            for _ in range(3):
                title = alias.get(title, title)
            info = pages.get(title)
            usable = info and info["ok"] and info["item"] and not info["disambiguation"] and not LIST_TITLE.match(title)
            cache[candidate] = (title, info["item"], info["revision"]) if usable else None
        time.sleep(0.1)
    return {c: cache[c] for c in candidates if cache.get(c)}


def second_terms(wikitext, country, lang, deadline, cache):
    """[(titolo, nomi, revisione)] della seconda fonte: voci di Wikipedia dei grassetti e corsivi della guida legate al
    paese. Il nome e' quello della guida, il titolo della voce e' un alias. Vuoto se [deadline] scade prima di finire."""
    found = resolve(markup_candidates(wikitext), lang, deadline, cache["pages"])
    claims = _claims_of({item for _, item, _ in found.values()}, deadline, cache["claims"])
    if _expired(deadline):
        return []
    tied = {item for _, item, _ in found.values() if item in claims and tied_to(claims[item], item, country)}
    topics = topic_types({q for item in tied for q in claims[item]["types"]}, cache["types"])
    out = []
    for candidate, (title, item, revision) in sorted(found.items()):
        # senza tipo (P31) la voce resta (Khink'ali, Tequila); con tipi, almeno uno deve essere di un argomento di TOPICS
        if item in tied and (not claims[item]["types"] or claims[item]["types"] & topics):
            out.append((title, [candidate] + ([title] if fold(title) != fold(candidate) else []), revision))
    return out


def reading_order(region_ids, published):
    """[region_ids] nell'ordine di lettura: prima le regioni mai lette senza termini pubblicati, poi quelle con termini ma
    senza data (pubblicate prima di guide_terms_read), poi dalla lettura piu' vecchia. Il tempo di una run (MAX_SECONDS)
    non basta per tutte: in ordine alfabetico le ultime non sarebbero mai lette."""
    key = {}
    if published and Path(published).is_file():
        with closing(sqlite3.connect(Path(published).resolve().as_uri() + "?mode=ro", uri=True)) as db:
            tables = {name for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
            if "guide_terms" in tables:
                key.update((region, "0") for (region,) in db.execute("SELECT DISTINCT regionId FROM guide_terms"))
            if "guide_terms_read" in tables:
                key.update(db.execute("SELECT regionId, readAt FROM guide_terms_read"))
    return sorted(region_ids, key=lambda region: (key.get(region, ""), region))


def merge(first, second):
    """Termini della prima fonte e, dopo, quelli della seconda la cui voce non c'e' gia' (stesso titolo = stesso termine)."""
    seen = {title for title, _, _ in first}
    return first + [t for t in second if t[0] not in seen and not seen.add(t[0])]


def collect(regions, lang, max_seconds=MAX_SECONDS, order=None, second_source=True):
    """Righe del JSONL per le regioni [regions] ({regionId: (titolo Wikivoyage EN, testo della guida)}), lette
    nell'ordine [order] (reading_order; di default alfabetico) finche' c'e' tempo. La seconda fonte (se [second_source])
    gira dopo la prima, finche' resta piu' del FIRST_SOURCE_RESERVE del tempo; se non finisce o fallisce, la regione
    esce con i soli termini della prima."""
    deadline = time.monotonic() + max_seconds
    second_deadline = deadline - max_seconds * FIRST_SOURCE_RESERVE
    second_cache = {"pages": {}, "claims": {}, "types": {}}
    item_of = city_population.wikibase_items({title for title, _ in regions.values()}, "en", deadline)
    country_of = countries(item_of.values()) if item_of else {}
    candidates = {}
    rows = []
    for region_id in order or sorted(regions):
        voyage_title, text = regions[region_id]
        country = country_of.get(item_of.get(voyage_title))
        if not country or _expired(deadline):
            continue
        if country not in candidates:
            try:
                candidates[country] = country_terms(country, lang, deadline)
            except Exception as e:
                print(f"-- termini locali: paese {country} saltato ({e})", file=sys.stderr)
                candidates[country] = None
        if candidates[country] is None:
            continue
        folded = fold(text)
        # prima i nomi che la guida usa: il termine e' il primo, non il titolo (spesso il nome scientifico)
        found = [(t, hit + [n for n in names if n not in hit], rev)
                 for t, names, rev in candidates[country] if (hit := matched(folded, names))]
        if second_source and not _expired(second_deadline):
            try:
                found = merge(found, second_terms(text, country, lang, second_deadline, second_cache))
            except Exception as e:
                print(f"-- termini locali: grassetti e corsivi di {region_id} saltati ({e})", file=sys.stderr)
        texts = summaries({t for t, _, _ in found}, lang, deadline)
        if _expired(deadline):
            continue
        rows.append({"regionId": region_id, "terms": [
            {"term": names[0], "aliases": names[1:], "title": title, "summary": texts[title],
             "url": f"https://{lang}.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")), "revision": revision}
            for title, names, revision in found if title in texts]})
    return rows


def main(argv):
    published = None
    if len(argv) == 5 and argv[3] == "--published":
        published, argv = argv[4], argv[:3]
    if len(argv) != 3 or argv[0] not in ("it", "en"):
        print(__doc__, file=sys.stderr)
        return 2
    lang, regions_tsv, out = argv[0], Path(argv[1]), Path(argv[2])
    regions = {}
    for line in regions_tsv.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) >= 3 and parts[2] and Path(parts[2]).is_file():
            regions[parts[0]] = (parts[1], Path(parts[2]).read_text(encoding="utf-8"))
    try:
        rows = collect(regions, lang, order=reading_order(regions, published))
    except Exception as e:
        print(f"-- termini locali non disponibili ({e}): le guide restano senza", file=sys.stderr)
        rows = []
    out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    print(f"guide: {sum(len(r['terms']) for r in rows)} termini locali {lang}, {len(rows)}/{len(regions)} regioni lette")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
