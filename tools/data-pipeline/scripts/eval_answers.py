#!/usr/bin/env python3
"""Controlli sulle istruzioni (instruction_checks di eval_common.py) sulle risposte gia' salvate da
eval_gguf.py --answers, senza modello ne' GPU: fedelta' al contesto, risposte troppo lunghe, lingua, <think> iniziale.
Il contesto viene dalle righe del test con cui le risposte sono state generate, unite per tipo, regione e domanda.
Senza --test, sulle risposte di eval_gguf.py --contexts con le sezioni del contesto ("parts" di eval_retrieval.py
--contexts): quante usano la sezione attesa e la pagina della guida generale, e il confronto con una ricerca di riferimento (--baseline).

Uso: python eval_answers.py <risposte.jsonl> [...] --test <eval_extended.jsonl o dataset>
     python eval_answers.py <risposte di eval_gguf.py --contexts> [...]
"""
import argparse
import json
from pathlib import Path

from eval_common import instruction_checks, print_context_use_report, print_instruction_report, question_lang, question_of


def key(kind, region, question):
    return kind, region, question.strip()


def checked_rows(answers, test):
    """Le risposte con i campi di instruction_checks, calcolati sulla riga del test con la stessa chiave (tipo, regione,
    domanda); le righe con la stessa chiave si usano nell'ordine in cui compaiono."""
    by_key = {}
    for row in test:
        by_key.setdefault(key(row["kind"], row["region"], question_of(row)), []).append(row)
    out = []
    for a in answers:
        rows = by_key.get(key(a["kind"], a["region"], a["question"]))
        if not rows:
            raise SystemExit(f"riga del test non trovata per la risposta: {a['kind']} {a['region']} {a['question']}")
        row = rows.pop(0)
        out.append({"kind": a["kind"], "question_lang": a.get("question_lang") or question_lang(row), "question": a["question"],
                    "got": a["got"], **instruction_checks(row, a["got"])})
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("answers", type=Path, nargs="+")
    ap.add_argument("--test", type=Path, help="file del test con cui sono state generate le risposte")
    ap.add_argument("--baseline", help="senza --test: la ricerca (campo \"search\") con cui confrontare le altre; "
                                       "di default la prima delle righe")
    a = ap.parse_args()
    if not a.test:
        rows = []
        for path in a.answers:
            with open(path, encoding="utf-8") as f:
                rows += [json.loads(line) for line in f]
        if not any("parts" in r for r in rows):
            ap.error("senza --test servono le risposte di eval_gguf.py --contexts con le sezioni del contesto (\"parts\")")
        print_context_use_report(rows, a.baseline)
        return
    with open(a.test, encoding="utf-8") as f:
        test = [json.loads(line) for line in f]
    for path in a.answers:
        with open(path, encoding="utf-8") as f:
            answers = [json.loads(line) for line in f]
        print(f"== {path.name}: {len(answers)} risposte")
        print_instruction_report(checked_rows(answers, test))


if __name__ == "__main__":
    main()
