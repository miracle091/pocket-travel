#!/usr/bin/env python3
"""Confronta due file di risposte di eval_gguf.py --answers (per esempio v10 e v11 sullo stesso test esteso).

Per ogni tipo di riga: risposte giuste di A e di B (rifiuto per le righe neg_*, risposta per le pos_*) con l'intervallo
di Wilson al 95%, e il test di McNemar esatto sulle righe presenti in tutti e due i file (stessa domanda, regione e
categoria): b = giuste in A e sbagliate in B, c = sbagliate in A e giuste in B, p bilaterale con la binomiale. Servono
almeno 6 righe cambiate tutte nello stesso verso per p < 0,05. In fondo gli stessi numeri per tutti i negativi e tutti i
positivi insieme, e i controlli sulle istruzioni (risposte fuori dal contesto, oltre 450 caratteri, lingua sbagliata).

Uso: python compare_answers.py <risposte A.jsonl> <risposte B.jsonl> [--names v10 v11]
"""
import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

Z = 1.96


def wilson(k, n):
    """Intervallo di Wilson al 95% di k/n, come (minimo, massimo) in frazione; (0, 1) senza righe."""
    if not n:
        return 0.0, 1.0
    p = k / n
    centre = (p + Z * Z / (2 * n)) / (1 + Z * Z / n)
    half = Z * math.sqrt(p * (1 - p) / n + Z * Z / (4 * n * n)) / (1 + Z * Z / n)
    return max(0.0, centre - half), min(1.0, centre + half)


def mcnemar(b, c):
    """p bilaterale del test di McNemar esatto: binomiale con p = 0,5 sulle b + c righe discordanti."""
    n = b + c
    if not n:
        return 1.0
    tail = sum(math.comb(n, i) for i in range(min(b, c) + 1)) / 2 ** n
    return min(1.0, 2 * tail)


def correct(row):
    """La riga e' giusta: rifiuto per i negativi, risposta per i positivi."""
    return row["refused"] if row["kind"].startswith("neg") else not row["refused"]


def keyed(rows):
    """{(tipo, regione, categoria, domanda, n-esima uguale): riga}: le righe ripetute restano distinte per posizione."""
    seen, out = Counter(), {}
    for r in rows:
        base = (r["kind"], r.get("region"), r.get("category"), r["question"])
        out[base + (seen[base],)] = r
        seen[base] += 1
    return out


def compare(a_rows, b_rows):
    """{gruppo: dati del confronto} per tipo di riga e per "tutti i neg" e "tutti i pos"."""
    a, b = keyed(a_rows), keyed(b_rows)
    groups = defaultdict(lambda: {"a": [], "b": [], "pairs": []})
    for rows, side in ((a, "a"), (b, "b")):
        for key, r in rows.items():
            for g in (r["kind"], "tutti i neg" if r["kind"].startswith("neg") else "tutti i pos"):
                groups[g][side].append(correct(r))
    for key in a.keys() & b.keys():
        for g in (key[0], "tutti i neg" if key[0].startswith("neg") else "tutti i pos"):
            groups[g]["pairs"].append((correct(a[key]), correct(b[key])))
    out = {}
    for g, d in groups.items():
        worse = sum(1 for x, y in d["pairs"] if x and not y)
        better = sum(1 for x, y in d["pairs"] if not x and y)
        out[g] = {"a": (sum(d["a"]), len(d["a"])), "b": (sum(d["b"]), len(d["b"])), "paired": len(d["pairs"]),
                  "worse": worse, "better": better, "p": mcnemar(worse, better)}
    return out


def instruction_counts(rows):
    """Risposte non rifiutate fuori dal contesto, oltre 450 caratteri e nella lingua sbagliata (campi di eval_gguf.py)."""
    answered = [r for r in rows if not r["refused"]]
    return {"fuori dal contesto": sum(r.get("fidelity") == "out" for r in answered),
            "oltre 450 caratteri": sum(bool(r.get("too_long")) for r in answered),
            "lingua sbagliata": sum(bool(r.get("wrong_lang")) for r in rows)}


def pct(k, n):
    return f"{100 * k / n:.1f}%" if n else "-"


def report(a_rows, b_rows, names):
    lines = [f"| gruppo | {names[0]} (IC 95%) | {names[1]} (IC 95%) | appaiate | {names[1]} peggio / meglio | p |",
             "|---|---|---|---|---|---|"]
    results = compare(a_rows, b_rows)
    order = sorted(g for g in results if not g.startswith("tutti")) + ["tutti i neg", "tutti i pos"]
    for g in order:
        if g not in results:
            continue
        r = results[g]
        cells = []
        for side in ("a", "b"):
            k, n = r[side]
            lo, hi = wilson(k, n)
            cells.append(f"{pct(k, n)} ({100 * lo:.0f}-{100 * hi:.0f})")
        flag = " *" if r["p"] < 0.05 else ""
        lines.append(f"| {g} | {cells[0]} | {cells[1]} | {r['paired']} | {r['worse']} / {r['better']} | {r['p']:.3f}{flag} |")
    ca, cb = instruction_counts(a_rows), instruction_counts(b_rows)
    lines.append("")
    lines.append("controlli sulle istruzioni: " + "; ".join(f"{k} {ca[k]} -> {cb[k]}" for k in ca))
    lines.append("(* = p < 0,05 col test di McNemar esatto sulle righe appaiate)")
    return "\n".join(lines)


def load(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("a", type=Path)
    ap.add_argument("b", type=Path)
    ap.add_argument("--names", nargs=2, default=["A", "B"])
    a = ap.parse_args()
    print(report(load(a.a), load(a.b), a.names))


if __name__ == "__main__":
    main()
