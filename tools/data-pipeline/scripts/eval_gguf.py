#!/usr/bin/env python3
"""Come eval_behavior.py, ma passando dal file .gguf con il runtime llama.cpp (llama-cli), non da un
checkpoint HF: verifica il comportamento del modello quantizzato che l'app scaricherebbe davvero.

Uso (richiede lo stesso clone/build di llama.cpp di convert_gguf.py, con llama-cli compilato:
`cmake -B build && cmake --build build --target llama-cli`):
  python eval_gguf.py <file .gguf> --llama-cpp <path al clone di llama.cpp> [--extended] [--limit N]
Stesse regioni di test di train_lora.py (seed 42, 1/20) e stesse metriche di eval_behavior.py; decodifica
greedy (--temp 0 --top-k 1). Il prompt viene formattato qui a mano nello stesso ChatML "grezzo" che
OnDeviceLlmEngine/ai_chat.cpp costruisce a runtime (un solo turno utente, nessun turno system — vedi
chat_add_and_format/reset_long_term_states in ai_chat.cpp), non il chat_template jinja scritto nel GGUF
da convert_gguf.py: l'app non lo usa (common_chat_format_single e' chiamato con use_jinja=false, quindi
passa dal formatter CHATML hardcoded di llama.cpp). -no-cnv disabilita il wrapping automatico di
llama-cli, cosi' il prompt passato con -p e' esattamente quello che finisce nel modello.
"""
import argparse
import shutil
import subprocess
from pathlib import Path

from eval_common import REFUSAL, load_test_rows, print_report

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("model", type=Path)
ap.add_argument("--llama-cpp", type=Path, required=True, help="cartella del clone locale di llama.cpp (tag v0.4.1)")
ap.add_argument("--extended", action="store_true", help="test esteso (generate_eval_set.py): rifiuto per tipo di riga")
ap.add_argument("--limit", type=int, default=0, help="0 = tutte le righe di test")
a = ap.parse_args()

llama_cli = shutil.which("llama-cli") or next(
    (str(p) for p in (a.llama_cpp / "build" / "bin" / "llama-cli", a.llama_cpp / "build" / "llama-cli") if p.exists()),
    None,
)
if not llama_cli:
    raise SystemExit(
        f"llama-cli non trovato (ne' nel PATH ne' in {a.llama_cpp / 'build'}): compilalo con "
        "`cmake -B build && cmake --build build --target llama-cli` nel clone di llama.cpp",
    )

test, held_out = load_test_rows(a.extended)
if a.limit:
    test = test[:a.limit]


def ask(content: str) -> str:
    prompt = f"<|im_start|>user\n{content}<|im_end|>\n<|im_start|>assistant\n"
    result = subprocess.run(
        [llama_cli, "-m", str(a.model), "-p", prompt, "-n", "110", "--temp", "0", "--top-k", "1",
         "-no-cnv", "--simple-io", "--no-display-prompt", "--no-warmup"],
        capture_output=True, text=True, check=True,
    )
    return result.stdout.strip()


stats = {}  # tipo di riga -> [(ha rifiutato, risposta ok)]
for n, r in enumerate(test, 1):
    got = ask(r["messages"][0]["content"])
    want = r["messages"][1]["content"]
    same = got[:60] == want[:60] if r["kind"].startswith("pos") else got == want
    stats.setdefault(r["kind"], []).append((got.startswith(REFUSAL), same))
    print(f"{n}/{len(test)} {r['kind']} rifiuta={got.startswith(REFUSAL)} uguale={same}", flush=True)

print_report(stats, test, held_out, a.extended)
