#!/usr/bin/env python3
"""Lettura dei dump XML di MediaWiki senza API: i MediaWiki Content File Exports di Wikimedia, contenuto attuale,
uno al mese il 1° (https://dumps.wikimedia.org/other/mediawiki_content_current/<wiki>/<AAAA-MM-GG>/xml/bzip2/, con
SHA256SUMS, scritto per ultimo a export finito). Ogni wiki ha una o piu' parti per intervallo di pagine,
<wiki>-<AAAA-MM-GG>-p<da>p<a>.xml.bz2 (Wikipedia EN 17, Wikipedia IT tre, Wikivoyage una), scaricate da
download-wikimedia-dumps.sh. La data si puo' dare anche come mese e anno (normalize_date).

Usato da generate_sft*.py, generate_eval_set*.py ed extract-cities-dump*.py al posto delle migliaia di richieste a
Wikivoyage/Wikipedia: il risultato dipende solo dal dump di una data precisa, quindi e' riproducibile.

- dump_files(): le parti di una wiki in una cartella, in ordine di pagina.
- iter_pages(): scorre tutte le parti (va bene per Wikivoyage, 50-200 MB compressi).
- load_titles(): solo le pagine richieste. I file non hanno un indice: si scorrono le parti in parallelo
  (load_pages, ~6 GB per Wikipedia IT, qualche minuto; ~40 GB per Wikipedia EN) e le pagine trovate si salvano in una
  cache accanto ai dump.
"""
import bz2
import datetime
import json
import os
import re
import xml.etree.ElementTree as ET
from multiprocessing import Pool
from pathlib import Path

PART = re.compile(r"-p(\d+)p\d+\.xml\.bz2$")
EXPORTS_URL = "https://dumps.wikimedia.org/other/mediawiki_content_current"


def export_url(wiki, date):
    """Pagina dell'export di [wiki] del giorno [date] (normalize_date): la si scrive accanto ai file generati, che cosi'
    dicono da quale dump vengono."""
    return f"{EXPORTS_URL}/{wiki}/{normalize_date(date)}/xml/bzip2/"


def norm_title(title):
    """Titolo come lo scrive il dump: spazi invece di underscore, prima lettera maiuscola."""
    t = title.replace("_", " ").strip()
    return t[:1].upper() + t[1:]


def _pages(stream):
    """(titolo, testo, destinazione del redirect o None) per le pagine del namespace 0."""
    root = None
    for event, el in ET.iterparse(stream, events=("start", "end")):
        if root is None:
            root = el
        if event != "end" or (not el.tag.endswith("}page") and el.tag != "page"):
            continue
        ns = el.tag[:-4] if el.tag.endswith("}page") else ""
        if el.findtext(f"{ns}ns") == "0":
            redirect = el.find(f"{ns}redirect")
            yield (el.findtext(f"{ns}title"), el.findtext(f"{ns}revision/{ns}text") or "",
                   # "Culture of Guyana#Cuisine": la pagina e' quella senza la sezione, come l'API con redirects=1
                   norm_title(redirect.get("title").split("#")[0]) if redirect is not None else None)
        root.clear()  # anche le pagine gia' lette: svuotate ma attaccate alla radice, in una parte di enwiki sono milioni


def iter_pages(paths):
    """Le pagine del namespace 0 di un file di dump, o di tutte le parti di [paths] (lista) in ordine."""
    for path in paths if isinstance(paths, (list, tuple)) else [paths]:
        with bz2.open(path, "rb") as f:
            yield from _pages(f)


def normalize_date(text):
    """Data di un export, AAAA-MM-GG, da MM-AAAA o AAAA-MM (separatori -, / o .: il giorno e' sempre il 1°), oppure da
    AAAA-MM-GG per sperimentare. ValueError per gli altri formati e per mesi o giorni che non esistono. Come
    normalize_dump_date in lib.sh."""
    t = str(text).strip()
    m = (re.fullmatch(r"(?P<m>\d{1,2})[-/.](?P<y>\d{4})", t) or re.fullmatch(r"(?P<y>\d{4})[-/.](?P<m>\d{1,2})", t)
         or re.fullmatch(r"(?P<y>\d{4})-(?P<m>\d{2})-(?P<d>\d{2})", t))
    try:
        return datetime.date(int(m["y"]), int(m["m"]), int(m.groupdict().get("d") or 1)).isoformat()
    except (TypeError, ValueError):  # TypeError: nessun formato riconosciuto (m e' None)
        raise ValueError(f"data non valida: {text!r} (formati: MM-AAAA o AAAA-MM, separati da - / o ., "
                         "oppure AAAA-MM-GG)") from None


def dump_files(dump_dir, wiki="*", date="*"):
    """[parti] del dump di [wiki] (es. itwikivoyage) del giorno [date] (normalize_date) in [dump_dir], in ordine di
    pagina; senza [wiki] e [date] tutte le parti della cartella (una sola wiki). FileNotFoundError se non ce ne sono, o
    se ne manca una di quelle elencate in <wiki>.SHA256SUMS (download-wikimedia-dumps.sh lo scrive prima delle parti:
    con un download a meta' le pagine trovate finirebbero nella cache di load_titles come se fossero tutte)."""
    date = date if date == "*" else normalize_date(date)
    parts = sorted((p for p in Path(dump_dir).glob(f"{wiki}-{date}-p*p*.xml.bz2") if PART.search(p.name)),
                   key=lambda p: int(PART.search(p.name).group(1)))
    if not parts:
        raise FileNotFoundError(f"nessuna parte {wiki}-{date}-p<da>p<a>.xml.bz2 in {dump_dir}")
    sums = Path(dump_dir) / f"{wiki}.SHA256SUMS"
    if wiki != "*" and sums.exists():
        listed = {line.split()[-1] for line in sums.read_text(encoding="utf-8").splitlines() if line.strip()}
        if missing := sorted(n for n in listed if PART.search(n) and n not in {p.name for p in parts}):
            raise FileNotFoundError(f"export incompleto in {dump_dir}, mancano: {', '.join(missing)}")
    return parts


def _scan(args):
    """(pagine, redirect) di [wanted] in un file: {titolo: testo}, {titolo: destinazione}."""
    path, wanted = args
    found, redirects = {}, {}
    for title, text, target in iter_pages(path):
        if title in wanted:
            if target:
                redirects[title] = target
            else:
                found[title] = text
    return found, redirects


def load_pages(paths, titles):
    """{titolo richiesto: (titolo finale, testo)} scorrendo tutte le parti [paths], al massimo una per processo e un
    processo per CPU (Wikipedia EN ha 17 parti), con un livello di redirect (un secondo giro solo per le destinazioni
    che mancano): il titolo finale e' quello dopo il redirect. Le pagine vuote non ci sono."""
    wanted = {norm_title(t) for t in titles}
    found, redirects = {}, {}
    need = wanted
    for _ in range(2):
        with Pool(min(len(paths), os.cpu_count() or 1)) as pool:
            for f, r in pool.imap_unordered(_scan, [(p, need) for p in paths]):
                found.update(f)
                redirects.update(r)
        need = {d for d in redirects.values() if d not in found}
        if not need:
            break
    final = {t: redirects.get(t, t) for t in wanted}
    return {t: (final[t], found[final[t]]) for t in wanted if found.get(final[t])}


def load_titles(dump_dir, wiki, date, titles):
    """{titolo richiesto: (titolo finale, testo)} delle pagine [titles] di [wiki] del giorno [date] (normalize_date), da
    load_pages, con la cache <wiki>-<AAAA-MM-GG>.pages.json in [dump_dir] ({"titles": titoli cercati, "pages": trovate})
    riusata quando contiene tutti i titoli richiesti."""
    wanted = {norm_title(t) for t in titles}
    date = normalize_date(date)
    cache = Path(dump_dir) / f"{wiki}-{date}.pages.json"
    try:
        cached = json.loads(cache.read_text(encoding="utf-8"))
        # le cache vecchie hanno solo il testo, senza il titolo finale: si rifanno
        if wanted <= set(cached["titles"]) and all(isinstance(v, list) for v in cached["pages"].values()):
            return {t: tuple(v) for t, v in cached["pages"].items() if t in wanted}
    except (OSError, ValueError, KeyError):  # cache assente o rovinata (scrittura interrotta): si rifa'
        pass
    pages = load_pages(dump_files(dump_dir, wiki, date), wanted)
    cache.write_text(json.dumps({"titles": sorted(wanted), "pages": pages}, ensure_ascii=False), encoding="utf-8")
    return pages
