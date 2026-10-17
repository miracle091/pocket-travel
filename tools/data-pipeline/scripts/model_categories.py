#!/usr/bin/env python3
"""Categoria di ogni domanda delle serie di eval_retrieval.py scelta da un modello GGUF, come fa l'app sul dispositivo
quando le liste di parole non riconoscono la categoria (ModelQuestionCategory.kt): stesso prompt, stessa grammatica
(solo i nomi delle categorie), decodifica greedy come ai_chat.cpp con una grammatica, prompt ChatML a turno singolo con
il blocco <think> vuoto solo se il chat template del GGUF usa enable_thinking, come ai_chat.cpp ed eval_gguf.py. Scrive
{domanda: gruppo o null} in JSON, da passare a eval_retrieval.py --model-categories.

Uso: python model_categories.py <file .gguf> --llama-cpp <cartella dei binari> --lang it|en --out FILE
     [--cache DIR] [--regions italia ...] [--gpu-layers 99 --device Vulkan0]
La cache deve contenere i dati scaricati da eval_retrieval.py e --regions essere le stesse della misura (stesse citta' e
domande).
"""
import argparse
import json
import random
import socket
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path

from gguf import GGUFReader

import eval_retrieval as ev
from eval_common import chatml_prompt, find_llama_bin, llama_env, uses_empty_think

SERIES = ["direct", "reworded", "dev", "dev2", "other-lang", "control"]


def questions(lang, cache, regions):
    """Tutte le domande delle serie di eval_retrieval.py per [lang] (citta' e paese, distanze, generali, nazione nominata)."""
    qs = set()
    for series in SERIES:
        rng, pick = random.Random(7), random.Random(8)
        for region in regions:
            plan = ev.make_plan(region, ev.city_rows_of(cache, region, True, lang), rng, pick, ev.questions_of(lang, series))
            qs.update(q for _, _, _, q, _ in plan)
    qs.update(q for q, _ in ev.GENERAL_Q[lang])
    names = [names[0 if lang == "it" else 1] for names in ev.COUNTRY_NAMES.values()]
    qs.update(q.format(p=p) for qs_ in ev.OTHER_COUNTRY_Q[lang].values() for q in qs_ for p in names)
    return sorted(qs)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("model", type=Path)
    ap.add_argument("--llama-cpp", type=Path, required=True)
    ap.add_argument("--lang", choices=sorted(ev.LANGS), default="it")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "pocket-travel-eval-retrieval")
    ap.add_argument("--regions", nargs="+", default=ev.DEFAULT_REGIONS)
    ap.add_argument("--gpu-layers", type=int, default=0)
    ap.add_argument("--device")
    a = ap.parse_args()
    labels = {name: group for name, _, group in ev.MODEL_CATEGORY_LABELS[a.lang]}
    grammar = ev.model_category_grammar(a.lang)
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    exe = find_llama_bin("llama-server", a.llama_cpp)
    server = subprocess.Popen([exe, "-m", str(a.model), "--host", "127.0.0.1", "--port", str(port), "-c", "4096", "-np", "1",
                               "--no-webui", "-ngl", str(a.gpu_layers), *(["--device", a.device] if a.device else [])],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=llama_env(exe))
    url = f"http://127.0.0.1:{port}"
    try:
        for _ in range(600):
            try:
                if urllib.request.urlopen(f"{url}/health", timeout=5).status == 200:
                    break
            except OSError:
                time.sleep(0.5)
        result = {}
        template = GGUFReader(a.model).fields.get("tokenizer.chat_template")
        think = uses_empty_think(bytes(template.parts[template.data[0]]).decode("utf-8") if template is not None else None)
        for q in questions(a.lang, a.cache, a.regions):
            # Come ai_chat.cpp: un turno utente, piu' il blocco <think> vuoto per i template con enable_thinking
            prompt = chatml_prompt(ev.model_category_prompt(q, a.lang), think)
            body = json.dumps({"prompt": prompt, "n_predict": 8, "temperature": 0, "top_k": 1, "grammar": grammar})
            request = urllib.request.Request(f"{url}/completion", body.encode("utf-8"), {"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=120) as r:
                result[q] = labels.get(json.load(r)["content"].strip())
    finally:
        server.kill()
    a.out.write_text(json.dumps(result, ensure_ascii=False, indent=0), encoding="utf-8")
    print(f"{len(result)} domande in {a.out}")


if __name__ == "__main__":
    main()
