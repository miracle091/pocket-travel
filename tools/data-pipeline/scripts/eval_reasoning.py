#!/usr/bin/env python3
"""Ragionamento di un GGUF con il thinking acceso e spento, sul set di generate_reasoning_set.py.

Ogni domanda va a llama-server nel turno utente dopo il suo contesto, nel formato ChatML dell'app. Spento: il blocco
<think> vuoto che ai_chat.cpp aggiunge ai Qwen3.5; acceso: il turno assistente comincia con "<think>" aperto e il modello
ragiona prima di rispondere. Campionamento dell'app (temperatura 0,3, top-k 10, top-p 0,95, min-p 0,05) con un seme
fisso: lo stesso per le due modalita'. La risposta si legge dopo "Risposta:"/"Answer:" (score_answer).

Per modalita': risposte giuste per tipo, token generati in media (thinking compreso), token per risposta giusta,
risposte troncate al limite di --max-tokens e secondi in media. Il risultato decide il campo `thinking` del catalogo.

Esempio:
    python gpu_lock.py run -- python eval_reasoning.py Qwen3.5-2B-UD-Q4_K_XL.gguf --llama-cpp <bin> \\
        --set reasoning-genova.jsonl --gpu-layers 99 --device Vulkan0 --answers reasoning-2b.jsonl
"""
import argparse
import json
import socket
import subprocess
import time
import urllib.request
from collections import defaultdict
from pathlib import Path

import gpu_lock
from eval_common import EMPTY_THINK, chatml_prompt, find_llama_bin, llama_env
from generate_reasoning_set import KINDS, score_answer

MODES = ("off", "on")
SAMPLING = {"temperature": 0.3, "top_k": 10, "top_p": 0.95, "min_p": 0.05, "repeat_penalty": 1.0, "seed": 42}


def prompt(row, mode):
    """Il prompt ChatML: contesto e domanda nel turno utente; thinking spento con il blocco vuoto, acceso con <think>."""
    base = chatml_prompt(f"{row['context']}\n\n{row['question']}")
    return base + (EMPTY_THINK if mode == "off" else "<think>\n")


def start_server(model, llama_cpp, gpu_layers, device, context):
    """llama-server su una porta libera di localhost; ritorna (processo, url) quando il modello e' carico."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    exe = find_llama_bin("llama-server", llama_cpp)
    cmd = [exe, "-m", str(model), "--host", "127.0.0.1", "--port", str(port), "-c", str(context), "-np", "1",
           "--no-webui", "-ngl", str(gpu_layers), *(["--device", device] if device else [])]
    server = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=llama_env(exe))
    url = f"http://127.0.0.1:{port}"
    for _ in range(600):
        if server.poll() is not None:
            raise SystemExit(f"llama-server uscito con codice {server.returncode}: {' '.join(cmd)}")
        try:
            with urllib.request.urlopen(f"{url}/health", timeout=5) as r:
                if r.status == 200:
                    return server, url
        except OSError:
            pass  # porta non ancora aperta o modello in caricamento (503)
        time.sleep(0.5)
    server.kill()
    raise SystemExit("llama-server non pronto dopo 5 minuti")


def ask(url, text, max_tokens):
    body = {"prompt": text, "n_predict": max_tokens, "cache_prompt": False, **SAMPLING}
    request = urllib.request.Request(f"{url}/completion", json.dumps(body).encode("utf-8"),
                                     {"Content-Type": "application/json"})
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=1800) as r:
        reply = json.load(r)
    return {"got": reply["content"], "tokens": reply.get("tokens_predicted", 0),
            "truncated": reply.get("stop_type") == "limit", "seconds": round(time.monotonic() - started, 1)}


def report(done):
    """Una riga per modalita': giuste per tipo e in tutto, token medi, token per risposta giusta, troncate, secondi."""
    lines = ["| modalita' | " + " | ".join(KINDS) + " | tutte | token medi | token per giusta | troncate | s medi |",
             "|---" * (len(KINDS) + 6) + "|"]
    for mode in MODES:
        rows = [d for d in done if d["mode"] == mode]
        if not rows:
            continue
        by_kind = defaultdict(list)
        for d in rows:
            by_kind[d["kind"]].append(d["ok"])
        right = sum(d["ok"] for d in rows)
        tokens = sum(d["tokens"] for d in rows)
        cells = [f"{sum(by_kind[k])}/{len(by_kind[k])}" for k in KINDS]
        per_right = f"{tokens / right:.0f}" if right else "-"
        lines.append(f"| {mode} | " + " | ".join(cells) + f" | {100 * right / len(rows):.1f}% ({right}/{len(rows)}) | "
                     f"{tokens / len(rows):.0f} | {per_right} | {sum(d['truncated'] for d in rows)} | "
                     f"{sum(d['seconds'] for d in rows) / len(rows):.1f} |")
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("model", type=Path)
    ap.add_argument("--llama-cpp", type=Path, required=True, help="cartella dei binari di llama.cpp (get_llama_tools.py)")
    ap.add_argument("--set", type=Path, required=True, help="set di generate_reasoning_set.py")
    ap.add_argument("--modes", nargs="+", choices=MODES, default=list(MODES))
    ap.add_argument("--max-tokens", type=int, default=4096, help="token generati al massimo, thinking compreso")
    ap.add_argument("--gpu-layers", type=int, default=0)
    ap.add_argument("--device", help="GPU da usare, come in `llama-server --list-devices`")
    ap.add_argument("--answers", type=Path, required=True, help="JSONL delle risposte; se esiste si riprende")
    a = ap.parse_args()
    gpu_lock.gpu_job("eval_reasoning.py", gpu=a.gpu_layers > 0)

    rows = [json.loads(line) for line in a.set.read_text(encoding="utf-8").splitlines() if line.strip()]
    done = [json.loads(line) for line in a.answers.open(encoding="utf-8")] if a.answers.exists() else []
    seen = {(d["mode"], d["id"]) for d in done}
    server, url = start_server(a.model, a.llama_cpp, a.gpu_layers, a.device, a.max_tokens + 1024)
    try:
        with a.answers.open("a", encoding="utf-8", buffering=1) as out:
            for mode in a.modes:
                for n, row in enumerate(rows, 1):
                    if (mode, row["id"]) in seen:
                        continue
                    reply = ask(url, prompt(row, mode), a.max_tokens)
                    d = {"mode": mode, "id": row["id"], "kind": row["kind"], "lang": row["lang"],
                         "answer": row["answer"], **reply, "ok": score_answer(row, reply["got"])}
                    out.write(json.dumps(d, ensure_ascii=False) + "\n")
                    done.append(d)
                    print(f"[{mode}] {n}/{len(rows)}", flush=True)
    finally:
        server.kill()
    print(f"modello {a.model.name}, set {a.set.name}, al massimo {a.max_tokens} token")
    print(report(done))


if __name__ == "__main__":
    main()
