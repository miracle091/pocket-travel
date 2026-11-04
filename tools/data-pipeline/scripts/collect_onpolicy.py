#!/usr/bin/env python3
"""Errori on-policy di un GGUF sulle righe di training: risposte campionate dal modello stesso, etichettate giuste o
sbagliate con i controlli di eval_gguf.py, per il secondo stadio KTO di dpo_lora.py (--loss kto --onpolicy).

Uso (binari di llama.cpp come per eval_gguf.py):
  python collect_onpolicy.py <file .gguf> --llama-cpp <cartella> --dataset pocket_travel_sft.v11.it.jsonl \\
                             --out <file .jsonl> [--gpu-layers 99 --device Vulkan0] [--prompts 5000 --samples 4]

Righe del dataset fuori dalle regioni di test (TEST_REGIONS) e da quelle del test esteso, scelte per gruppo (QUOTAS):
tutte quelle dei gruppi deboli del v11 (pos_near, neg_near, partenze), un campione fisso degli altri. Per ogni prompt
una risposta greedy (quella che il test misura) e --samples risposte campionate (temperatura --temperature, semi
0..N-1). Etichette (label):
  pos  "bad" se rifiuta (frase del training o parole proprie); "good" se risponde, entro MAX_ANSWER_CHARS, nella lingua
       del dataset, con lo stesso inizio della risposta attesa o frasi tutte del contesto ("aderente" per VICINO e
       PARTENZE, scritte con un modello di frase); altrimenti "unsure", che KTO non usa.
  neg  "good" se rifiuta sullo stesso tema della risposta attesa; "bad" se risponde; "unsure" se rifiuta su un altro tema.
Il file si scrive a righe: un'interruzione lascia le righe fatte, e una nuova esecuzione con lo stesso --out riprende
dai prompt mancanti.
"""
import argparse
import json
import random
import socket
import subprocess
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import gpu_lock
import vram
from eval_common import (ANSWER_TOKENS, MAX_ANSWER_CHARS, SFT_DIR, TEST_REGIONS, chatml_prompt, ensure_final_newline,
                         extended_file, find_llama_bin, instruction_checks, dataset_lang, llama_env,
                         read_jsonl_lenient, refusal_prefix)

# Prompt al piu' per gruppo, su --prompts 5000: i gruppi deboli del test esteso del v11 per intero, gli altri a campione.
QUOTAS = {"pos_near": 1.0, "neg_near": 1.0, "pos_dep": 1.0, "neg_dep": 1.0,
          "neg_city": 1200, "pos_city": 900, "neg": 700, "pos": 900}


def group_of(row):
    """Gruppo della riga come nel test esteso: _near (VICINO), _dep (PARTENZE), _city (citta'), altrimenti il tipo."""
    suffix = {"VICINO": "_near", "PARTENZE": "_dep"}.get(row["category"])
    if suffix is None and row["region"].startswith("citta:"):
        suffix = "_city"
    return row["kind"] + (suffix or "")


def pick_rows(rows, excluded_regions, quotas, seed=42):
    """Righe fuori dalle regioni escluse, per gruppo: una quota float e' la frazione del gruppo, una int il massimo."""
    groups = {}
    for r in rows:
        if r["region"] not in excluded_regions and r["region"] not in TEST_REGIONS:
            groups.setdefault(group_of(r), []).append(r)
    rng = random.Random(seed)
    picked = []
    for group, items in sorted(groups.items()):
        quota = quotas.get(group, 0)
        n = round(len(items) * quota) if isinstance(quota, float) else min(quota, len(items))
        picked += rng.sample(items, n)
    return picked


def label(row, got):
    """"good", "bad" o "unsure" per la risposta got alla riga row (regole nella docstring del modulo)."""
    want = row["messages"][1]["content"]
    # il tipo del test esteso (pos_near, neg_dep...): context_fidelity tratta da "aderente" le frasi a modello di _near e _dep
    checks = instruction_checks({**row, "kind": group_of(row)}, got)
    if row["kind"] == "neg":
        if not checks["refused"]:
            return "bad"
        same_theme = got.split(":")[0] == want.split(":")[0]
        return "good" if got.startswith(refusal_prefix(row)) and same_theme else "unsure"
    if checks["refused"]:
        return "bad"
    if checks["too_long"] or checks["wrong_lang"] or checks["think_start"]:
        return "unsure"
    if got[:60] == want[:60] or checks["fidelity"] == "literal":
        return "good"
    if row["category"] in ("VICINO", "PARTENZE") and checks["fidelity"] == "adherent":
        return "good"
    return "unsure"


def prompt_key(row):
    return row["messages"][0]["content"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("model", type=Path)
    ap.add_argument("--llama-cpp", type=Path, required=True)
    ap.add_argument("--dataset", required=True, help="file in data/sft/ usato nel training del modello")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--samples", type=int, default=4, help="risposte campionate per prompt, oltre alla greedy")
    ap.add_argument("--temperature", type=float, default=0.8)
    ap.add_argument("--limit", type=int, default=0, help="solo i primi N prompt scelti (prova breve)")
    ap.add_argument("--parallel", type=int, default=4, help="richieste insieme (slot di llama-server)")
    ap.add_argument("--gpu-layers", type=int, default=0)
    ap.add_argument("--device", help="come in eval_gguf.py (es. Vulkan0)")
    ap.add_argument("--max-vram-held", type=float, default=vram.DEFAULT_MAX_HELD_PCT, help=vram.HELP)
    a = ap.parse_args()

    from status import Progress, phase
    gpu_lock.gpu_job("collect_onpolicy.py", gpu=a.gpu_layers > 0)
    rows = [json.loads(line) for line in (SFT_DIR / a.dataset).open(encoding="utf-8")]
    excluded = {json.loads(line)["region"] for line in (SFT_DIR / extended_file(a.dataset)).open(encoding="utf-8")}
    picked = pick_rows(rows, excluded, QUOTAS)
    if a.limit:
        picked = picked[:a.limit]
    done = {r["prompt"] for r in read_jsonl_lenient(a.out)} if a.out.exists() else set()
    todo = [r for r in picked if prompt_key(r) not in done]
    phase("raccolta on-policy", f"{a.model.name}: {len(picked)} prompt, {len(done)} gia' fatti, "
                                f"{a.samples} campioni + greedy, temperatura {a.temperature}")

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    exe = find_llama_bin("llama-server", a.llama_cpp)
    if a.gpu_layers:
        vram.check_llama(exe, llama_env(exe), a.device, a.max_vram_held)
    # 8192 token per slot, come DEFAULT_CONTEXT_SIZE di ai_chat.cpp (llama-server divide -c tra gli slot)
    cmd = [exe, "-m", str(a.model), "--host", "127.0.0.1", "--port", str(port), "-c", str(8192 * a.parallel),
           "-np", str(a.parallel), "--no-webui", "-ngl", str(a.gpu_layers), *(["--device", a.device] if a.device else [])]
    server = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=llama_env(exe))
    url = f"http://127.0.0.1:{port}"
    try:
        for _ in range(600):
            if server.poll() is not None:
                raise SystemExit(f"llama-server uscito con codice {server.returncode}: {' '.join(cmd)}")
            try:
                with urllib.request.urlopen(f"{url}/health", timeout=5) as r:
                    if r.status == 200:
                        break
            except OSError:
                time.sleep(0.5)  # porta non ancora aperta o modello in caricamento (503)
        else:
            raise SystemExit("llama-server non pronto dopo 5 minuti")

        def complete(prompt, seed):
            """Risposta greedy (seed None) o campionata con il seme dato."""
            sampling = {"temperature": 0, "top_k": 1} if seed is None else {
                "temperature": a.temperature, "top_k": 0, "top_p": 1.0, "min_p": 0.0, "seed": seed}
            body = {"prompt": prompt, "n_predict": ANSWER_TOKENS, "cache_prompt": True, **sampling}
            request = urllib.request.Request(f"{url}/completion", json.dumps(body).encode("utf-8"),
                                             {"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=600) as r:
                return json.load(r)["content"].strip()

        def collect(row):
            # stesso prompt ChatML grezzo di eval_gguf.py: i modelli v11 non hanno il blocco <think> (text_only.py)
            prompt = chatml_prompt(prompt_key(row))
            samples = []
            for seed in [None, *range(a.samples)]:
                got = complete(prompt, seed)
                samples.append({"seed": seed, "got": got, "label": label(row, got)})
            return {"prompt": prompt_key(row), "want": row["messages"][1]["content"], "kind": row["kind"],
                    "group": group_of(row), "region": row["region"], "category": row["category"],
                    "lang": dataset_lang(row), "samples": samples}

        ensure_final_newline(a.out)
        progress = Progress("raccolta", len(todo), "prompt", every=60)
        bad = 0
        with a.out.open("a", encoding="utf-8", buffering=1) as out, ThreadPoolExecutor(a.parallel) as pool:
            for n, result in enumerate(pool.map(collect, todo), 1):
                out.write(json.dumps(result, ensure_ascii=False) + "\n")
                bad += any(s["label"] == "bad" for s in result["samples"])
                progress.update(n, f"prompt con errori {bad}")
    finally:
        server.kill()

    report(read_jsonl_lenient(a.out))
    return 0


def pct(samples, wanted):
    return 100 * sum(s["label"] == wanted for s in samples) / max(len(samples), 1)


def report(results):
    """Per gruppo: prompt, risposte greedy sbagliate, campionate sbagliate e incerte, prompt con almeno un errore."""
    groups = {}
    for r in results:
        groups.setdefault(r["group"], []).append(r)
    print("gruppo: prompt | greedy sbagliate | campionate sbagliate | incerte | prompt con almeno un errore")
    for group, items in sorted(groups.items()):
        greedy = [s for r in items for s in r["samples"] if s["seed"] is None]
        sampled = [s for r in items for s in r["samples"] if s["seed"] is not None]
        everything = greedy + sampled
        with_error = sum(any(s["label"] == "bad" for s in r["samples"]) for r in items)
        print(f"{group}: {len(items)} | {pct(greedy, 'bad'):.1f}% | {pct(sampled, 'bad'):.1f}% | "
              f"{pct(everything, 'unsure'):.1f}% | {with_error}".replace(".", ","))


if __name__ == "__main__":
    raise SystemExit(main())
