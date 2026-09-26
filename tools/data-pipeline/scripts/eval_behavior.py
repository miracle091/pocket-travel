#!/usr/bin/env python3
"""Misura il comportamento di un modello fuso sulle regioni di test (le stesse di train_lora.py).

Uso: HIP_VISIBLE_DEVICES=<gpu> python eval_behavior.py [<cartella merged>]
Metriche: sui positivi, rifiuti sbagliati e risposta con lo stesso inizio dell'atteso;
sui negativi, quanti rifiutano e quanti danno la risposta identica (rifiuto sul tema giusto).
Il test qui non scarta le righe oltre --max-len di train_lora.py: le regioni sono le stesse,
le righe possono essere qualcuna in piu'.
"""
import argparse

try:
    import unsloth  # noqa: F401  patch di compatibilita' Windows/ROCm
except ImportError:
    pass
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

from eval_common import REFUSAL, SFT_DIR, load_test_rows, print_report
from status import Progress, phase

ap = argparse.ArgumentParser()
ap.add_argument("model", nargs="?", default=str(SFT_DIR / "run-smollm2-135m" / "merged"))
ap.add_argument("--batch", type=int, default=8)
ap.add_argument("--extended", action="store_true", help="test esteso (generate_eval_set.py): rifiuto per tipo di riga")
ap.add_argument("--errors", action="store_true", help="con --extended: elenca i positivi rifiutati (categoria, domanda)")
a = ap.parse_args()

# bf16 nativo solo se supportato e capability >= 8, come train_lora.py (NVIDIA e AMD), altrimenti fp16
BF16 = torch.cuda.is_bf16_supported() and torch.cuda.get_device_capability()[0] >= 8
DTYPE = torch.bfloat16 if BF16 else torch.float16

phase("caricamento modello", a.model)
tok = AutoTokenizer.from_pretrained(a.model, padding_side="left")
model = AutoModelForCausalLM.from_pretrained(a.model, dtype=DTYPE).to("cuda").eval()

test, held_out = load_test_rows(a.extended)
phase("eval", f"{len(test)} righe {'(test esteso)' if a.extended else '(test base)'}, batch {a.batch}")
progress = Progress("eval", len(test), "riga", every=30)

stats = {}  # tipo di riga -> [(ha rifiutato, risposta ok)]
for i in range(0, len(test), a.batch):
    batch = test[i:i + a.batch]
    enc = tok.apply_chat_template([r["messages"][:1] for r in batch], add_generation_prompt=True,
                                  return_tensors="pt", return_dict=True, padding=True,
                                  enable_thinking=False).to("cuda")
    with torch.no_grad():
        out = model.generate(**enc, max_new_tokens=110, do_sample=False)
    for r, o in zip(batch, out):
        got = tok.decode(o[enc["input_ids"].shape[1]:], skip_special_tokens=True).strip()
        want = r["messages"][1]["content"]
        same = got[:60] == want[:60] if r["kind"].startswith("pos") else got == want
        stats.setdefault(r["kind"], []).append((got.startswith(REFUSAL), same))
        if a.errors and r["kind"].startswith("pos") and got.startswith(REFUSAL):
            print("RIFIUTO SBAGLIATO", r["category"], "|", r["messages"][0]["content"].rsplit("DOMANDA: ", 1)[1])
    done = sum(len(rs) for rs in stats.values())
    refused = {p: [x[0] for k, rs in stats.items() if k.startswith(p) for x in rs] for p in ("pos", "neg")}
    progress.update(done, " · ".join(f"{p} rifiutati {100 * sum(v) // len(v)}%" for p, v in refused.items() if v))

print_report(stats, test, held_out, a.extended)
