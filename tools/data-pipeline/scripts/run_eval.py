#!/usr/bin/env python3
"""Ciclo eval completo su un modello fuso: rigenera eval_extended.jsonl se manca, poi lancia
eval_behavior.py sia in modalita' base che estesa (generate_eval_set.py), in un solo comando.

Uso: python run_eval.py [<cartella merged>] [opzioni di eval_behavior.py, es. --errors]
"""
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SFT_DIR = HERE.parent / "data" / "sft"

if __name__ == "__main__":
    args = sys.argv[1:]
    if not (SFT_DIR / "eval_extended.jsonl").exists():
        print("-- eval_extended.jsonl assente: lo rigenero (generate_eval_set.py)")
        subprocess.run([sys.executable, str(HERE / "generate_eval_set.py")], check=True)

    for label, flags in (("base", []), ("esteso", ["--extended"])):
        print(f"\n=== test {label} ===")
        subprocess.run([sys.executable, str(HERE / "eval_behavior.py"), *args, *flags], check=True)
