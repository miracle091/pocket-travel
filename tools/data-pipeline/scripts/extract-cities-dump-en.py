#!/usr/bin/env python3
"""Come extract-cities-dump.py, ma dal dump di Wikivoyage EN: scrive <outDir>/<regionId>.cities-en.jsonl
(una riga per citta': {"city": titolo, "text": wikitext grezzo}), letto da GenerateCities.kt --lang en.

Le pagine EN non hanno un Quickbar con la nazione: una citta' e' una pagina con {{usablecity}},
{{guidecity}}, {{starcity}} o {{outlinecity}}, e il luogo che la contiene e' in {{isPartOf|...}}, un
livello alla volta (Rimini -> Rimini (province) -> Emilia-Romagna -> ... -> Italy). Due passaggi sul
dump: il primo raccoglie isPartOf di tutte le pagine e quali sono citta', il secondo scrive il testo
delle citta' che, risalendo la catena, arrivano a una delle regioni passate.

Uso: extract-cities-dump-en.py <dump.xml.bz2> <regioni.tsv> <outDir>
regioni.tsv: righe "regionId<TAB>wikiTitle" (titolo EN Wikivoyage, da pilot-regions.sh).
"""
import json
import re
import sys
from pathlib import Path

import city_population
import wiki_dump

IS_PART_OF = re.compile(r"\{\{\s*isPartOf\s*\|\s*([^}|]+)", re.I)
CITY_STATUS = re.compile(r"\{\{\s*(?:usable|guide|star|outline)city\s*}}", re.I)
# Catene piu' lunghe sono cicli o errori di pagina.
MAX_DEPTH = 12


def regions_of(title, parent, region_id_by_title):
    """regionId di tutte le regioni passate risalendo isPartOf da title (es. uno stato USA e gli Stati
    Uniti interi, come extract-cities-dump.py con Stato e Stato federato)."""
    found, seen = set(), set()
    current = title
    for _ in range(MAX_DEPTH):
        current = parent.get(current)
        if current is None or current in seen:
            break
        if current in region_id_by_title:
            found.add(region_id_by_title[current])
        seen.add(current)
    return found


def main():
    if len(sys.argv) != 4:
        print(f"Uso: {sys.argv[0]} <dump.xml.bz2> <regioni.tsv> <outDir>", file=sys.stderr)
        sys.exit(1)
    dump_path, regions_tsv, out_dir = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = [line.split("\t") for line in regions_tsv.read_text(encoding="utf-8").splitlines() if line.strip()]
    region_id_by_title = {wiki_dump.norm_title(title): region_id for region_id, title in rows if title}

    parent, cities, redirects = {}, set(), {}
    for title, text, redirect in wiki_dump.iter_pages(dump_path):
        if redirect:
            redirects[title] = redirect
            continue
        head = text[:6000] + text[-3000:]
        match = IS_PART_OF.search(head)
        if match:
            parent[title] = wiki_dump.norm_title(match.group(1))
        if CITY_STATUS.search(head):
            cities.add(title)
    # isPartOf che punta a un redirect: si segue la destinazione.
    parent = {child: redirects.get(p, p) for child, p in parent.items()}

    city_regions = {c: r for c in cities if (r := regions_of(c, parent, region_id_by_title))}

    files, counts = {}, {}
    try:
        for title, text, redirect in wiki_dump.iter_pages(dump_path):
            region_ids = set() if redirect else city_regions.get(title, set())
            for region_id in region_ids:
                if region_id not in files:
                    files[region_id] = open(out_dir / f"{region_id}.cities-en.jsonl", "w", encoding="utf-8")
                files[region_id].write(json.dumps({"city": title, "text": text}, ensure_ascii=False) + "\n")
                counts[region_id] = counts.get(region_id, 0) + 1
    finally:
        for f in files.values():
            f.close()

    print(f"cities (en): {sum(counts.values())} pagine di {len(counts)} regioni scritte in {out_dir}")
    # popolazione da Wikidata (le pagine EN non la riportano) per le citta' principali della scheda Citta' dell'app
    title_of = {region_id: title for title, region_id in region_id_by_title.items()}
    city_population.annotate([out_dir / f"{r}.cities-en.jsonl" for r in counts], "en",
                             {out_dir / f"{r}.cities-en.jsonl": title_of[r] for r in counts})


if __name__ == "__main__":
    main()
