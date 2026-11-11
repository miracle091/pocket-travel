#!/usr/bin/env python3
"""Rivalutazione a evento: confronta un modello nuovo con quello di riferimento e segnala le regressioni oltre soglia.

Si lancia dopo un nuovo dataset, un aggiornamento di llama.cpp, un modello candidato o un cambio di modello online, con
le risposte al test esteso (eval_gguf.py --extended --answers, o eval_online.py --answers) e, se ci sono, i benchmark
(eval_gguf.py --bench) del riferimento e del nuovo:

    python gpu_lock.py run -- python eval_gguf.py nuovo.gguf --llama-cpp <bin> --extended --answers nuovo.jsonl
    python gpu_lock.py run -- python eval_gguf.py nuovo.gguf --llama-cpp <bin> --bench nuovo-bench.json
    python check_regressions.py base.jsonl nuovo.jsonl --bench base-bench.json nuovo-bench.json

Regressione: le risposte giuste di tutti i negativi (rifiuti) o di tutti i positivi calano di oltre --points punti
percentuali, un singolo tipo di riga peggiora in modo significativo (McNemar esatto, p < 0,05 dopo la correzione di
Holm-Bonferroni sulla famiglia dei tipi di riga con righe appaiate, come in compare_answers.py), la quota di righe
oltre 450 caratteri o nella lingua sbagliata cresce di oltre --points punti (quella fuori dal contesto e' solo un
avviso), i due file hanno
troppe poche righe in comune (appaiate meno dell'80% di quelle del file piu' corto: il confronto non regge), o la mediana di tg (token/s di risposta) cala di oltre --tg-percent. I benchmark si confrontano solo con
stesso dispositivo, thread e token (la chiave di confronto senza impronta del modello e versione di llama.cpp). Esce con
1 se c'e' una regressione, cosi' un comando o uno script che lo lancia se ne accorge; il rapporto di compare_answers.py
dice poi quali tipi di riga sono cambiati. Con le risposte di eval_online.py (campo `variant`) si confronta una
variante sola, --variant (default rag).
"""
import argparse
import json
import sys
from pathlib import Path

from compare_answers import compare, instruction_counts, load

DEFAULT_POINTS = 3.0
DEFAULT_TG_PERCENT = 10.0
MIN_PAIRED_SHARE = 0.8
SIGNIFICANT_P = 0.05


def only_variant(rows, variant):
    """Le righe della variante, se le righe hanno il campo `variant` (eval_online.py); altrimenti tutte."""
    return [r for r in rows if r.get("variant") == variant] if any("variant" in r for r in rows) else rows


def answer_regressions(base_rows, new_rows, points):
    """Righe del rapporto e regressioni per "tutti i neg" e "tutti i pos": quota di giuste prima e dopo."""
    results = compare(base_rows, new_rows)
    lines, regressions = [], []
    for group in ("tutti i neg", "tutti i pos"):
        if group not in results:
            continue
        r = results[group]
        (ka, na), (kb, nb) = r["a"], r["b"]
        if not na or not nb:
            continue
        before, after = 100 * ka / na, 100 * kb / nb
        line = f"{group}: {before:.1f}% -> {after:.1f}% ({after - before:+.1f} punti, p {r['p']:.3f})"
        lines.append(line)
        if before - after > points:
            regressions.append(line)
        if r["paired"] < MIN_PAIRED_SHARE * min(na, nb):
            few = f"{group}: solo {r['paired']} righe appaiate su {na} e {nb}: i due file non coprono le stesse righe"
            lines.append(few)
            regressions.append(few)
    for group in sorted(g for g in results if not g.startswith("tutti")):
        r = results[group]
        if r["worse"] > r["better"] and r["p_holm"] is not None and r["p_holm"] < SIGNIFICANT_P:
            line = f"{group}: peggio in {r['worse']} righe, meglio in {r['better']} (p {r['p']:.3f}, con Holm {r['p_holm']:.3f})"
            lines.append(line)
            regressions.append(line)
    return lines, regressions


def instruction_regressions(base_rows, new_rows, points):
    """Righe del rapporto e regressioni dei controlli sulle istruzioni (instruction_counts di compare_answers.py): la
    quota di righe oltre 450 caratteri o nella lingua sbagliata cresce di oltre points punti. Le risposte giuste contano
    solo il rifiuto: senza questo controllo passerebbero risposte tutte in inglese o troncate al limite di token. Le
    righe fuori dal contesto restano nel rapporto come avviso: la misura e' lessicale e i modelli che parafrasano (gli
    online) darebbero falsi allarmi."""
    if not base_rows or not new_rows:
        return [], []
    before, after = instruction_counts(base_rows), instruction_counts(new_rows)
    lines, regressions = [], []
    for check in before:
        share_before, share_after = 100 * before[check] / len(base_rows), 100 * after[check] / len(new_rows)
        line = f"{check}: {share_before:.1f}% -> {share_after:.1f}% delle righe"
        if check == "fuori dal contesto":
            lines.append(line + " (solo avviso, misura lessicale)")
        else:
            lines.append(line)
            if share_after - share_before > points:
                regressions.append(line)
    return lines, regressions


def comparable(a, b):
    """Due benchmark si confrontano solo sullo stesso dispositivo, con gli stessi thread e token."""
    return all(a[k] == b[k] for k in ("device", "threads", "n_prompt", "n_gen"))


def bench_regressions(base, new, tg_percent):
    """Righe del rapporto e regressioni di tg tra due record di bench_result.py."""
    if not comparable(base, new):
        return [f"benchmark non confrontabili: {base['device']} t{base['threads']} contro {new['device']} t{new['threads']}"], []
    before, after = base["tg"]["median_tps"], new["tg"]["median_tps"]
    change = 100 * (after - before) / before
    line = f"tg: {before:.1f} -> {after:.1f} token/s ({change:+.1f}%)"
    if base["unstable"] or new["unstable"]:
        line += ", misura instabile (CV oltre il 10%): da ripetere"
    return [line], [line] if -change > tg_percent else []


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("base", type=Path, help="risposte del riferimento (JSONL)")
    ap.add_argument("new", type=Path, help="risposte del nuovo (JSONL)")
    ap.add_argument("--variant", default="rag", help="variante di eval_online.py da confrontare (default rag)")
    ap.add_argument("--bench", type=Path, nargs=2, metavar=("BASE", "NEW"), help="benchmark JSON del riferimento e del nuovo")
    ap.add_argument("--points", type=float, default=DEFAULT_POINTS, help="calo massimo delle risposte giuste, in punti")
    ap.add_argument("--tg-percent", type=float, default=DEFAULT_TG_PERCENT, help="calo massimo di tg, in percentuale")
    a = ap.parse_args()
    base_rows, new_rows = only_variant(load(a.base), a.variant), only_variant(load(a.new), a.variant)
    lines, regressions = answer_regressions(base_rows, new_rows, a.points)
    more_lines, more_regressions = instruction_regressions(base_rows, new_rows, a.points)
    lines += more_lines
    regressions += more_regressions
    if a.bench:
        base, new = (json.loads(p.read_text(encoding="utf-8")) for p in a.bench)
        more_lines, more_regressions = bench_regressions(base, new, a.tg_percent)
        lines += more_lines
        regressions += more_regressions
    print("\n".join(lines))
    if regressions:
        print("\nREGRESSIONE oltre soglia:\n" + "\n".join(f"- {r}" for r in regressions))
        sys.exit(1)
    print("\nnessuna regressione oltre soglia")


if __name__ == "__main__":
    main()
