#!/usr/bin/env python3
"""Ciclo eval completo su un modello fuso: rigenera il test esteso se manca, poi lancia
eval_behavior.py sia in modalita' base che estesa (generate_eval_set.py), in un solo comando.
Per un modello addestrato sul dataset inglese (run.json con pocket_travel_sft.en.jsonl) il test esteso e'
eval_extended.en.jsonl, di generate_eval_set_en.py.

Uso: python run_eval.py [<cartella merged>] [opzioni di eval_behavior.py, es. --errors]
"""
import subprocess
import sys
from pathlib import Path

from eval_common import extended_file, run_dataset

HERE = Path(__file__).resolve().parent
SFT_DIR = HERE.parent / "data" / "sft"

if __name__ == "__main__":
    args = sys.argv[1:]
    # stessa cartella di default di eval_behavior.py
    model = args[0] if args and not args[0].startswith("-") else str(SFT_DIR / "run-smollm2-135m" / "merged")
    extended = extended_file(run_dataset(model))
    if not (SFT_DIR / extended).exists():
        generator = "generate_eval_set_en.py" if extended.endswith(".en.jsonl") else "generate_eval_set.py"
        print(f"-- {extended} assente: lo rigenero ({generator})")
        subprocess.run([sys.executable, str(HERE / generator)], check=True)

    for label, flags in (("base", []), ("esteso", ["--extended"])):
        print(f"\n=== test {label} ===")
        subprocess.run([sys.executable, str(HERE / "eval_behavior.py"), *args, *flags], check=True)
