#!/usr/bin/env python3
"""Controlla un dataset SFT (generate_sft.py) prima del training e, con --out, ne scrive una copia pulita.

Ogni riga e' {"messages": [prompt dell'app, risposta], "kind": "pos"|"neg", "region", "category", "translated"}; il
prompt e' quello di on_device_prompt (it o en), da cui si ricavano contesto e domanda.

Errori (con --strict il codice di uscita e' 1 se ce n'e' almeno uno):
- riga malformata, o prompt in una lingua diversa da quella del dataset;
- contesto oltre MAX_CONTEXT, risposta vuota o oltre MAX_ANSWER;
- risposta positiva con frasi che non sono nel contesto (spazi normalizzati). Le categorie con risposte non copiate dalle
  guide si verificano a modo loro: VACCINAZIONI senza punteggiatura e trattini (il riassunto e' riscritto in frasi), VICINO e
  PARTENZE (sft_nearby.py) solo sui numeri, che devono stare tutti nel blocco di contesto. DISTANZE (il blocco calcolato e'
  nel contesto), FATTI_RAPIDI, NOTE e le guide sono copiate parola per parola; i rifiuti non si controllano;
- U+FFFD o markup residuo ({{, }}, [[, ]], <ref, &amp;, &nbsp;, "()") nel contesto o nella risposta;
- regione che e' una sottoregione di una regione di test (le righe delle regioni di test vere restano nel file: train_lora.py
  le separa nel test);
- riga identica a una precedente, o stesso (contesto, domanda) gia' visto.

Avvisi e statistiche: quota di rifiuti (totale e per categoria), righe per categoria e per regione, domande e risposte piu'
ripetute, domande oltre il limite, risposte con piu' di 3 frasi o nella lingua sbagliata (euristica prudente sulle parole
funzione), righe con stessa domanda e stessa risposta ma contesto diverso, kind incoerente con la risposta e, con --eval,
righe di training con la stessa domanda e regione, o lo stesso contesto, di una riga del test esteso.

--out scrive le righe originali (mai riscritte) senza quelle con errori, senza i duplicati (resta la prima) e con al
massimo --max-question-repeats righe per la stessa domanda (senza l'opzione: OFF_TOPIC_MAX_USES per la categoria OFF,
nessun limite per le altre).

Uso: python audit_sft.py <dataset.jsonl> [--lang it|en] [--eval <eval_extended.jsonl>] [--out <pulito.jsonl>]
     [--max-question-repeats N] [--strict]
"""
import argparse
import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

import generate_sft_dataset as it
import generate_sft_dataset_en as en
from eval_common import REFUSAL, REFUSAL_EN, TEST_REGIONS

# (inizio del prompt fino a "CONTESTO: ", separatore prima della domanda): ricavati dalle funzioni vere dell'app
PROMPTS = {"it": (it.on_device_prompt("", "").split("CONTESTO: ")[0] + "CONTESTO: ", "\n\nDOMANDA: "),
           "en": (en.on_device_prompt("", "").split("CONTEXT: ")[0] + "CONTEXT: ", "\n\nQUESTION: ")}
SPLIT = {"it": it.SENTENCE_END, "en": en.SENTENCE_END}
FALLBACKS = {it.FALLBACK_CONTEXT, en.FALLBACK_CONTEXT}
MARKUP = ("{{", "}}", "[[", "]]", "<ref", "&amp;", "&nbsp;", "()")
# Categorie con risposta sintetica: VICINO e PARTENZE si confrontano sui numeri, VACCINAZIONI senza punteggiatura
NUMBERS_ONLY = {"VICINO", "PARTENZE"}
LOOSE = {"VACCINAZIONI"}
# Parole funzione senza equivalente nell'altra lingua (niente "a", "e", "in", "no", "me", "per"): la risposta e' nella
# lingua sbagliata solo con almeno WRONG_MIN parole dell'altra e piu' del doppio di quelle della sua
STOP = {"it": set("il lo gli di del della dei delle che con non sono una nel nella nelle anche più come ma si un al "
                  "dal dalla sul sulla questo questa sono molto tra fra poi".split()),
        "en": set("the and of to is are for with that this you your from by on at or be it as an was were which "
                  "their there have has not but can will".split())}
WRONG_MIN = 3
MIN_WORDS = 8
OFF_CATEGORY = "OFF"
TOP = 5


def prompt_lang(content):
    """"it" o "en" secondo l'inizio del prompt, None se non e' un prompt dell'app."""
    return next((l for l, (head, _) in PROMPTS.items() if content.startswith(head)), None)


def parse_row(row):
    """(contesto, domanda, risposta) di una riga; ValueError se non e' nel formato del dataset."""
    try:
        user, assistant = row["messages"]
        if user["role"] != "user" or assistant["role"] != "assistant":
            raise ValueError("ruoli")
        content, answer = user["content"], assistant["content"]
        lang = prompt_lang(content)
        if lang is None or not isinstance(answer, str):
            raise ValueError("prompt")
        head, sep = PROMPTS[lang]
        context, question = content[len(head):].rsplit(sep, 1)
        return context, question, answer
    except (KeyError, TypeError, ValueError, AttributeError) as e:
        raise ValueError(f"riga malformata: {e}") from e


def is_refusal(answer):
    return answer.startswith((REFUSAL, REFUSAL_EN))


def squash(text):
    """Spazi e a capo normalizzati."""
    return " ".join(text.split())


def loose(text):
    """Solo le parole, in minuscolo: per i riassunti riscritti in frasi (vedi LOOSE)."""
    return " ".join(re.findall(r"\w+", text.lower()))


def pieces(answer):
    """Frasi della risposta, al massimo fini (SENTENCE_END italiano): ogni frase copiata dalle guide ne contiene un numero
    intero, anche se l'inglese le divide meno."""
    out = []
    for line in answer.splitlines():
        out += [x.strip().removeprefix("• ") for x in re.split(SPLIT["it"], line.strip())]
    return [x for x in out if x]


def count_sentences(answer, lang):
    """Frasi della risposta, riga per riga come sentences() di generate_sft_dataset.py."""
    return sum(len([x for x in re.split(SPLIT[lang], line.strip()) if x.strip()]) for line in answer.splitlines())


def is_extractive(category, context, answer):
    """True se la risposta positiva e' fatta di frasi del contesto (o, per le categorie sintetiche, dei suoi numeri)."""
    if category in NUMBERS_ONLY:
        return set(re.findall(r"\d+", answer)) <= set(re.findall(r"\d+", context))
    norm = loose if category in LOOSE else squash
    ctx = norm(context)
    return all(norm(p) in ctx for p in pieces(answer))


def wrong_language(answer, lang):
    """True se la risposta ha molte parole funzione dell'altra lingua e poche della sua."""
    words = re.findall(r"[a-zà-ÿ]+", answer.lower())
    if len(words) < MIN_WORDS:
        return False
    other = "en" if lang == "it" else "it"
    wrong, right = sum(w in STOP[other] for w in words), sum(w in STOP[lang] for w in words)
    return wrong >= WRONG_MIN and wrong > 2 * right


def hard_errors(row, lang):
    """Motivi per cui la riga e' sbagliata (lista vuota se va bene), senza i duplicati: sono tra righe (find_drops)."""
    try:
        context, question, answer = parse_row(row)
    except ValueError:
        return ["malformata"]
    kind, region, category = row.get("kind"), row.get("region"), row.get("category")
    if kind not in ("pos", "neg") or not isinstance(region, str) or not region or not isinstance(category, str):
        return ["malformata"]
    errors = []
    if lang and prompt_lang(row["messages"][0]["content"]) != lang:
        errors.append("prompt-lingua")
    if len(context) > it.MAX_CONTEXT:
        errors.append("contesto-lungo")
    if not answer.strip():
        errors.append("risposta-vuota")
    elif len(answer) > it.MAX_ANSWER:
        errors.append("risposta-lunga")
    elif kind == "pos" and not is_refusal(answer) and not is_extractive(category, context, answer):
        errors.append("non-estrattiva")
    if "�" in context + answer:
        errors.append("fffd")
    if any(m in context or m in answer for m in MARKUP):
        errors.append("markup-residuo")
    if region not in TEST_REGIONS and not region.startswith("citta:") and any(region.startswith(f"{t}-") for t in TEST_REGIONS):
        errors.append("sottoregione-di-test")
    return errors


def repeat_limit(category, max_repeats):
    """Quante righe possono avere la stessa domanda: --max-question-repeats, altrimenti solo la categoria OFF ha un limite."""
    return max_repeats or (it.OFF_TOPIC_MAX_USES if category == OFF_CATEGORY else None)


def find_drops(entries, lang, max_repeats=None):
    """([errori di ogni riga], [motivo per cui la riga si toglie dal file pulito o None]); [entries] = [(riga di testo,
    riga JSON o None)]. Un duplicato e' un errore, una domanda oltre il limite no: si toglie e basta."""
    errors, drops, seen_raw, seen_pair, asked = [], [], set(), set(), Counter()
    for raw, row in entries:
        errs = hard_errors(row, lang) if row is not None else ["malformata"]
        if not errs:
            context, question, _ = parse_row(row)
            if raw in seen_raw:
                errs = ["riga-duplicata"]
            elif (context, question) in seen_pair:
                errs = ["contesto-domanda-duplicati"]
            seen_raw.add(raw)
            seen_pair.add((context, question))
        errors.append(errs)
        if errs:
            drops.append(errs[0])
            continue
        limit = repeat_limit(row["category"], max_repeats)
        if limit and asked[question] >= limit:
            drops.append("domanda-ripetuta")
        else:
            asked[question] += 1
            drops.append(None)
    return errors, drops


def load(path):
    """[(riga di testo, riga JSON o None se non e' JSON valido)] senza le righe vuote."""
    out = []
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        if raw.strip():
            try:
                row = json.loads(raw)
            except ValueError:
                row = None
            out.append((raw, row if isinstance(row, dict) else None))
    return out


def guess_lang(path, entries):
    """Lingua dal nome (*.en.jsonl, *.it.jsonl), altrimenti dal prompt piu' frequente."""
    for lang in PROMPTS:
        if str(path).endswith(f".{lang}.jsonl"):
            return lang
    counts = Counter()
    for _, row in entries:
        try:
            counts[prompt_lang(row["messages"][0]["content"])] += 1
        except (KeyError, TypeError, IndexError):
            pass
    counts.pop(None, None)
    return counts.most_common(1)[0][0] if counts else "it"


def leakage(rows, eval_rows):
    """(righe con stessa domanda e regione, righe con lo stesso contesto) di una riga del test esteso: solo righe di
    training (le regioni di test non si addestrano) e senza il contesto di fallback, uguale per tutti."""
    pairs, contexts = set(), set()
    for r in eval_rows:
        c, q, _ = parse_row(r)
        pairs.add((q, r.get("region")))
        contexts.add(squash(c))
    same_q = same_ctx = 0
    for r in rows:
        if r["region"] in TEST_REGIONS:
            continue
        c, q, _ = parse_row(r)
        same_q += (q, r["region"]) in pairs
        same_ctx += c not in FALLBACKS and squash(c) in contexts
    return same_q, same_ctx


def spread(counter):
    """"min/mediana/max" dei conteggi."""
    v = list(counter.values())
    return f"{min(v)}/{statistics.median(v):g}/{max(v)}" if v else "-"


def short(text, n=70):
    text = squash(text)
    return text if len(text) <= n else text[:n - 1] + "…"


def lines_of(indexes, n=3):
    return ", ".join(str(i + 1) for i in indexes[:n]) + (", ..." if len(indexes) > n else "")


def report(path, entries, lang, max_repeats, eval_rows=None):
    """Stampa il rapporto; ritorna (numero di righe con errori, righe da togliere per motivo)."""
    errors, drops = find_drops(entries, lang, max_repeats)
    rows = [(i, row) for i, (_, row) in enumerate(entries) if row is not None and "malformata" not in errors[i]]
    parsed = [(i, row, *parse_row(row)) for i, row in rows]
    kinds = Counter(row["kind"] for _, row in rows)
    print(f"audit di {path} (lingua {lang}): {len(entries)} righe, pos {kinds['pos']} neg {kinds['neg']}")

    bad = [i for i, e in enumerate(errors) if e]
    print(f"errori: {len(bad)} righe" if bad else "errori: nessuno")
    where = defaultdict(list)
    for i in bad:
        for reason in errors[i]:
            where[reason].append(i)
    for reason, idx in sorted(where.items(), key=lambda x: -len(x[1])):
        print(f"  {reason}: {len(idx)} (righe {lines_of(idx)})")

    cats, refs = Counter(r["category"] for _, r in rows), Counter(r["category"] for _, r in rows if r["kind"] == "neg")
    share = kinds["neg"] / max(len(rows), 1)
    print(f"rifiuti: {share:.1%} (atteso ~25%) | per categoria: "
          + ", ".join(f"{c} {refs[c] / n:.0%}" for c, n in cats.most_common()))
    print("righe per categoria: " + ", ".join(f"{c} {n}" for c, n in cats.most_common()))
    regions = Counter(r["region"] for _, r in rows)
    top = ", ".join(f"{r} {n}" for r, n in regions.most_common(TOP))
    print(f"righe per regione: {len(regions)} regioni, min/mediana/max {spread(regions)} | piu' righe: {top}")
    tests = sum(n for r, n in regions.items() if r in TEST_REGIONS)
    print(f"righe delle regioni di test (non vanno in training): {tests}")

    asked = Counter(q for _, _, _, q, _ in parsed)
    print(f"domande: {len(asked)} distinte | piu' ripetute: "
          + "; ".join(f"{n}x {short(q, 50)}" for q, n in asked.most_common(15)))
    over = [i for i, d in enumerate(drops) if d == "domanda-ripetuta"]
    limit = f"--max-question-repeats {max_repeats}" if max_repeats else f"{it.OFF_TOPIC_MAX_USES} per OFF"
    print(f"domande oltre il limite ({limit}): {len(over)} righe in piu' su "
          f"{len({parse_row(entries[i][1])[1] for i in over})} domande")

    answers = Counter(a for _, _, _, _, a in parsed if not is_refusal(a))
    repeated = [(a, n) for a, n in answers.most_common() if n > 1]
    print(f"risposte (senza rifiuti) usate piu' volte: {len(repeated)} | piu' ripetute: "
          + ("; ".join(f"{n}x {short(a, 50)}" for a, n in repeated[:TOP]) if repeated else "-"))

    long_ = [i for i, _, _, _, a in parsed if count_sentences(a, lang) > 3]
    print(f"risposte con piu' di 3 frasi: {len(long_)}" + (f" (righe {lines_of(long_)})" if long_ else ""))
    wrong = [i for i, _, _, _, a in parsed if wrong_language(a, lang)]
    print(f"risposte forse nella lingua sbagliata: {len(wrong)}" + (f" (righe {lines_of(wrong)})" if wrong else ""))
    pairs = defaultdict(set)
    for _, _, c, q, a in parsed:
        pairs[q, a].add(c)
    near = sum(len(c) - 1 for c in pairs.values() if len(c) > 1)
    near_pos = sum(len(c) - 1 for (q, a), c in pairs.items() if len(c) > 1 and not is_refusal(a))
    print(f"stessa domanda e risposta con contesto diverso: {near} righe in piu' ({near_pos} senza i rifiuti)")
    mixed = [i for i, row, _, _, a in parsed if (row["kind"] == "neg") != is_refusal(a)]
    print(f"kind incoerente con la risposta (neg senza rifiuto, pos con rifiuto): {len(mixed)}"
          + (f" (righe {lines_of(mixed)})" if mixed else ""))

    if eval_rows is not None:
        same_q, same_ctx = leakage([row for _, row in rows], eval_rows)
        print(f"test esteso: {len(eval_rows)} righe | training con stessa domanda e regione: {same_q}, stesso contesto: {same_ctx}")

    removed = Counter(d for d in drops if d)
    return len(bad), removed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset", type=Path)
    ap.add_argument("--lang", choices=("it", "en"), help="lingua del dataset (default: dal nome del file o dal prompt)")
    ap.add_argument("--eval", type=Path, help="test esteso (eval_extended.jsonl) per cercare le righe che ne ripetono")
    ap.add_argument("--out", type=Path, help="scrive qui il dataset pulito")
    ap.add_argument("--max-question-repeats", type=int, help="righe al massimo per la stessa domanda nel file pulito")
    ap.add_argument("--strict", action="store_true", help="codice di uscita 1 se ci sono errori")
    a = ap.parse_args()
    entries = load(a.dataset)
    lang = a.lang or guess_lang(a.dataset, entries)
    eval_rows = [r for _, r in load(a.eval) if r is not None] if a.eval else None
    n_bad, removed = report(a.dataset, entries, lang, a.max_question_repeats, eval_rows)
    if a.out:
        drops = find_drops(entries, lang, a.max_question_repeats)[1]
        with open(a.out, "w", encoding="utf-8", newline="\n") as f:
            for (raw, _), drop in zip(entries, drops):
                if drop is None:
                    f.write(raw + "\n")
        kept = len(entries) - sum(removed.values())
        print(f"scritto {a.out}: {kept} righe, tolte {sum(removed.values())}"
              + (" (" + ", ".join(f"{r} {n}" for r, n in removed.most_common()) + ")" if removed else ""))
    if a.strict and n_bad:
        sys.exit(1)


if __name__ == "__main__":
    main()
