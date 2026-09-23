#!/usr/bin/env python3
"""Utilita' condivise da eval_behavior.py e eval_gguf.py: stesse regioni di test di
train_lora.py (seed 42, 1/20) e stesso formato di report, sia base che esteso (generate_eval_set.py).
"""
import json
import random
from pathlib import Path

SFT_DIR = Path(__file__).resolve().parent.parent / "data" / "sft"
REFUSAL = "Il contesto non contiene informazioni"


def load_test_rows(extended):
    """(righe di test, regioni di test). Se extended, le righe vengono da eval_extended.jsonl."""
    rows = [json.loads(l) for l in open(SFT_DIR / "pocket_travel_sft.jsonl", encoding="utf-8")]
    regions = sorted({r["region"] for r in rows})
    held_out = set(random.Random(42).sample(regions, max(1, len(regions) // 20)))
    if extended:
        ext = [json.loads(l) for l in open(SFT_DIR / "eval_extended.jsonl", encoding="utf-8")]
        return ext, held_out
    return [r for r in rows if r["region"] in held_out], held_out


def pct(rows, i):
    return 100 * sum(x[i] for x in rows) / len(rows)


def print_report(stats, test, held_out, extended):
    if extended:
        print(f"test esteso: {len(test)} righe (domande fuori dai template di training)")
        for kind, rs in sorted(stats.items()):
            print(f"{kind}: {len(rs)} righe | rifiuta {pct(rs, 0):.1f}%" + (" (atteso 0%)" if kind.startswith("pos") else " (atteso 100%)"))
        return
    pos, neg = stats["pos"], stats["neg"]
    print(f"test: {len(test)} righe, {len(held_out)} regioni | pos {len(pos)} neg {len(neg)}")
    print(f"POS: rifiuto sbagliato {pct(pos, 0):.1f}% | inizio uguale all'atteso {pct(pos, 1):.1f}%")
    print(f"NEG: rifiuta {pct(neg, 0):.1f}% | risposta identica (tema giusto) {pct(neg, 1):.1f}%")
