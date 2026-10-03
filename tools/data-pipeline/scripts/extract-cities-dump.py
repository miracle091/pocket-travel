#!/usr/bin/env python3
"""Estrae dal dump di Wikivoyage IT le pagine {{QuickbarCity}} di ogni regione passata, in un JSONL
per regione (una riga per citta': {"city": titolo, "text": wikitext grezzo}) sotto <outDir>. Un
solo passaggio sul dump per TUTTE le regioni (vedi build-cities-dump.sh, chiamato una volta per
shard nel workflow), invece di uno per regione: letto poi da GenerateCities.kt (build-cities.sh,
una regione alla volta) per generare cities.db, che pulisce il wikitext con la stessa cleanBody
delle guide - questo script non duplica quella pulizia.

Uso: extract-cities-dump.py <dump.xml.bz2> <regioni.tsv> <outDir>
regioni.tsv: righe "regionId<TAB>itTitle" (titolo IT Wikivoyage gia' risolto via langlink, vedi
resolve_it_wikivoyage_title in lib.sh), una per regione.
"""
import json
import re
import sys
from pathlib import Path

import city_population
import wiki_dump

# Stessa regola di city_parents in generate_sft_dataset.py: Stato/Stato federato/Regione/Territorio
# del QuickbarCity sono i link ai luoghi che contengono la citta'.
QUICKBAR_FIELD = re.compile(r"^\|\s*(Stato|Stato federato|Regione|Territorio)\s*=\s*\[\[([^\]|#]+)", re.M)


def city_parents(text):
    """Titoli (normalizzati) dei luoghi che contengono la citta', o None se la pagina non ha un
    {{QuickbarCity}}. Solo i primi 4000 caratteri: il template e' sempre in testa alla pagina."""
    if not re.match(r"\s*\{\{\s*QuickbarCity", text, re.I):
        return None
    return {wiki_dump.norm_title(m.group(2)) for m in QUICKBAR_FIELD.finditer(text[:4000])}


def main():
    if len(sys.argv) != 4:
        print(f"Uso: {sys.argv[0]} <dump.xml.bz2> <regioni.tsv> <outDir>", file=sys.stderr)
        sys.exit(1)
    dump_path, regions_tsv, out_dir = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = [line.split("\t") for line in regions_tsv.read_text(encoding="utf-8").splitlines() if line.strip()]
    region_id_by_title = {wiki_dump.norm_title(title): region_id for region_id, title in rows if title}

    files, counts = {}, {}
    try:
        for title, text, redirect in wiki_dump.iter_pages(dump_path):
            if redirect:
                continue
            parents = city_parents(text)
            if not parents:
                continue
            region_ids = {region_id_by_title[p] for p in parents if p in region_id_by_title}
            for region_id in region_ids:
                if region_id not in files:
                    files[region_id] = open(out_dir / f"{region_id}.cities.jsonl", "w", encoding="utf-8")
                files[region_id].write(json.dumps({"city": title, "text": text}, ensure_ascii=False) + "\n")
                counts[region_id] = counts.get(region_id, 0) + 1
    finally:
        for f in files.values():
            f.close()

    print(f"citta': {sum(counts.values())} pagine di {len(counts)} regioni scritte in {out_dir}")
    # popolazione (Abitanti del QuickbarCity, poi Wikidata) per le citta' principali della scheda Citta' dell'app
    title_of = {region_id: title for title, region_id in region_id_by_title.items()}
    city_population.annotate([out_dir / f"{r}.cities.jsonl" for r in counts], "it",
                             {out_dir / f"{r}.cities.jsonl": title_of[r] for r in counts})


if __name__ == "__main__":
    main()
