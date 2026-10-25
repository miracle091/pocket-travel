#!/usr/bin/env python3
"""Prova veloce di un modello GGUF vero con un llama-server: poche righe fisse, decodifica greedy, prompt come l'app.
Esce con errore se una riga non va. Serve alla CI (job "Modello vero con llama.cpp" di android-ci.yml) per vedere
subito se una patch di third-party/patches/llama-cpp, un nuovo commit del submodule o una modifica agli script di
valutazione rompono le risposte; sul dispositivo le stesse cose si provano solo con i test strumentati, a mano.

Controlli, con il modello addestrato sulle guide (italiano):
- domanda con la risposta nel contesto: la risposta contiene il valore atteso e non e' il rifiuto;
- domanda senza risposta nel contesto: la risposta comincia con il rifiuto (is_refusal di eval_common.py);
- nessuna risposta contiene un blocco <think> (i Qwen3.5 ricevono il blocco vuoto nel prompt, come in ai_chat.cpp);
- categoria della domanda con la grammatica di ModelQuestionCategory.kt: la risposta e' sempre un nome di categoria.
Il prompt e' il ChatML a turno singolo di ai_chat.cpp (chatml_prompt), con il testo del turno utente di
PromptTemplates.onDevicePrompt (on_device_prompt); i contesti sono scritti apposta, corti per una CPU senza GPU.

Uso: python llm_smoke.py <file .gguf> --llama-cpp <cartella dei binari o build di llama.cpp>
"""
import argparse
import json
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import eval_retrieval as ev
from eval_common import ANSWER_TOKENS, chatml_prompt, find_llama_bin, is_refusal, llama_env, on_device_prompt, uses_empty_think

# (contesto, domanda, valore atteso nella risposta; None = rifiuto atteso)
ROWS = [
    ("▸ Emergenze\nIl numero unico di emergenza è il 112, gratuito da tutti i telefoni, anche senza credito. "
     "Le farmacie di turno espongono l'elenco sulla porta.",
     "Qual è il numero di emergenza?", "112"),
    ("▸ Trasporti\nDa Lubecca ad Amburgo il treno regionale parte ogni ora e impiega 45 minuti. "
     "Il biglietto si compra alle macchinette in stazione.",
     "Quanto dura il treno da Lubecca ad Amburgo?", "45"),
    ("▸ Giornali\nI quotidiani nazionali più letti si trovano in ogni edicola. Molti hanno anche un sito con le "
     "notizie in inglese.",
     "Dove si mangia il pesce fresco?", None),
    ("▸ Elettricità\nLe prese sono di tipo F, a 230 volt. Gli adattatori si trovano nei supermercati e nei negozi di "
     "ferramenta.",
     "Serve il visto per entrare nel paese?", None),
]
# Domande di ModelQuestionCategoryDeviceTest: le liste di parole dell'app non ne riconoscono la categoria.
CATEGORY_QUESTIONS = ["C'è un agriturismo a Zara?", "Tira vento a Spalato?", "Ci sono voli interni?",
                      "Come si combatte il jet lag?"]


def start_server(llama_cpp, model):
    """llama-server su una porta libera di localhost; ritorna (processo, url) quando il modello e' carico. Il log del
    server va in un file temporaneo: se non parte, le sue ultime righe dicono perche'."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    exe = find_llama_bin("llama-server", llama_cpp)
    # -c come DEFAULT_CONTEXT_SIZE di ai_chat.cpp
    log = Path(tempfile.mkdtemp()) / "llama-server.log"
    with open(log, "wb") as out:
        server = subprocess.Popen([exe, "-m", str(model), "--host", "127.0.0.1", "--port", str(port), "-c", "8192", "-np", "1",
                                   "--no-webui", "-ngl", "0"], stdout=out, stderr=subprocess.STDOUT, env=llama_env(exe))

    def log_tail():
        return "\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-20:])

    url = f"http://127.0.0.1:{port}"
    for _ in range(600):
        if server.poll() is not None:
            raise SystemExit(f"llama-server uscito con codice {server.returncode}:\n{log_tail()}")
        try:
            with urllib.request.urlopen(f"{url}/health", timeout=5) as r:
                if r.status == 200:
                    return server, url
        except OSError:
            pass  # porta non ancora aperta o modello in caricamento (503)
        time.sleep(0.5)
    server.kill()
    server.wait()
    raise SystemExit(f"llama-server non pronto dopo 5 minuti:\n{log_tail()}")


def post(url, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(f"{url}{path}", data, {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=600) as r:
        return json.load(r)


def complete(url, prompt, n_predict, grammar=None):
    """Risposta greedy; un errore di llama-server (HTTP 500, per esempio una grammatica che non si compila) diventa la
    risposta "<errore ...>", che non passa nessun controllo."""
    body = {"prompt": prompt, "n_predict": n_predict, "temperature": 0, "top_k": 1, "cache_prompt": False}
    if grammar:
        body["grammar"] = grammar
    try:
        return post(url, "/completion", body)["content"].strip()
    except urllib.error.HTTPError as e:
        with e:
            return f"<errore di llama-server: HTTP {e.code} {e.read().decode('utf-8', 'replace')[:200]}>"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("model", type=Path)
    ap.add_argument("--llama-cpp", type=Path, required=True)
    a = ap.parse_args()
    failures = []
    server, url = start_server(a.llama_cpp, a.model)
    try:
        # Il chat template scritto nel GGUF, come lo legge llama-server: decide il blocco <think> vuoto.
        empty_think = uses_empty_think(post(url, "/props").get("chat_template"))
        for context, question, want in ROWS:
            got = complete(url, chatml_prompt(on_device_prompt(context, question, "it"), empty_think), ANSWER_TOKENS)
            if "<think>" in got:
                problem = "blocco <think> nella risposta"
            elif want is None:
                problem = None if is_refusal(got, "it") else "manca il rifiuto"
            else:
                problem = "rifiuto" if is_refusal(got, "it") else None if want in got else f"manca \"{want}\""
            print(f"{'ERRORE' if problem else 'ok'}: {question} -> {got!r}")
            if problem:
                failures.append(f"{question}: {problem}")
        names = {name for name, _, _ in ev.MODEL_CATEGORY_LABELS["it"]}
        for question in CATEGORY_QUESTIONS:
            got = complete(url, chatml_prompt(ev.model_category_prompt(question, "it"), empty_think), 8,
                           ev.model_category_grammar("it"))
            print(f"{'ok' if got in names else 'ERRORE'}: categoria di {question} -> {got!r}")
            if got not in names:
                failures.append(f"categoria di {question}: {got!r} non e' una categoria")
    finally:
        server.kill()
    for f in failures:
        print(f"::error::{f}", file=sys.stderr)
    print(f"{len(ROWS) + len(CATEGORY_QUESTIONS) - len(failures)}/{len(ROWS) + len(CATEGORY_QUESTIONS)} righe ok")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
