#!/usr/bin/env python3
"""Lettura dei dump XML di MediaWiki (dumps.wikimedia.org, pages-articles .xml.bz2) senza API.

Usato da generate_sft_dataset.py al posto delle migliaia di richieste a Wikivoyage/Wikipedia: il dataset
dipende solo dal dump di una data precisa, quindi e' riproducibile.

- iter_pages(): scorre tutto il dump (va bene per Wikivoyage, ~50 MB compressi).
- load_multistream(): legge solo le pagine richieste da un dump "multistream" usando il suo indice
  (offset:pageid:titolo): per Wikipedia IT (~4,4 GB) decomprime solo i blocchi da ~100 pagine che servono.
"""
import bz2
import io
import xml.etree.ElementTree as ET
from collections import defaultdict

def norm_title(title):
    """Titolo come lo scrive il dump: spazi invece di underscore, prima lettera maiuscola."""
    t = title.replace("_", " ").strip()
    return t[:1].upper() + t[1:]


def _pages(stream):
    """(titolo, testo, destinazione del redirect o None) per le pagine del namespace 0."""
    for _, el in ET.iterparse(stream, events=("end",)):
        if not el.tag.endswith("}page") and el.tag != "page":
            continue
        ns = el.tag[:-4] if el.tag.endswith("}page") else ""
        if el.findtext(f"{ns}ns") == "0":
            redirect = el.find(f"{ns}redirect")
            yield (el.findtext(f"{ns}title"), el.findtext(f"{ns}revision/{ns}text") or "",
                   norm_title(redirect.get("title")) if redirect is not None else None)
        el.clear()


def iter_pages(path):
    with bz2.open(path, "rb") as f:
        yield from _pages(f)


def load_multistream(xml_path, index_path, titles):
    """{titolo richiesto: testo} per le pagine trovate, seguendo un livello di redirect. I titoli si
    confrontano normalizzati (norm_title)."""
    wanted = {norm_title(t) for t in titles}
    found, redirects = {}, {}
    for _ in range(2):  # secondo giro solo per le destinazioni dei redirect
        need = (wanted - found.keys() - redirects.keys()) | {d for d in redirects.values() if d not in found}
        blocks = defaultdict(set)
        with bz2.open(index_path, "rt", encoding="utf-8") as idx:
            offsets = []
            for line in idx:
                off, _, title = line.rstrip("\n").split(":", 2)
                offsets.append(int(off))
                if title in need:
                    blocks[int(off)].add(title)
        ends = {o: n for o, n in zip(sorted(set(offsets)), sorted(set(offsets))[1:])}
        with open(xml_path, "rb") as f:
            for off, names in blocks.items():
                f.seek(off)
                data = f.read(ends[off] - off) if off in ends else f.read()
                xml = b"<mediawiki>" + bz2.decompress(data) + b"</mediawiki>"
                for title, text, target in _pages(io.BytesIO(xml)):
                    if title not in names:
                        continue
                    if target:
                        redirects[title] = target
                    else:
                        found[title] = text
        if not any(d not in found for d in redirects.values()):
            break
    return {t: found.get(redirects.get(t, t)) for t in wanted if found.get(redirects.get(t, t))}
