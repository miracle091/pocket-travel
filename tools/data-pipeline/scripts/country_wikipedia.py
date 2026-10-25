"""Sezioni della voce di Wikipedia di ogni regione per le guide (build-guides.sh): Cucina, Tradizioni, Mezzi di
comunicazione, Telecomunicazioni, che generateGuides (--wikipedia, CountryWikipedia.kt) pulisce con la stessa cleanBody
e aggiunge alla guida nelle categorie CIBO_BEVANDE, USI_COSTUMI, VITA_QUOTIDIANA e CONNETTIVITA, con la voce tra le fonti
(Wikipedia e' CC BY-SA 4.0 come Wikivoyage). Quasi nessun paese ha su Wikipedia una voce a parte su questi temi; le
sezioni della voce del paese si'.

La voce si trova come per le citta' (city_wikipedia.py): elemento Wikidata della pagina di Wikivoyage EN della regione
(il titolo di regions.sh) e suo sitelink {lang}wiki; il testo dall'API di Wikipedia.

Uso: country_wikipedia.py <it|en> <regioni.tsv> <output.jsonl>
  regioni.tsv: "regionId<TAB>titolo della pagina di Wikivoyage EN" per riga.
  output.jsonl: {"regionId", "title": voce, "text": wikitext delle sezioni, anche vuoto} per ogni regione di cui si e'
  letta la voce. Le regioni che mancano (senza voce, errore di rete, tempo esaurito) tengono in generateGuides le
  sezioni di Wikipedia gia' pubblicate. Mai fatale: con un errore l'output e' vuoto e tutte le regioni le tengono.
"""
import json
import sys
import time
from pathlib import Path

import city_wikipedia

# Titoli delle sezioni, confrontati in minuscolo a qualunque livello: le stesse chiavi di wikipediaHeadingToCategory in
# CountryWikipedia.kt, che le mette nelle categorie della guida. Contati sulle voci dei paesi del catalogo (ottobre
# 2026): "Mezzi di comunicazione" e' raro, "Media" piu' frequente; "Cultura" e "Societa'" restano fuori (arte, scienza,
# demografia).
SECTIONS = {
    "it": ("gastronomia", "cucina", "tradizioni", "usi e costumi", "tradizioni e folclore", "folclore e tradizioni",
           "festività", "festività nazionali", "mezzi di comunicazione", "mezzi di comunicazione di massa", "media",
           "telecomunicazioni", "comunicazioni", "internet"),
    "en": ("cuisine", "customs", "traditions", "etiquette", "festivals", "holidays", "public holidays",
           "holidays and festivals", "media", "mass media", "telecommunications", "telecommunication", "communications",
           "internet"),
}
# Tetto di tempo: in condizioni normali le circa 350 voci ne usano una piccola parte.
MAX_SECONDS = 10 * 60


def collect(regions, lang, max_seconds=MAX_SECONDS):
    """[{"regionId", "title", "text"}] per le regioni [regions] ({regionId: titolo Wikivoyage EN}) di cui si legge la voce."""
    deadline = time.monotonic() + max_seconds
    title_of = city_wikipedia.wikipedia_titles(set(regions.values()), lang, deadline, voyage_lang="en")
    text_of = city_wikipedia.wikipedia_sections(title_of.values(), lang, deadline, names=SECTIONS[lang], keep_empty=True)
    rows = []
    for region_id, voyage_title in regions.items():
        title = title_of.get(voyage_title)
        if title in text_of:
            rows.append({"regionId": region_id, "title": title, "text": text_of[title]})
    return rows


def main(argv):
    if len(argv) != 3 or argv[0] not in SECTIONS:
        print(__doc__, file=sys.stderr)
        return 2
    lang, regions_tsv, out = argv[0], Path(argv[1]), Path(argv[2])
    regions = dict(line.split("\t")[:2] for line in regions_tsv.read_text(encoding="utf-8").splitlines() if "\t" in line)
    try:
        rows = collect(regions, lang)
    except Exception as e:
        print(f"-- Wikipedia non disponibile ({e}): le guide tengono le sezioni di Wikipedia pubblicate", file=sys.stderr)
        rows = []
    out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    print(f"guide: voce di Wikipedia {lang} letta per {len(rows)}/{len(regions)} regioni, "
          f"{sum(bool(r['text']) for r in rows)} con sezioni")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
