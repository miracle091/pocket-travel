#!/usr/bin/env python3
"""Come eval_behavior.py, ma passando dal file .gguf con il runtime llama.cpp (llama-server), non da un
checkpoint HF: verifica il comportamento del modello quantizzato che l'app scaricherebbe davvero.

Uso (binari di llama.cpp scaricati con get_llama_tools.py, o un clone con build/bin):
  python eval_gguf.py <file .gguf> --llama-cpp <cartella dei binari> [--extended] [--limit N]
                      [--gpu-layers 99 --device Vulkan0]
Stesse regioni di test di train_lora.py (TEST_REGIONS in eval_common.py) e stesse metriche di eval_behavior.py; decodifica
greedy (temperature 0, top-k 1). Il modello si carica una volta sola in llama-server e ogni riga e' una
richiesta /completion. Il prompt viene formattato qui a mano nello stesso ChatML "grezzo" che
OnDeviceLlmEngine/ai_chat.cpp costruisce a runtime (un solo turno utente, nessun turno system — vedi
chat_add_and_format/reset_long_term_states in ai_chat.cpp), non il chat_template jinja scritto nel GGUF
da convert_gguf.py: l'app non lo usa (common_chat_format_single e' chiamato con use_jinja=false, quindi
passa dal formatter CHATML hardcoded di llama.cpp). /completion non applica template: il prompt passato
e' esattamente quello che finisce nel modello.
"""
import argparse
import json
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

from gguf import GGUFReader

import vram
from eval_common import DEFAULT_DATASET, find_llama_bin, llama_env, load_test_rows, print_report, refusal_summary, score
from status import Progress, phase

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("model", type=Path)
ap.add_argument("--llama-cpp", type=Path, required=True, help="cartella dei binari di llama.cpp (get_llama_tools.py) o clone con build/bin")
ap.add_argument("--extended", action="store_true", help="test esteso (generate_eval_set.py): rifiuto per tipo di riga")
ap.add_argument("--limit", type=int, default=0, help="0 = tutte le righe di test")
ap.add_argument("--threads", type=int, default=0, help="thread CPU di llama.cpp (0 = default di llama.cpp)")
ap.add_argument("--gpu-layers", type=int, default=0, help="layer su GPU (99 = tutti; serve una build GPU e la GPU libera)")
ap.add_argument("--device", help="GPU da usare, come in `llama-server --list-devices` (es. Vulkan0, ROCm1): "
                                 "senza, llama.cpp divide i layer su tutte, grafica integrata compresa")
ap.add_argument("--max-vram-held", type=float, default=vram.DEFAULT_MAX_HELD_PCT, help=vram.HELP + ", con --gpu-layers")
ap.add_argument("--empty-think", action="store_true",
                help="forza il blocco <think> vuoto (di default: aggiunto se il chat template del GGUF usa enable_thinking, come ai_chat.cpp)")
ap.add_argument("--dataset", default=DEFAULT_DATASET, help="file in data/sft/ usato nel training (test base: sue regioni di test)")
ap.add_argument("--answers", type=Path, help="salva domanda, risposta attesa e ottenuta (JSONL) per leggerle a mano")
a = ap.parse_args()

# Come ai_chat.cpp (common_chat_templates_support_enable_thinking): i template con thinking (Qwen3.5)
# ricevono il blocco <think> vuoto che il training (enable_thinking=False) mette nel turno assistente.
template = GGUFReader(a.model).fields.get("tokenizer.chat_template")
empty_think = a.empty_think or (template is not None and "enable_thinking" in bytes(template.parts[template.data[0]]).decode("utf-8"))

test, held_out = load_test_rows(a.extended, a.dataset)
if a.limit:
    test = test[:a.limit]


def start_server():
    """llama-server su una porta libera di localhost; ritorna (processo, url) quando il modello e' carico."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    exe = find_llama_bin("llama-server", a.llama_cpp)
    # -c come DEFAULT_CONTEXT_SIZE di ai_chat.cpp: senza, llama.cpp usa tutto il contesto di training
    # (262k per Qwen3.5) e alloca una cache KV enorme, che con poca RAM libera manda in crash il processo
    cmd = [exe, "-m", str(a.model), "--host", "127.0.0.1", "--port", str(port), "-c", "8192", "-np", "1",
           "--no-webui", "-ngl", str(a.gpu_layers), *(["--device", a.device] if a.device else []),
           *(["-t", str(a.threads)] if a.threads else [])]
    if a.gpu_layers:
        vram.check_llama(exe, llama_env(exe), a.device, a.max_vram_held)
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


def ask(url, content):
    prompt = f"<|im_start|>user\n{content}<|im_end|>\n<|im_start|>assistant\n"
    if empty_think:
        prompt += "<think>\n\n</think>\n\n"
    body = json.dumps({"prompt": prompt, "n_predict": 110, "temperature": 0, "top_k": 1, "cache_prompt": False})
    request = urllib.request.Request(f"{url}/completion", body.encode("utf-8"), {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=600) as r:
        return json.load(r)["content"].strip()


stats = {}  # tipo di riga -> [(ha rifiutato, risposta ok)]
answers = open(a.answers, "w", encoding="utf-8", buffering=1) if a.answers else None  # a righe: resta anche se llama crasha
phase("eval GGUF", f"{a.model.name}, {len(test)} righe {'(test esteso)' if a.extended else ''}")
server, url = start_server()
progress = Progress("eval", len(test), "riga", every=30)
try:
    for n, r in enumerate(test, 1):
        got = ask(url, r["messages"][0]["content"])
        score(stats, r, got)
        progress.update(n, refusal_summary(stats))
        if answers:
            answers.write(json.dumps({"kind": r["kind"], "region": r["region"], "category": r.get("category"),
                                      "question": r["messages"][0]["content"].rsplit("DOMANDA:", 1)[1].strip(),
                                      "want": r["messages"][1]["content"], "got": got}, ensure_ascii=False) + "\n")
finally:
    server.kill()

print_report(stats, test, held_out, a.extended)
