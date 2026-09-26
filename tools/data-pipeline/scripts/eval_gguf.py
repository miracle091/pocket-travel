#!/usr/bin/env python3
"""Come eval_behavior.py, ma passando dal file .gguf con il runtime llama.cpp (llama-cli), non da un
checkpoint HF: verifica il comportamento del modello quantizzato che l'app scaricherebbe davvero.

Uso (richiede lo stesso clone/build di llama.cpp di convert_gguf.py, con llama-cli compilato:
`cmake -B build && cmake --build build --target llama-cli`):
  python eval_gguf.py <file .gguf> --llama-cpp <path al clone di llama.cpp> [--extended] [--limit N]
Stesse regioni di test di train_lora.py (TEST_REGIONS in eval_common.py) e stesse metriche di eval_behavior.py; decodifica
greedy (--temp 0 --top-k 1). Il prompt viene formattato qui a mano nello stesso ChatML "grezzo" che
OnDeviceLlmEngine/ai_chat.cpp costruisce a runtime (un solo turno utente, nessun turno system — vedi
chat_add_and_format/reset_long_term_states in ai_chat.cpp), non il chat_template jinja scritto nel GGUF
da convert_gguf.py: l'app non lo usa (common_chat_format_single e' chiamato con use_jinja=false, quindi
passa dal formatter CHATML hardcoded di llama.cpp). -no-cnv disabilita il wrapping automatico di
llama-cli, cosi' il prompt passato con -p e' esattamente quello che finisce nel modello.
"""
import argparse
import json
import shutil
import subprocess
from pathlib import Path

from eval_common import REFUSAL, load_test_rows, print_report
from status import Progress, phase

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("model", type=Path)
ap.add_argument("--llama-cpp", type=Path, required=True, help="cartella del clone locale di llama.cpp (tag v0.4.1)")
ap.add_argument("--extended", action="store_true", help="test esteso (generate_eval_set.py): rifiuto per tipo di riga")
ap.add_argument("--limit", type=int, default=0, help="0 = tutte le righe di test")
ap.add_argument("--threads", type=int, default=0, help="thread CPU di llama.cpp (0 = default di llama.cpp)")
ap.add_argument("--empty-think", action="store_true",
                help="aggiunge il blocco <think> vuoto come fa ai_chat.cpp per i template con thinking (Qwen3/Qwen3.5)")
ap.add_argument("--answers", type=Path, help="salva domanda, risposta attesa e ottenuta (JSONL) per leggerle a mano")
a = ap.parse_args()

# llama-completion: nome del vecchio llama-cli non interattivo nelle build recenti di llama.cpp
llama_cli = next((w for n in ("llama-completion", "llama-cli") if (w := shutil.which(n))), None) or next(
    (str(p) for n in ("llama-completion", "llama-cli") for ext in ("", ".exe")
     for p in (a.llama_cpp / "build" / "bin" / f"{n}{ext}", a.llama_cpp / "build" / f"{n}{ext}",
               a.llama_cpp / "bin" / f"{n}{ext}") if p.exists()),
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
    if a.empty_think:
        prompt += "<think>\n\n</think>\n\n"
    result = subprocess.run(
        [llama_cli, "-m", str(a.model), "-p", prompt, "-n", "110", "--temp", "0", "--top-k", "1",
         # -c come DEFAULT_CONTEXT_SIZE di ai_chat.cpp: senza, llama.cpp usa tutto il contesto di training
         # (262k per Qwen3.5) e alloca una cache KV enorme, che con poca RAM libera manda in crash il processo
         "-c", "8192", "-no-cnv", "--simple-io", "--no-display-prompt", "--no-warmup",
         *(["-t", str(a.threads)] if a.threads else [])],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
    )
    return result.stdout.strip().removesuffix("[end of text]").strip()


stats = {}  # tipo di riga -> [(ha rifiutato, risposta ok)]
answers = open(a.answers, "w", encoding="utf-8") if a.answers else None
phase("eval GGUF", f"{a.model.name}, {len(test)} righe {'(test esteso)' if a.extended else ''}")
progress = Progress("eval", len(test), "riga", every=30)


def refusal_summary():
    """'pos rifiutati 3% · neg rifiutati 91%' sulle righe fatte finora."""
    out = []
    for label, prefix in (("pos", "pos"), ("neg", "neg")):
        rows = [x for kind, rs in stats.items() if kind.startswith(prefix) for x in rs]
        if rows:
            out.append(f"{label} rifiutati {100 * sum(x[0] for x in rows) // len(rows)}%")
    return " · ".join(out)


for n, r in enumerate(test, 1):
    got = ask(r["messages"][0]["content"])
    want = r["messages"][1]["content"]
    same = got[:60] == want[:60] if r["kind"].startswith("pos") else got == want
    stats.setdefault(r["kind"], []).append((got.startswith(REFUSAL), same))
    progress.update(n, refusal_summary())
    if answers:
        answers.write(json.dumps({"kind": r["kind"], "region": r["region"], "category": r.get("category"),
                                  "question": r["messages"][0]["content"].rsplit("DOMANDA:", 1)[1].strip(),
                                  "want": want, "got": got}, ensure_ascii=False) + "\n")

print_report(stats, test, held_out, a.extended)
