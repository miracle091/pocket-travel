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

Wikipedia e' CC BY-SA 4.0 come Wikivoyage, Wikidata CC0: titolo, indirizzo e revisione della voce bastano per citarla.

Uso: local_terms.py <it|en> <regioni.tsv> <output.jsonl>
  regioni.tsv: "regionId<TAB>titolo della pagina di Wikivoyage EN<TAB>file con il testo della guida" per riga.
  output.jsonl: una riga per regione letta, {"regionId", "terms": [{"term", "aliases", "title", "summary", "url",
  "revision"}]}, anche con "terms" vuoto.
Mai fatale: le regioni non lette (errore di rete, tempo esaurito, paese non trovato) mancano dal file e generateGuides
tiene i loro termini pubblicati.
"""
import json
import re
import sys
import time
import unicodedata
import urllib.parse
from pathlib import Path

import city_population

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
MIN_NAME = 4
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
                    out[page["title"]] = cut_summary(extract)
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


def collect(regions, lang, max_seconds=MAX_SECONDS):
    """Righe del JSONL per le regioni [regions] ({regionId: (titolo Wikivoyage EN, testo della guida)})."""
    deadline = time.monotonic() + max_seconds
    item_of = city_population.wikibase_items({title for title, _ in regions.values()}, "en", deadline)
    country_of = countries(item_of.values()) if item_of else {}
    candidates = {}
    rows = []
    for region_id, (voyage_title, text) in sorted(regions.items()):
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
        texts = summaries({t for t, _, _ in found}, lang, deadline)
        if _expired(deadline):
            continue
        rows.append({"regionId": region_id, "terms": [
            {"term": names[0], "aliases": names[1:], "title": title, "summary": texts[title],
             "url": f"https://{lang}.wikipedia.org/wiki/" + urllib.parse.quote(title.replace(" ", "_")), "revision": revision}
            for title, names, revision in found if title in texts]})
    return rows


def main(argv):
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
        rows = collect(regions, lang)
    except Exception as e:
        print(f"-- termini locali non disponibili ({e}): le guide restano senza", file=sys.stderr)
        rows = []
    out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    print(f"guide: {sum(len(r['terms']) for r in rows)} termini locali {lang}, {len(rows)}/{len(regions)} regioni lette")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
