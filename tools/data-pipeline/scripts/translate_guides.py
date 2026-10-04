#!/usr/bin/env python3
"""Guide arricchite dall'altra lingua, italiano dall'inglese o inglese dall'italiano (--from): per ogni regione (o
citta') e categoria si confronta la sezione della guida con quella nell'altra lingua
(translate_sections.needs_translation: assente, sotto 300 caratteri o lunga meno della meta') e, dove l'altra e' molto
piu' ricca, la si traduce. Scrive un file di sezioni tradotte (una riga JSON per regione e categoria) che
generateGuides / generateCities (--translated) mettono al posto di quelle povere, segnandole con translated = 1 nel
database.

Uso: translate_guides.py guides|cities <guides.db|cities.db da arricchire> <idem nell'altra lingua> <tradotte.jsonl>
         --cache-dir DIR --model-dir DIR [--from en|it] [--max-seconds N] [--deadline EPOCH]
Con --from en (default) il database da arricchire e' quello italiano, con --from it quello inglese.

Motore: CTranslate2 (int8, CPU) sui modelli Helsinki-NLP/opus-mt-tc-big-en-it e opus-mt-tc-big-it-en (CC BY 4.0),
convertiti da convert-translation-model.sh (--model-dir e' quello della direzione); la divisione in frasi, la cache delle
frasi (un file per direzione in --cache-dir) e lo scarto delle frasi sospette sono quelli di translate_sections.py. Il
testo tradotto e' un'opera derivata di Wikivoyage (CC BY-SA 4.0): l'app lo dichiara tra le licenze.

Tetto di tempo: --max-seconds per questa chiamata e --deadline (secondi dal 1970) per un tetto comune a piu'
chiamate; passato il primo si smette di tradurre frasi nuove e le sezioni restanti seguono nella run successiva (la
cache in --cache-dir tiene il lavoro fatto). Le sezioni gia' interamente in cache si includono sempre, a prescindere dal
tempo, cosi' una guida gia' tradotta non torna povera a pezzi. Una sezione con una frase sospetta resta com'e'.
Con il motore o il modello mancanti lo script fallisce senza scrivere nulla: il chiamante pubblica la guida originale.

Citta': l'omologo di una pagina e' il titolo di Wikivoyage nell'altra lingua collegato dallo stesso elemento Wikidata
(il titolo e' spesso diverso: Venezia / Venice), in mancanza lo stesso titolo; le pagine che esistono solo nell'altra
lingua non si aggiungono (Londra e London comparirebbero due volte).
"""
import argparse
import json
import os
import sqlite3
import sys
import time
import urllib.parse
from pathlib import Path

import city_population
import translate_sections as ts

# Dai fatti rapidi in inglese non si traduce: li costruisce la pipeline dal Quickbar italiano.
SKIP_CATEGORIES = {"FATTI_RAPIDI"}
CHUNK_CHARS = 40_000  # testo inglese per chiamata al modello: tra un lotto e l'altro si controlla il tempo
# Sezioni che in realta' vengono dalla pagina nell'altra lingua (generateGuides usa quella inglese quando la pagina
# italiana non ha sezioni, es. la Siberia) equivalgono a una sezione assente: group() le salta per il prefisso dell'url.


class Ct2Translator(ts.Translator):
    """translate_sections.Translator con CTranslate2 al posto di torch: stessa cache e stessi controlli."""

    def __init__(self, src, tgt, cache_dir, model_dir):
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        super().__init__(src, tgt, cache_dir, batch=64)
        self.model_dir = Path(model_dir)

    def _load(self):
        import ctranslate2
        import sentencepiece
        self.tokenizer = sentencepiece.SentencePieceProcessor(model_file=str(self.model_dir / "source.spm"))
        self.target = sentencepiece.SentencePieceProcessor(model_file=str(self.model_dir / "target.spm"))
        self.model = ctranslate2.Translator(str(self.model_dir), device="cpu", compute_type="int8",
                                            intra_threads=os.cpu_count() or 4)
        print(f"[traduzione] {self.model_dir.name} su cpu (CTranslate2 int8)", file=sys.stderr)

    def _decode(self, batch):
        results = self.model.translate_batch([self.tokenizer.encode(s, out_type=str) + ["</s>"] for s in batch],
                                             beam_size=4, max_decoding_length=512, max_input_length=512)
        return [self.target.decode(r.hypotheses[0]) for r in results]


def read_rows(db, table, owner_column):
    """[(proprietario, categoria, titolo, corpo, sourceUrl)] di una tabella di sezioni; [] se la tabella manca."""
    con = sqlite3.connect(str(db))
    try:
        return con.execute(f"SELECT {owner_column}, category, title, body, sourceUrl FROM {table} ORDER BY rowid").fetchall()
    except sqlite3.OperationalError:
        return []
    finally:
        con.close()


def group(rows, drop_url_prefix=None):
    """{(proprietario, categoria): [(titolo, corpo, sourceUrl)]}, nell'ordine delle righe."""
    grouped = {}
    for owner, category, title, body, url in rows:
        if drop_url_prefix and url.startswith(drop_url_prefix):
            continue
        grouped.setdefault((owner, category), []).append((title, body, url))
    return grouped


def pick_sections(own_sections, source, source_owner):
    """[(proprietario, categoria, righe d'origine)] da tradurre. [own_sections] e [source]: group(), della guida da
    arricchire e di quella nell'altra lingua; [source_owner]: {proprietario: omologo nell'altra lingua}; vale il
    confronto per categoria (testo di tutte le sue sezioni)."""
    by_owner = {}
    for (owner, category), rows in source.items():
        by_owner.setdefault(owner, {})[category] = rows
    picks = []
    for owner, other in sorted(source_owner.items()):
        for category, rows in sorted(by_owner.get(other, {}).items()):
            if category in SKIP_CATEGORIES:
                continue
            own = "\n".join(body for _, body, _ in own_sections.get((owner, category), []))
            if ts.needs_translation(own, "\n".join(body for _, body, _ in rows)):
                picks.append((owner, category, rows))
    return picks


def _all_cached(translator, texts):
    return all(translator._key(s) in translator.cache for t in texts for _, ss in translator._pieces(t) for s in ss)


def _translate_chunk(translator, picks):
    """{(proprietario, categoria): [(titolo, corpo, sourceUrl)] tradotti}; salta le sezioni con una frase sospetta."""
    flat = [(p, row) for p in picks for row in p[2]]
    outs = translator.translate_many([row[1] for _, row in flat] + [row[0] for _, row in flat])
    bodies, titles = outs[:len(flat)], outs[len(flat):]
    translated, failed, position = {}, set(), 0
    for p in picks:
        key, taken = (p[0], p[1]), set()
        for title, body, url in p[2]:
            new_body, new_title = bodies[position], titles[position]
            position += 1
            if new_body is None:
                failed.add(key)
                continue
            # due titoli uguali nella stessa categoria darebbero la stessa chiave di lista nell'app
            if new_title is None or new_title.lower() in taken:
                new_title = title
            taken.add(new_title.lower())
            translated.setdefault(key, []).append((new_title, new_body, url))
    # una categoria tradotta solo in parte mescolerebbe due lingue: si scarta intera
    return {k: v for k, v in translated.items() if k not in failed}, len(failed)


def translate_picks(picks, translator, deadline, priority=None):
    """Traduce [picks] (vedi pick_sections) fino a [deadline] (secondi dal 1970): prima quelle gia' in cache, poi le
    altre in ordine di [priority] ({proprietario: peso}, i piu' pesanti per primi). Ritorna (traduzioni, rimandate)."""
    priority = priority or {}
    picks = sorted(picks, key=lambda p: (-priority.get(p[0], 0), p[0], p[1]))
    ready = [p for p in picks if _all_cached(translator, [r[1] for r in p[2]] + [r[0] for r in p[2]])]
    pending = [p for p in picks if p not in ready]
    result, failed = {}, 0
    if ready:
        done, bad = _translate_chunk(translator, ready)
        result.update(done)
        failed += bad
    while pending and time.time() < deadline:
        chunk, size = [], 0
        while pending and size < CHUNK_CHARS:
            chunk.append(pending.pop(0))
            size += sum(len(r[1]) for r in chunk[-1][2])
        done, bad = _translate_chunk(translator, chunk)
        result.update(done)
        failed += bad
    print(f"traduzioni: {len(picks)} categorie da tradurre, {len(result)} tradotte (di cui {len(ready)} gia' in cache), "
          f"{failed} scartate per frasi sospette, {len(pending)} rimandate per il tempo", file=sys.stderr)
    return result, pending


def other_titles(own_titles, other_titles, own_lang, other_lang, cache_dir):
    """{titolo nella lingua [own_lang]: titolo in [other_lang]} delle citta': sitelink di Wikivoyage nell'altra lingua
    dell'elemento Wikidata della pagina, altrimenti lo stesso titolo se c'e' tra [other_titles]. I collegamenti trovati
    stanno in --cache-dir."""
    cache_path = Path(cache_dir) / f"city-titles.{own_lang}-{other_lang}.jsonl"
    known = {}
    if cache_path.exists():
        for line in cache_path.read_text(encoding="utf-8").splitlines():
            if line:
                d = json.loads(line)
                known[d["own"]] = d["other"]
    todo = sorted(t for t in own_titles if t not in known)
    found = {}
    try:
        items = city_population.wikibase_items(todo, own_lang)
        ids = sorted(set(items.values()))
        title_of_item = {}
        for i in range(0, len(ids), city_population.BATCH):
            url = ("https://www.wikidata.org/w/api.php?action=wbgetentities&props=sitelinks"
                   f"&sitefilter={other_lang}wikivoyage&format=json&ids="
                   + urllib.parse.quote("|".join(ids[i:i + city_population.BATCH]), safe="|"))
            for q, entity in city_population._get(url).get("entities", {}).items():
                if link := entity.get("sitelinks", {}).get(f"{other_lang}wikivoyage"):
                    title_of_item[q] = link["title"]
            time.sleep(0.2)
        found = {t: title_of_item[q] for t, q in items.items() if q in title_of_item}
    except Exception as e:  # rete o Wikidata: si ripiega sul titolo uguale
        print(f"-- titoli delle citta' nell'altra lingua da Wikidata non disponibili ({e})", file=sys.stderr)
    if found:
        with open(cache_path, "a", encoding="utf-8") as f:
            for t, other in sorted(found.items()):
                f.write(json.dumps({"own": t, "other": other}, ensure_ascii=False) + "\n")
    known.update(found)
    return {t: known.get(t, t) for t in own_titles if known.get(t, t) in other_titles}


def write_overlay(path, translated):
    with open(path, "w", encoding="utf-8") as f:
        for (owner, category), rows in sorted(translated.items()):
            f.write(json.dumps({"owner": owner, "category": category,
                                "sections": [{"title": t, "body": b, "sourceUrl": u} for t, b, u in rows]},
                               ensure_ascii=False) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("kind", choices=["guides", "cities"])
    ap.add_argument("own_db")
    ap.add_argument("source_db")
    ap.add_argument("overlay")
    ap.add_argument("--cache-dir", required=True)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--from", dest="source_lang", choices=["en", "it"], default="en")
    ap.add_argument("--max-seconds", type=float, default=3600)
    ap.add_argument("--deadline", type=float, default=float("inf"))
    args = ap.parse_args(argv)

    source_lang = args.source_lang
    own_lang = "it" if source_lang == "en" else "en"
    table, owner_column = ("guide_sections", "regionId") if args.kind == "guides" else ("city_sections", "city")
    own_rows, source_rows = read_rows(args.own_db, table, owner_column), read_rows(args.source_db, table, owner_column)
    # sezioni il cui url e' nella lingua sbagliata (la pagina inglese usata per la guida italiana senza sezioni) non valgono
    source = group(source_rows, drop_url_prefix=f"https://{own_lang}.")
    owners = {r[0] for r in own_rows}
    source_owners = {k[0] for k in source}
    priority = None
    if args.kind == "guides":
        source_owner = {o: o for o in owners if o in source_owners}
    else:
        source_owner = other_titles(owners, source_owners, own_lang, source_lang, args.cache_dir)
        con = sqlite3.connect(args.own_db)
        try:
            priority = dict(con.execute("SELECT city, MAX(population) FROM city_sections WHERE population IS NOT NULL GROUP BY city"))
        except sqlite3.OperationalError:  # cities.db senza la colonna population
            pass
        finally:
            con.close()
    own_sections = group(own_rows, drop_url_prefix=f"https://{source_lang}.")
    picks = pick_sections(own_sections, source, source_owner)
    if not picks:
        print(f"traduzioni {source_lang}->{own_lang}: nessuna sezione povera rispetto all'altra lingua", file=sys.stderr)
        write_overlay(args.overlay, {})
        return 0

    translator = Ct2Translator(source_lang, own_lang, args.cache_dir, args.model_dir)
    before = len(translator.cache)
    deadline = min(time.time() + args.max_seconds, args.deadline)
    translated, _ = translate_picks(picks, translator, deadline, priority)
    write_overlay(args.overlay, translated)
    marker = os.environ.get("TRANSLATE_GROWN_MARKER")
    if marker and len(translator.cache) > before:
        Path(marker).touch()
    return 0


if __name__ == "__main__":
    sys.exit(main())
