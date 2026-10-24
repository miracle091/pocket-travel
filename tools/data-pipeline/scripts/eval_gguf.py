#!/usr/bin/env python3
"""Come eval_behavior.py, ma passando dal file .gguf con il runtime llama.cpp (llama-server), non da un
checkpoint HF: verifica il comportamento del modello quantizzato che l'app scaricherebbe davvero.

Uso (binari di llama.cpp scaricati con get_llama_tools.py, o un clone con build/bin):
  python eval_gguf.py <file .gguf> --llama-cpp <cartella dei binari> [--extended] [--limit N]
                      [--gpu-layers 99 --device Vulkan0]
  python eval_gguf.py <file .gguf> --llama-cpp <cartella> --contexts <file di eval_retrieval.py --contexts> ...
Con --app-sampling, per ogni riga anche la probabilita' che l'app cominci la risposta con il rifiuto campionando come
l'app (APP_SAMPLING): prodotto, sui token del rifiuto, della probabilita' di ciascuno dopo la catena dei sampler, con i
token precedenti forzati nel prompt (una richiesta /completion di un token per ogni token del rifiuto). Con --samples N
anche N risposte campionate per riga (semi 0..N-1) per controllare quella probabilita' sulla frequenza osservata:
  python eval_gguf.py <file .gguf> --llama-cpp <cartella> --extended --app-sampling [--samples 20 --kinds pos_near neg_city]
Con --contexts il modello risponde con il contesto prodotto dalla ricerca vera (ricerca e modello insieme): per lingua,
serie e variante della ricerca, rifiuti con la sezione attesa nel contesto (del modello) e senza (della ricerca).
Dopo le metriche dei rifiuti, i controlli sulle istruzioni (instruction_checks di eval_common.py), anche nei campi di
--answers: fedelta' al contesto, risposte oltre 450 caratteri, lingua, <think> iniziale; eval_answers.py li rifa' su
risposte gia' salvate. Con --system "<testo>" il prompt ha prima un turno system (l'app non lo manda): serve a misurare
un prompt di sistema, per esempio sugli Ufficiali, con le stesse domande.
Stesse regioni di test di train_lora.py (TEST_REGIONS in eval_common.py) e stesse metriche di eval_behavior.py; decodifica
greedy (temperature 0, top-k 1). Il modello si carica una volta sola in llama-server e ogni riga e' una
richiesta /completion. Il prompt e' lo stesso ChatML "grezzo" (chatml_prompt di eval_common.py) che
OnDeviceLlmEngine/ai_chat.cpp costruisce a runtime (un solo turno utente, nessun turno system — vedi
chat_add_and_format/reset_long_term_states in ai_chat.cpp), non il chat_template jinja scritto nel GGUF
da convert_gguf.py: per i modelli ChatML l'app usa il formatter CHATML di llama.cpp (il template jinja serve
solo al controllo del formato al caricamento e ai modelli non ChatML). /completion non applica template: il prompt passato
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

import gpu_lock
import vram
from eval_common import (ANSWER_TOKENS, DEFAULT_DATASET, chatml_prompt, context_report, find_llama_bin, instruction_checks, is_refusal,
                         llama_env, load_test_rows, on_device_prompt, print_app_sampling_report, print_instruction_report, print_lang_report,
                         print_report, question_lang, question_of, refusal_prefix, refusal_summary, score, text_lang, uses_empty_think)
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
ap.add_argument("--dataset", default=DEFAULT_DATASET, help="file in data/sft/ usato nel training (test base: sue regioni di test; "
                                                         "con un dataset *.en.jsonl il test esteso e' eval_extended.en.jsonl)")
ap.add_argument("--answers", type=Path, help="salva domanda, risposta attesa e ottenuta (JSONL) per leggerle a mano")
ap.add_argument("--contexts", type=Path, nargs="+",
                help="file di eval_retrieval.py --contexts al posto delle righe di test: il prompt dell'app con il contesto "
                     "della ricerca vera, e i rifiuti contati con e senza la sezione attesa nel contesto")
ap.add_argument("--app-sampling", action="store_true",
                help="anche la probabilita' del rifiuto con il campionamento dell'app (temperatura 0,3, top-k 10, top-p 0,95, min-p 0,05)")
ap.add_argument("--samples", type=int, default=0, help="con --app-sampling: anche N risposte campionate per riga, per controllo")
ap.add_argument("--kinds", nargs="+", help="solo le righe di questi tipi (es. pos_near neg_city)")
ap.add_argument("--system", help="turno system prima del turno utente, nel formato CHATML (l'app oggi non lo manda: per "
                                 "misurare un prompt di sistema, per esempio sugli Ufficiali)")
a = ap.parse_args()
if a.contexts and (a.app_sampling or a.kinds):
    ap.error("--app-sampling e --kinds valgono per le righe di test, non con --contexts")
if a.samples and not a.app_sampling:
    ap.error("--samples serve con --app-sampling")
gpu_lock.gpu_job("eval_gguf.py", gpu=a.gpu_layers > 0)

# Come ai_chat.cpp: i template con enable_thinking (Qwen3.5 del v10) ricevono il blocco <think> vuoto che il training
# (enable_thinking=False) mette nel turno assistente.
template = GGUFReader(a.model).fields.get("tokenizer.chat_template")
empty_think = a.empty_think or uses_empty_think(
    bytes(template.parts[template.data[0]]).decode("utf-8") if template is not None else None)

if a.contexts:
    test = [json.loads(line) for f in a.contexts for line in open(f, encoding="utf-8")]
else:
    test, held_out = load_test_rows(a.extended, a.dataset)
if a.kinds:
    test = [r for r in test if r["kind"] in a.kinds]
if a.limit:
    test = test[:a.limit]

# Campionamento dell'app: temperatura 0,3 (DEFAULT_SAMPLER_TEMP in ai_chat.cpp), top-k 10 e top-p 0,95 (TOP_K e TOP_P in
# LlamaOnDeviceLlmEngine.kt), il resto come i default di common_params_sampling che ai_chat.cpp non cambia: min-p 0,05,
# gli altri sampler spenti, stesso ordine (top-k, top-p e min-p sulle probabilita' prima della temperatura). Tutto
# esplicito: llama-server prende i default anche dai metadati general.sampling.* del GGUF, ai_chat.cpp no.
APP_SAMPLING = {"temperature": 0.3, "top_k": 10, "top_p": 0.95, "min_p": 0.05, "typical_p": 1.0, "top_n_sigma": -1.0,
                "xtc_probability": 0.0, "dynatemp_range": 0.0, "repeat_penalty": 1.0, "frequency_penalty": 0.0,
                "presence_penalty": 0.0, "dry_multiplier": 0.0, "mirostat": 0,
                "samplers": ["penalties", "dry", "top_n_sigma", "top_k", "typ_p", "top_p", "min_p", "xtc", "temperature"]}


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


def post(url, path, body):
    request = urllib.request.Request(f"{url}{path}", json.dumps(body).encode("utf-8"), {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=600) as r:
        return json.load(r)


def ask(url, content):
    """(risposta, troncata): troncata se llama-server si e' fermato al limite di token (stop_type "limit")."""
    reply = post(url, "/completion", {"prompt": chatml_prompt(content, empty_think, a.system), "n_predict": ANSWER_TOKENS, "temperature": 0,
                                      "top_k": 1, "cache_prompt": False})
    return reply["content"].strip(), reply.get("stop_type") == "limit"


refusal_tokens = {}  # inizio del rifiuto -> token del modello


def app_refusal(url, row):
    """Con --app-sampling: {"p_refusal": probabilita' che l'app cominci la risposta alla riga con il rifiuto} e, con
    --samples, i rifiuti tra le risposte campionate. Ogni token del rifiuto si legge tra i candidati rimasti dopo la
    catena dei sampler (post_sampling_probs), con i token precedenti in coda al prompt: un token non tra i candidati
    (tolto da top-k, top-p o min-p) ha probabilita' 0. Il prompt e' lo stesso della decodifica greedy; cache_prompt
    evita di rielaborarlo a ogni token."""
    prefix = refusal_prefix(row)
    if prefix not in refusal_tokens:
        refusal_tokens[prefix] = post(url, "/tokenize", {"content": prefix})["tokens"]
    tokens, prompt = refusal_tokens[prefix], chatml_prompt(row["messages"][0]["content"], empty_think, a.system)
    p = 1.0
    for i, token in enumerate(tokens):
        reply = post(url, "/completion", {"prompt": [prompt, *tokens[:i]], "n_predict": 1, "n_probs": APP_SAMPLING["top_k"],
                                          "post_sampling_probs": True, "cache_prompt": True, **APP_SAMPLING})
        p *= next((c["prob"] for c in reply["completion_probabilities"][0]["top_probs"] if c["id"] == token), 0.0)
        if not p:
            break
    out = {"p_refusal": p}
    if a.samples:
        # qualche token in piu' del rifiuto: la risposta puo' cominciare con spazi o a capo, tolti come in ask
        replies = [post(url, "/completion", {"prompt": prompt, "n_predict": len(tokens) + 8, "seed": seed, "cache_prompt": True,
                                             **APP_SAMPLING})["content"].strip() for seed in range(a.samples)]
        out.update(samples=a.samples, sampled_refusals=sum(x.startswith(prefix) for x in replies))
    return out


def answer_contexts(url):
    """Le righe di --contexts con "got" e "refused"; un prompt gia' visto (stessa domanda e stesso contesto in due
    varianti della ricerca) non si richiede: la decodifica e' greedy."""
    seen = {}
    progress = Progress("eval", len(test), "domanda", every=30)
    for n, r in enumerate(test, 1):
        prompt = on_device_prompt(r["context"], r["question"], r["lang"])
        if prompt not in seen:
            seen[prompt] = ask(url, prompt)
        r["got"], r["truncated"] = seen[prompt]
        r["refused"] = is_refusal(r["got"], r["lang"])
        if answers:
            answers.write(json.dumps(r, ensure_ascii=False) + "\n")
        progress.update(n, f"rifiuti {sum(x['refused'] for x in test[:n])}")
    print("lingua serie ricerca: domande | sezione attesa nel contesto | rifiuti con la sezione | rifiuti senza")
    for (lang, series, search), (n, with_section, refused_with, refused_without) in sorted(context_report(test).items()):
        print(f"{lang} {series} {search}: {n} | {with_section} | {refused_with}/{with_section} | "
              f"{refused_without}/{n - with_section}")


stats = {}  # tipo di riga -> [(ha rifiutato, risposta ok)]
by_lang = {}  # lingua della domanda -> stats
wrong_lang = {}  # tipo di riga -> risposte non nella lingua del dataset
app_rows = []  # con --app-sampling: righe di print_app_sampling_report
instruction_rows = []  # righe di print_instruction_report
answers = open(a.answers, "w", encoding="utf-8", buffering=1) if a.answers else None  # a righe: resta anche se llama crasha
phase("eval GGUF", f"{a.model.name}, {len(test)} righe {'(test esteso)' if a.extended else ''}")
server, url = start_server()
if a.contexts:
    try:
        answer_contexts(url)
    finally:
        server.kill()
    raise SystemExit(0)
progress = Progress("eval", len(test), "riga", every=30)
try:
    for n, r in enumerate(test, 1):
        got, truncated = ask(url, r["messages"][0]["content"])
        score(stats, r, got)
        langs = {"question_lang": question_lang(r), "answer_lang": text_lang(got)}
        score(by_lang.setdefault(langs["question_lang"], {}), r, got)
        checks = instruction_checks(r, got)
        wrong_lang[r["kind"]] = wrong_lang.get(r["kind"], 0) + checks["wrong_lang"]
        instruction_rows.append({"kind": r["kind"], "question_lang": langs["question_lang"], "question": question_of(r).strip(),
                                 "got": got, **checks})
        app = app_refusal(url, r) if a.app_sampling else {}
        if app:
            app_rows.append({"kind": r["kind"], "question_lang": langs["question_lang"], "question": question_of(r).strip(),
                             "greedy": got.startswith(refusal_prefix(r)), **app})
        progress.update(n, refusal_summary(stats))
        if answers:
            answers.write(json.dumps({"kind": r["kind"], "region": r["region"], "category": r.get("category"),
                                      "question": question_of(r).strip(), "want": r["messages"][1]["content"], "got": got,
                                      "truncated": truncated, **langs, **checks, **app}, ensure_ascii=False) + "\n")
finally:
    server.kill()

print_report(stats, test, held_out, a.extended)
if a.extended:
    print_lang_report(by_lang, wrong_lang, stats)
print_instruction_report(instruction_rows)
if app_rows:
    print_app_sampling_report(app_rows)
