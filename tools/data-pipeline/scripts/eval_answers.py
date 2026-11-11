#!/usr/bin/env python3
"""Controlli sulle istruzioni (instruction_checks di eval_common.py) sulle risposte gia' salvate da
eval_gguf.py --answers, senza modello ne' GPU: fedelta' al contesto, risposte troppo lunghe, lingua, <think> iniziale.
Il contesto viene dalle righe del test con cui le risposte sono state generate, unite per tipo, regione e domanda.
Senza --test, sulle risposte di eval_gguf.py --contexts con le sezioni del contesto ("parts" di eval_retrieval.py
--contexts): quante usano la sezione attesa e la pagina della guida generale, e il confronto con una ricerca di riferimento (--baseline).
Con --oracle, sui file del test: le risposte attese passano da score e instruction_checks come se fossero del modello, e
ogni riga che non prende il 100% (positivo letto come rifiuto, negativo senza la frase di rifiuto, risposta fuori dal
contesto, troppo lunga, nella lingua sbagliata o con <think>) e' un errore della riga o del controllo, non di un modello.

Uso: python eval_answers.py <risposte.jsonl> [...] --test <eval_extended.jsonl o dataset>
     python eval_answers.py <risposte di eval_gguf.py --contexts> [...]
     python eval_answers.py --oracle <eval_extended.jsonl o dataset> [...]
"""
import argparse
import json
from collections import Counter
from pathlib import Path

from eval_common import (MAX_ANSWER_CHARS, TEST_REGIONS, instruction_checks, print_context_use_report, print_instruction_report,
                         question_lang, question_of, score)


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


def oracle_failures(test):
    """[(riga, cause)] per le righe la cui risposta attesa, passata come risposta del modello da score e
    instruction_checks, non prende il 100%: e' un errore della riga o del controllo, non di un modello. Il tema del
    rifiuto e l'"inizio uguale" di score non si controllano: con la risposta attesa sono sempre giusti."""
    out = []
    for row in test:
        want, neg = row["messages"][1]["content"], row["kind"].startswith("neg")
        stats = {}
        score(stats, row, want)
        strict = stats[row["kind"]][0][0]
        checks = instruction_checks(row, want)
        causes = [cause for cause, failed in (
            ("negativo senza la frase di rifiuto", neg and not strict),
            ("positivo con la frase di rifiuto", not neg and strict),
            ("positivo letto come rifiuto con parole proprie", not neg and not strict and checks["refused"]),
            ("fuori dal contesto", checks["fidelity"] == "out"),
            (f"oltre {MAX_ANSWER_CHARS} caratteri", checks["too_long"]),
            ("lingua sbagliata", checks["wrong_lang"]),
            ("<think> iniziale", checks["think_start"])) if failed]
        if causes:
            out.append((row, causes))
    return out


def print_oracle_report(test):
    failures = oracle_failures(test)
    print(f"oracolo: {len(failures)} righe su {len(test)} con la risposta attesa che non passa i controlli")
    by_cause = {}
    for row, causes in failures:
        for cause in causes:
            by_cause.setdefault(cause, []).append(row)
    for cause, rows in sorted(by_cause.items(), key=lambda item: -len(item[1])):
        kinds = Counter(r["kind"] for r in rows)
        print(f"{cause}: {len(rows)} ({', '.join(f'{k} {n}' for k, n in sorted(kinds.items()))})")
        for r in rows:
            print(f"  {r['kind']} {r['region']} | {question_of(r).strip()} | {r['messages'][1]['content'][:200].replace(chr(10), ' ')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("answers", type=Path, nargs="+")
    ap.add_argument("--test", type=Path, help="file del test con cui sono state generate le risposte")
    ap.add_argument("--baseline", help="senza --test: la ricerca (campo \"search\") con cui confrontare le altre; "
                                       "di default la prima delle righe")
    ap.add_argument("--oracle", action="store_true", help="i file sono test estesi o dataset (di questi solo le regioni di "
                                                          "test, il set di validazione di train_lora.py): le risposte attese "
                                                          "passano dai controlli come se fossero del modello")
    a = ap.parse_args()
    if a.oracle:
        for path in a.answers:
            with open(path, encoding="utf-8") as f:
                test = [json.loads(line) for line in f]
            if not path.name.startswith("eval_extended"):
                test = [r for r in test if r["region"] in TEST_REGIONS]
            print(f"== {path.name}")
            print_oracle_report(test)
        return
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
