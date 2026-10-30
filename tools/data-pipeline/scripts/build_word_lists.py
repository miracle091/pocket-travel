#!/usr/bin/env python3
"""Radici candidate per le liste [triggers ...] di word_lists.txt, tratte dalle guide stesse.

Per ogni categoria delle sezioni (i gruppi di word_lists.txt che sono categorie di GuideCategory: CONSIGLI, EMERGENZE,
PERIODI e METEO no) e per lingua, le parole tipiche delle sezioni di quella categoria rispetto a tutte le altre, con il
log-odds pesato e il prior di Dirichlet informativo (Monroe, Colaresi e Quinn, "Fightin' Words", 2008): z-score della
differenza dei log-odds, col prior proporzionale alla frequenza della parola in tutte le guide. Il corpus sono i
guides<suffisso>.db e <regione>.cities<suffisso>.db (piu' <regione>.wp<suffisso>.db, se c'e') nella cache di
eval_retrieval.py: va lanciato prima quello, con la stessa --lang.

Le parole si dividono come fa l'app con la domanda (minuscole, senza accenti, separate da cio' che non e' lettera o
cifra). Restano fuori le stopword di word_lists.txt, i nomi di luogo (le parole dei nomi delle citta' e delle regioni),
le parole scritte quasi sempre con la maiuscola (nomi di locali, di persone, di luoghi minori), quelle in poche sezioni o
in pochi luoghi della categoria, quelle in piu' di una sezione su 20 (parole di ogni testo, "dove", "the") e quelle che le
radici di adesso trovano gia'. Lo z ordina le candidate; la precisione le filtra: frequenza relativa della parola nella
categoria divisa per la somma delle frequenze relative in tutte le categorie, cioe' quanto la parola indica la categoria
se le categorie sono equiprobabili (con le sole occorrenze pesano troppo le categorie con piu' testo, Cosa vedere e
Storia). Ogni parola diventa una radice con le regole di questionGroups (CategoryWords.kt): il prefisso piu' corto, da 4
lettere, le cui parole nelle guide sono precise quasi quanto la parola stessa ("cost" no, perche' trova anche "costa" e
"costruito"); la parola intera con "=" se ogni prefisso, lei compresa, trova parole di altre categorie; sotto le 4
lettere la parola com'e'.

Con --stems RADICE ... z e precisione delle radici date nei tre gruppi dove sono piu' tipiche, per controllare una radice
scritta a mano. Con --write FILE una copia di word_lists.txt con le radici candidate aggiunte su una riga in fondo a ogni
[triggers ...] (quelle scritte a mano restano), da misurare con WORD_LISTS=FILE python eval_retrieval.py --word-lists
--baseline ...; con --select GRUPPO:radice solo quelle scelte, senza leggere le guide.

Le parole vengono dai testi di Wikivoyage e Wikipedia (CC BY-SA) delle guide pubblicate: una lista di parole non e' una
copia del testo.

Uso: python build_word_lists.py [--lang it|en ...] [--cache DIR] [--regions italia ...] [--groups GRUPPO ...] [--top N]
[--min-sections N] [--max-section-share Q] [--min-places N] [--prior A0] [--min-precision P] [--precision-ratio R]
[--stems RADICE ...] [--json FILE] [--write FILE [--select GRUPPO:radice ...]]
"""
import argparse
import bisect
import json
import math
import re
import sqlite3
import sys
import tempfile
from collections import Counter, defaultdict
from contextlib import closing
from pathlib import Path

from eval_retrieval import DEFAULT_REGIONS, LANGS, SHORT_STEM_CHARS, folded
from word_lists import CATEGORY_TRIGGERS, PATH as WORD_LISTS_PATH, STOPWORDS

# Gruppi di word_lists.txt che non sono categorie delle sezioni.
NOT_SECTION_GROUPS = {"CONSIGLI", "EMERGENZE", "PERIODI", "METEO"}
GROUPS = [g for g in CATEGORY_TRIGGERS if g not in NOT_SECTION_GROUPS]
MAX_CAPITALIZED = 0.5  # oltre questa quota di occorrenze con la maiuscola la parola e' un nome
MIN_WORD_CHARS = 3


def split_words(text):
    """(parola senza accenti, con la maiuscola) per ogni parola di [text], divise come le parole della domanda."""
    return [(folded(t), t[0].isupper()) for t in re.split(r"[^\w]+|_", text) if t]


def hits(stem, word):
    """La radice [stem] trova la parola [word] con le regole di questionGroups (espressioni con "+" escluse)."""
    if stem.endswith("="):
        return word in (stem[:-1], stem[:-1] + "s")
    return word.startswith(stem) if len(stem) >= SHORT_STEM_CHARS else word in (stem, stem + "s")


def read_corpus(cache, lang, regions):
    """(categoria, luogo, testo) delle sezioni delle guide e delle citta' in cache, e le parole dei nomi di luogo."""
    sfx = LANGS[lang]["suffix"]
    rows, names = [], set()
    with closing(sqlite3.connect(cache / f"guides{sfx}.db")) as db:
        for region, category, title, body in db.execute("SELECT regionId, category, title, body FROM guide_sections"):
            rows.append((category, region, f"{title or ''}\n{body}"))
            names.update(re.split(r"[^\w]+|_", folded(region)))
    for region in regions:
        names.update(re.split(r"[^\w]+|_", folded(region)))
        for kind in ("cities", "wp"):
            path = cache / f"{region}.{kind}{sfx}.db"
            if not path.exists():
                continue
            with closing(sqlite3.connect(path)) as db:
                for city, category, title, body in db.execute("SELECT city, category, title, body FROM city_sections"):
                    rows.append((category, city, f"{title or ''}\n{body}"))
                    names.update(re.split(r"[^\w]+|_", folded(city)))
    names.discard("")
    return rows, names


class Counts:
    """Occorrenze delle parole per categoria, sezioni e luoghi per parola, quota con la maiuscola."""

    def __init__(self, rows):
        self.by_cat = defaultdict(Counter)
        self.sections = Counter()
        self.section_count = len(rows)
        places = defaultdict(set)
        capitalized = Counter()
        for category, place, text in rows:
            words = split_words(text)
            self.by_cat[category].update(w for w, _ in words)
            capitalized.update(w for w, cap in words if cap)
            for w in {w for w, _ in words}:
                self.sections[w] += 1
                places[(category, w)].add(place)
        self.total = Counter()
        for counter in self.by_cat.values():
            self.total.update(counter)
        self.size = sum(self.total.values())
        self.cat_size = {c: sum(counter.values()) for c, counter in self.by_cat.items()}
        self.capitalized = {w: capitalized[w] / n for w, n in self.total.items()}
        self.places = {key: len(v) for key, v in places.items()}
        self.vocab = sorted(self.total)

    def family(self, stem):
        """Parole delle guide che la radice trova."""
        if stem.endswith("=") or len(stem) < SHORT_STEM_CHARS:
            base = stem.rstrip("=")
            return [w for w in (base, base + "s") if w in self.total]
        start = bisect.bisect_left(self.vocab, stem)
        end = bisect.bisect_left(self.vocab, stem + "\U0010ffff")
        return self.vocab[start:end]


def z_score(y_i, n_i, y_all, n_all, prior):
    """z del log-odds pesato della categoria contro il resto (Monroe et al. 2008, eq. 16-22), prior informativo
    alpha_w = prior * frequenza della parola nel corpus."""
    y_j, n_j = y_all - y_i, n_all - n_i
    alpha = prior * y_all / n_all
    delta = (math.log((y_i + alpha) / (n_i + prior - y_i - alpha))
             - math.log((y_j + alpha) / (n_j + prior - y_j - alpha)))
    return delta / math.sqrt(1 / (y_i + alpha) + 1 / (y_j + alpha))


def stem_stats(counts, group, stem, prior):
    """(z, precisione) delle parole trovate da [stem]. La precisione e' la frequenza relativa nella categoria divisa per
    la somma delle frequenze relative in tutte le categorie: quanto le parole indicano la categoria a categorie
    equiprobabili, senza che pesino le categorie con piu' testo (Cosa vedere, Storia)."""
    words = counts.family(stem)
    y_i = sum(counts.by_cat[group][w] for w in words)
    y_all = sum(counts.total[w] for w in words)
    if not y_i:
        return 0.0, 0.0
    rates = {c: sum(counter[w] for w in words) / counts.cat_size[c] for c, counter in counts.by_cat.items()}
    return z_score(y_i, counts.cat_size[group], y_all, counts.size, prior), rates[group] / sum(rates.values())


def choose_stem(counts, group, word, prior, precision_ratio):
    """Radice della parola: il prefisso piu' corto, da SHORT_STEM_CHARS lettere, preciso quasi quanto la parola
    (almeno precision_ratio volte); la parola con "=" se anche lei come prefisso trova parole di altre categorie."""
    if len(word) < SHORT_STEM_CHARS:
        return word
    _, word_precision = stem_stats(counts, group, word + "=", prior)
    for k in range(SHORT_STEM_CHARS, len(word) + 1):
        if stem_stats(counts, group, word[:k], prior)[1] >= precision_ratio * word_precision:
            return word[:k]
    return word + "="


def covered(word, triggers):
    """Gruppi le cui radici di adesso trovano gia' la parola."""
    return {g for g, stems in triggers.items() if any(hits(s, word) for s in stems.split() if "+" not in s)}


def candidates(counts, names, group, top, prior=1000.0, min_sections=20, max_section_share=0.05, min_places=5,
               min_precision=0.3, precision_ratio=0.8, triggers=CATEGORY_TRIGGERS):
    """Le [top] radici con lo z piu' alto per [group]: {stem, z, precision, words, other_groups}, calcolati sulle parole
    che la radice trova nelle guide; other_groups sono i gruppi le cui radici trovano gia' alcune di quelle parole."""
    if group not in counts.by_cat:
        return []
    n_i = counts.cat_size[group]
    max_sections = max_section_share * counts.section_count
    scored = []
    for w, y_all in counts.total.items():
        y_i = counts.by_cat[group][w]
        if (not y_i or len(w) < MIN_WORD_CHARS or w.isdigit() or w in STOPWORDS or w in names
                or not min_sections <= counts.sections[w] <= max_sections
                or counts.places.get((group, w), 0) < min_places or counts.capitalized[w] > MAX_CAPITALIZED):
            continue
        z = z_score(y_i, n_i, y_all, counts.size, prior)
        if z > 0:
            scored.append((z, w))
    scored.sort(reverse=True)
    out = []
    for _, w in scored:
        if len(out) >= top:
            break
        if covered(w, triggers) or any(hits(c["stem"], w) for c in out):
            continue
        if stem_stats(counts, group, w + "=", prior)[1] < min_precision:
            continue
        stem = choose_stem(counts, group, w, prior, precision_ratio)
        # Una radice gia' scelta piu' lunga, che questa contiene, lascia il posto a questa.
        out = [c for c in out if not hits(stem, c["stem"].rstrip("="))]
        z, precision = stem_stats(counts, group, stem, prior)
        family = counts.family(stem)
        out.append({"stem": stem, "z": round(z, 1), "precision": round(precision, 2),
                    "words": sorted(family, key=lambda f: -counts.by_cat[group][f])[:6],
                    "other_groups": sorted({g for f in family for g in covered(f, triggers)} - {group})})
    return out


def with_stems(text, additions):
    """word_lists.txt [text] con le radici di {gruppo: [radici]} su una riga in piu' in fondo a ogni [triggers GRUPPO]."""
    lines = text.splitlines()
    last, section = {}, None
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            section = stripped[1:-1]
        elif stripped and not stripped.startswith("#") and section:
            last[section] = i
    for group, stems in sorted(additions.items(), key=lambda kv: -last.get(f"triggers {kv[0]}", -1)):
        if f"triggers {group}" not in last:
            raise ValueError(f"manca la sezione [triggers {group}]")
        if stems:
            lines.insert(last[f"triggers {group}"] + 1, " ".join(stems))
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--lang", nargs="+", choices=sorted(LANGS), default=["it", "en"])
    ap.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "pocket-travel-eval-retrieval")
    ap.add_argument("--regions", nargs="+", default=DEFAULT_REGIONS)
    ap.add_argument("--groups", nargs="+", choices=GROUPS, default=GROUPS)
    ap.add_argument("--top", type=int, default=15)
    ap.add_argument("--min-sections", type=int, default=20, help="sezioni minime con la parola, in tutte le categorie")
    ap.add_argument("--max-section-share", type=float, default=0.05,
                    help="quota massima delle sezioni con la parola: oltre e' una parola di ogni testo")
    ap.add_argument("--min-places", type=int, default=5, help="luoghi minimi con la parola nella categoria")
    ap.add_argument("--prior", type=float, default=1000.0, help="alpha_0 del prior di Dirichlet")
    ap.add_argument("--min-precision", type=float, default=0.3)
    ap.add_argument("--precision-ratio", type=float, default=0.8)
    ap.add_argument("--stems", nargs="+", default=[], metavar="RADICE",
                    help="invece delle candidate, z e precisione di queste radici nei gruppi dove sono piu' tipiche")
    ap.add_argument("--json", type=Path, help="scrive le candidate in JSON: {lingua: {gruppo: [...]}}")
    ap.add_argument("--write", type=Path, help="copia di word_lists.txt con le radici candidate aggiunte")
    ap.add_argument("--select", nargs="+", default=[], metavar="GRUPPO:radice",
                    help="con --write, solo queste radici (anche non candidate)")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    additions = defaultdict(list)
    for item in a.select:
        group, _, stem = item.partition(":")
        additions[group].append(stem)
    result = {}
    for lang in [] if a.write and a.select else a.lang:
        if not (a.cache / f"guides{LANGS[lang]['suffix']}.db").exists():
            sys.exit(f"manca la cache di eval_retrieval.py in {a.cache}: lancia prima eval_retrieval.py --lang {lang}")
        rows, names = read_corpus(a.cache, lang, a.regions)
        counts = Counts(rows)
        if a.stems:
            for stem in a.stems:
                stats = sorted(((*stem_stats(counts, g, stem, a.prior), g) for g in GROUPS if g in counts.by_cat),
                               reverse=True)
                print(f"{lang} {stem:<12} {sum(counts.total[w] for w in counts.family(stem)):>7} occorrenze  "
                      + "  ".join(f"{g} z={z:.1f} prec={p:.2f}" for z, p, g in stats[:3]))
            continue
        result[lang] = {}
        for group in a.groups:
            found = candidates(counts, names, group, a.top, a.prior, a.min_sections, a.max_section_share, a.min_places,
                               a.min_precision, a.precision_ratio)
            result[lang][group] = found
            print(f"== {lang} {group}")
            for c in found:
                also = f"  anche {','.join(c['other_groups'])}" if c["other_groups"] else ""
                print(f"  {c['stem']:<14} z={c['z']:<7} prec={c['precision']:<5} {' '.join(c['words'])}{also}")
            if not a.select:
                additions[group] += [c["stem"] for c in found if c["stem"] not in additions[group]]
    if a.json:
        a.json.write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    if a.write:
        # A capo "\n" come il file dell'app, anche su Windows.
        a.write.write_text(with_stems(WORD_LISTS_PATH.read_text(encoding="utf-8"), additions), encoding="utf-8",
                           newline="\n")
        print(f"scritto {a.write}")


if __name__ == "__main__":
    main()
