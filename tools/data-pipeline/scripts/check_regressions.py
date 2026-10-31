#!/usr/bin/env python3
"""Rivalutazione a evento: confronta un modello nuovo con quello di riferimento e segnala le regressioni oltre soglia.

Si lancia dopo un nuovo dataset, un aggiornamento di llama.cpp, un modello candidato o un cambio di modello online, con
le risposte al test esteso (eval_gguf.py --extended --answers, o eval_online.py --answers) e, se ci sono, i benchmark
(eval_gguf.py --bench) del riferimento e del nuovo:

    python gpu_lock.py run -- python eval_gguf.py nuovo.gguf --llama-cpp <bin> --extended --answers nuovo.jsonl
    python gpu_lock.py run -- python eval_gguf.py nuovo.gguf --llama-cpp <bin> --bench nuovo-bench.json
    python check_regressions.py base.jsonl nuovo.jsonl --bench base-bench.json nuovo-bench.json

Regressione: le risposte giuste di tutti i negativi (rifiuti) o di tutti i positivi calano di oltre --points punti
percentuali, o la mediana di tg (token/s di risposta) cala di oltre --tg-percent. I benchmark si confrontano solo con
stesso dispositivo, thread e token (la chiave di confronto senza impronta del modello e versione di llama.cpp). Esce con
1 se c'e' una regressione, cosi' un comando o uno script che lo lancia se ne accorge; il rapporto di compare_answers.py
dice poi quali tipi di riga sono cambiati.
"""
import argparse
import json
import sys
from pathlib import Path

from compare_answers import compare, load

DEFAULT_POINTS = 3.0
DEFAULT_TG_PERCENT = 10.0


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
    ap.add_argument("--bench", type=Path, nargs=2, metavar=("BASE", "NEW"), help="benchmark JSON del riferimento e del nuovo")
    ap.add_argument("--points", type=float, default=DEFAULT_POINTS, help="calo massimo delle risposte giuste, in punti")
    ap.add_argument("--tg-percent", type=float, default=DEFAULT_TG_PERCENT, help="calo massimo di tg, in percentuale")
    a = ap.parse_args()
    lines, regressions = answer_regressions(load(a.base), load(a.new), a.points)
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
