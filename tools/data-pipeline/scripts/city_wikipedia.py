"""Sezioni Storia e Clima della voce di Wikipedia di ogni citta' per i JSONL di extract-cities-dump(-en).py: aggiunge
alle righe il campo "wikipedia" ({"title": voce, "text": wikitext delle sezioni}), che GenerateCities.kt pulisce con la
stessa cleanBody delle guide e salva come sezioni STORIA e CLIMA in cities.db.

La voce si trova dall'elemento Wikidata collegato alla pagina di Wikivoyage (city_population.wikibase_items) e dal
suo sitelink {lang}wiki; il testo viene dall'API di Wikipedia (wikitext delle voci a lotti): i dump completi di
Wikipedia (IT ~5 GB, EN ~24 GB) non stanno nei runner di CI, che girano questo script una volta per shard.
Wikipedia e' CC BY-SA 4.0 come Wikivoyage; la voce resta tra le fonti della guida nell'app.

Mai fatale: un errore di rete lascia le citta' senza Storia e Clima. Il tempo e' limitato a MAX_SECONDS per chiamata
(una per shard e lingua nel workflow): oltre, le voci rimaste non si scaricano e quelle citta' restano senza le due
sezioni fino alla generazione successiva, invece di consumare il budget di tempo dello shard (publish-regions.yml).
"""
import json
import re
import sys
import time
import urllib.parse

import city_population

# Titoli delle sezioni da tenere, per lingua: confrontati in minuscolo, a qualunque livello ("=== Clima ===" sta
# spesso sotto "Geografia fisica", "=== Climate ===" sotto "Geography").
SECTIONS = {"it": ("storia", "clima"), "en": ("history", "climate")}
HEADING = re.compile(r"^(={2,6})\s*(.+?)\s*\1\s*$", re.M)
# Voci intere nella risposta: con lotti piu' grandi le citta' maggiori (~250 KB di wikitext l'una) superano il limite
# di dimensione della risposta dell'API, che rimanda il resto con "continue".
CONTENT_BATCH = 20
# Tetto di tempo di annotate: in condizioni normali l'Italia in italiano (~2.700 citta') ne usa una piccola parte.
MAX_SECONDS = 20 * 60


def sections(text, names):
    """Wikitext delle sezioni [names] della voce, ognuna come "== Titolo ==" seguito dal suo testo (sottosezioni
    comprese, fino al titolo successivo dello stesso livello o superiore), nell'ordine di [names]. Se un titolo compare
    piu' volte vale quello di livello piu' alto (la Storia della citta', non quella di una chiesa piu' in basso)."""
    heads = [(m.start(), m.end(), len(m.group(1)), m.group(2)) for m in HEADING.finditer(text)]
    out = []
    for name in names:
        found = [(level, i) for i, (_, _, level, title) in enumerate(heads) if title.lower() == name]
        if not found:
            continue
        level, i = min(found)
        stop = next((h[0] for h in heads[i + 1:] if h[2] <= level), len(text))
        body = text[heads[i][1]:stop].strip()
        if body:
            out.append(f"== {heads[i][3]} ==\n{body}")
    return "\n".join(out)


def _expired(deadline, what, left):
    if deadline is not None and time.monotonic() > deadline:
        print(f"-- Wikipedia: tempo esaurito, {left} {what} non scaricati (restano senza Storia e Clima)", file=sys.stderr)
        return True
    return False


def wikipedia_titles(titles, lang, deadline=None):
    """{titolo Wikivoyage: titolo della voce di Wikipedia [lang]} dal sitelink dell'elemento Wikidata collegato. Dopo
    [deadline] (time.monotonic()) non inizia altri lotti."""
    item_of = city_population.wikibase_items(titles, lang, deadline)
    ids = sorted(set(item_of.values()))
    title_of_item = {}
    for i in range(0, len(ids), city_population.BATCH):
        if _expired(deadline, "sitelink", len(ids) - i):
            break
        url = (f"https://www.wikidata.org/w/api.php?action=wbgetentities&props=sitelinks&sitefilter={lang}wiki"
               "&format=json&ids=" + "|".join(ids[i:i + city_population.BATCH]))
        for q, entity in city_population._get(url).get("entities", {}).items():
            if link := entity.get("sitelinks", {}).get(f"{lang}wiki"):
                title_of_item[q] = link["title"]
        time.sleep(0.2)
    return {t: title_of_item[q] for t, q in item_of.items() if q in title_of_item}


def wikipedia_sections(titles, lang, deadline=None):
    """{titolo della voce: wikitext di Storia e Clima} per le voci di Wikipedia [lang] in [titles] che ne hanno; tiene
    solo le sezioni, non le voci intere. Dopo [deadline] (time.monotonic()) non inizia altri lotti."""
    titles = sorted(set(titles))
    out = {}
    for i in range(0, len(titles), CONTENT_BATCH):
        if _expired(deadline, "voci", len(titles) - i):
            break
        params = {"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main", "format": "json",
                  "formatversion": "2", "titles": "|".join(titles[i:i + CONTENT_BATCH])}
        while True:
            data = city_population._get(f"https://{lang}.wikipedia.org/w/api.php?" + urllib.parse.urlencode(params))
            for page in data.get("query", {}).get("pages", []):
                if revisions := page.get("revisions"):
                    if text := sections(revisions[0]["slots"]["main"]["content"], SECTIONS[lang]):
                        out[page["title"]] = text
            if "continue" not in data:
                break
            params.update(data["continue"])
        time.sleep(0.2)
    return out


def annotate(paths, lang, max_seconds=MAX_SECONDS):
    """Aggiunge "wikipedia" alle righe dei JSONL [paths] le cui citta' hanno una voce con Storia o Clima, entro
    [max_seconds] per le voci."""
    deadline = time.monotonic() + max_seconds
    rows = {p: [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()] for p in paths}
    try:
        title_of = wikipedia_titles({r["city"] for lines in rows.values() for r in lines}, lang, deadline)
        text_of = wikipedia_sections(title_of.values(), lang, deadline)
    except Exception as e:
        print(f"-- Wikipedia non disponibile ({e}): le citta' restano senza Storia e Clima", file=sys.stderr)
        title_of, text_of = {}, {}
    for path, lines in rows.items():
        for r in lines:
            if (title := title_of.get(r["city"])) in text_of:
                r["wikipedia"] = {"title": title, "text": text_of[title]}
        path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in lines), encoding="utf-8")
    total = sum(len(lines) for lines in rows.values())
    found = sum("wikipedia" in r for lines in rows.values() for r in lines)
    print(f"citta': Storia/Clima da Wikipedia per {found}/{total}")
