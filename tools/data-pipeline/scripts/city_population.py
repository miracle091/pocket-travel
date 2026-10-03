"""Popolazione e capitale delle citta' per i JSONL di extract-cities-dump(-en).py: la scheda Citta' dell'app mostra le
citta' principali in ordine di popolazione e segna la capitale.

Fonti, nell'ordine:
1. il campo "Abitanti" del {{QuickbarCity}} della pagina di Wikivoyage IT (gia' nel dump, nessuna richiesta);
2. Wikidata, proprieta' P1082 (popolazione), dall'elemento collegato alla pagina di Wikivoyage (pageprops
   wikibase_item): e' il dato che le voci di Wikipedia mostrano nel riquadro informativo. Wikidata e' CC0.
Senza nessuna delle due la citta' resta senza popolazione e l'app ripiega sulla lunghezza della guida.
Capitale: la proprieta' P36 (capitale) dell'elemento Wikidata della regione (stato, o stato federato per le regioni che
lo sono, es. il Texas), risolta nel titolo della sua pagina di Wikivoyage nella stessa lingua.

Mai fatale: un errore di rete lascia le citta' interessate senza popolazione.
"""
import json
import re
import sys
import time
import urllib.parse
import urllib.request

UA = {"User-Agent": "pocket-travel-cities/1.0 (https://github.com/miracle091/pocket-travel)"}
ABITANTI = re.compile(r"^\|\s*Abitanti\s*=\s*([0-9][0-9.\s ]*)", re.M)
BATCH = 50  # massimo di titoli/id per richiesta delle API MediaWiki


def abitanti(text):
    """Popolazione dal campo "Abitanti = 360.462 <small>(2023)</small>" del QuickbarCity, o None."""
    m = ABITANTI.search(text[:6000])
    if not m:
        return None
    digits = re.sub(r"\D", "", m.group(1))
    return int(digits) if digits and int(digits) > 0 else None


def _get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # 429 o rete: si riprova con attesa crescente
            if attempt == 3:
                raise
            print(f"-- popolazione: {e}, riprovo", file=sys.stderr)
            time.sleep(5 * (attempt + 1))


def _latest_population(claims):
    """Valore P1082 preferito, altrimenti quello con la data piu' recente (qualificatore P585)."""
    best = None
    for c in claims.get("P1082", []):
        try:
            amount = int(float(c["mainsnak"]["datavalue"]["value"]["amount"]))
        except (KeyError, ValueError, TypeError):
            continue
        date = (c.get("qualifiers", {}).get("P585") or [{}])[0].get("datavalue", {}).get("value", {}).get("time", "")
        key = (c.get("rank") == "preferred", date)
        if best is None or key > best[0]:
            best = (key, amount)
    return best[1] if best else None


def wikidata_populations(titles, lang):
    """{titolo: popolazione} da Wikidata per le pagine di Wikivoyage [lang] in [titles]."""
    titles = sorted(set(titles))
    item_of = {}
    for i in range(0, len(titles), BATCH):
        chunk = titles[i:i + BATCH]
        url = (f"https://{lang}.wikivoyage.org/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item"
               f"&redirects=1&format=json&titles=" + urllib.parse.quote("|".join(chunk)))
        data = _get(url)["query"]
        alias = {n["to"]: n["from"] for k in ("normalized", "redirects") for n in data.get(k, [])}
        for page in data.get("pages", {}).values():
            if q := page.get("pageprops", {}).get("wikibase_item"):
                title = page["title"]
                while title in alias:
                    title = alias[title]
                item_of[title] = q
        time.sleep(0.2)
    ids = sorted(set(item_of.values()))
    pop_of_item = {}
    for i in range(0, len(ids), BATCH):
        url = ("https://www.wikidata.org/w/api.php?action=wbgetentities&props=claims&format=json&ids="
               + "|".join(ids[i:i + BATCH]))
        for q, entity in _get(url).get("entities", {}).items():
            if (pop := _latest_population(entity.get("claims", {}))) is not None:
                pop_of_item[q] = pop
        time.sleep(0.2)
    return {t: pop_of_item[q] for t, q in item_of.items() if q in pop_of_item}


def capital_titles(region_titles, lang):
    """{titolo della regione: nomi possibili della sua capitale (titolo su Wikivoyage [lang], etichetta)} da Wikidata (P36)."""
    titles = sorted(set(region_titles))
    item_of = {}
    for i in range(0, len(titles), BATCH):
        url = (f"https://{lang}.wikivoyage.org/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item"
               f"&redirects=1&format=json&titles=" + urllib.parse.quote("|".join(titles[i:i + BATCH])))
        data = _get(url)["query"]
        alias = {n["to"]: n["from"] for k in ("normalized", "redirects") for n in data.get(k, [])}
        for page in data.get("pages", {}).values():
            if q := page.get("pageprops", {}).get("wikibase_item"):
                title = page["title"]
                while title in alias:
                    title = alias[title]
                item_of[title] = q
        time.sleep(0.2)
    region_items = sorted(set(item_of.values()))
    capitals_of = {}
    for i in range(0, len(region_items), BATCH):
        url = ("https://www.wikidata.org/w/api.php?action=wbgetentities&props=claims&format=json&ids="
               + "|".join(region_items[i:i + BATCH]))
        for q, entity in _get(url).get("entities", {}).items():
            capitals_of[q] = _current_values(entity.get("claims", {}).get("P36", []))
        time.sleep(0.2)
    capital_items = sorted({c for cs in capitals_of.values() for c in cs})
    page_of = {}
    for i in range(0, len(capital_items), BATCH):
        url = (f"https://www.wikidata.org/w/api.php?action=wbgetentities&props=sitelinks|labels&sitefilter={lang}wikivoyage"
               f"&languages={lang}&format=json&ids=" + "|".join(capital_items[i:i + BATCH]))
        for q, entity in _get(url).get("entities", {}).items():
            # il titolo della pagina di Wikivoyage e il nome dell'elemento: la capitale del Giappone su Wikidata e' la
            # prefettura ("Prefettura di Tokyo"), la pagina della citta' su Wikivoyage e' "Tokyo"
            names = {link["title"]} if (link := entity.get("sitelinks", {}).get(f"{lang}wikivoyage")) else set()
            if label := entity.get("labels", {}).get(lang):
                names.add(label["value"])
            page_of[q] = names
        time.sleep(0.2)
    return {t: {n for c in capitals_of.get(q, []) for n in page_of.get(c, set())} for t, q in item_of.items()}


def _current_values(claims):
    """Valori attuali di una proprieta': quelli di rango preferito, altrimenti quelli senza data di fine (P582); la
    capitale dell'Italia su Wikidata elenca anche Torino e Firenze, con la data in cui hanno smesso di esserlo."""
    usable = [c for c in claims if c.get("rank") != "deprecated" and "datavalue" in c.get("mainsnak", {})]
    preferred = [c for c in usable if c.get("rank") == "preferred"]
    current = preferred or [c for c in usable if "P582" not in c.get("qualifiers", {})]
    return [c["mainsnak"]["datavalue"]["value"]["id"] for c in current]


def annotate(paths, lang, region_titles=None):
    """Aggiunge "population" a ogni riga dei JSONL [paths]: Abitanti (solo IT), poi Wikidata per le mancanti; con
    [region_titles] ({percorso: titolo della regione}) anche "capital" (true per la capitale della regione)."""
    rows = {p: [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()] for p in paths}
    capital_of = {}
    if region_titles:
        try:
            capital_of = capital_titles(region_titles.values(), lang)
        except Exception as e:
            print(f"-- capitali da Wikidata non disponibili ({e})", file=sys.stderr)
    for lines in rows.values():
        for r in lines:
            r["population"] = abitanti(r["text"]) if lang == "it" else None
    missing = {r["city"] for lines in rows.values() for r in lines if r["population"] is None}
    try:
        found = wikidata_populations(missing, lang)
    except Exception as e:
        print(f"-- popolazione da Wikidata non disponibile ({e}): le citta' senza Abitanti restano senza", file=sys.stderr)
        found = {}
    for path, lines in rows.items():
        for r in lines:
            if r["population"] is None:
                r["population"] = found.get(r["city"])
            r["capital"] = r["city"] in capital_of.get((region_titles or {}).get(path), set())
        path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in lines), encoding="utf-8")
    total = sum(len(lines) for lines in rows.values())
    with_pop = sum(r["population"] is not None for lines in rows.values() for r in lines)
    capitals = sum(r["capital"] for lines in rows.values() for r in lines)
    print(f"citta': popolazione per {with_pop}/{total} (Wikidata: {len(found)}), capitali {capitals}")
