#!/usr/bin/env python3
"""Fedelta' al contesto del motore Online dell'app, con e senza il contesto delle guide (RAG), su un campione del
test esteso (generate_eval_set.py).

Ogni domanda va al servizio due volte con il formato dell'app (OnlinePromptTemplates.kt): un messaggio system con le
istruzioni e, nella variante "rag", il contesto della riga in un blocco <contesto fonte="...">; la domanda da sola nel
messaggio user. Le risposte passano dagli stessi controlli deterministici dei modelli sul dispositivo
(eval_common.instruction_checks): rifiuto anche con parole proprie, risposte letterali, aderenti o fuori dal contesto,
quota delle parole lunghe prese dal contesto, oltre 450 caratteri, lingua sbagliata. Senza contesto la fedelta' si
misura comunque contro il contesto della riga: dice quanto la risposta coincide con la guida senza averla vista.

La chiave viene dall'ambiente (GEMINI_API_KEY, MISTRAL_API_KEY, OPENAI_API_KEY, ANTHROPIC_API_KEY) e non si stampa.
Le richieste rispettano --rpm; su un 429 si aspetta il retryDelay o il Retry-After (al massimo 70 s) e si riprova;
con la quota giornaliera finita lo script si ferma e scrive il rapporto delle righe fatte. Le risposte in --answers
si scrivono a righe, e una nuova esecuzione con lo stesso file riprende dalle righe mancanti.

Esempio: python eval_online.py --service gemini --limit 100 --answers online-gemini.jsonl
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_common import DEFAULT_DATASET, context_of, dataset_lang, instruction_checks, load_test_rows, question_of  # noqa: E402

USER_AGENT = "pocket-travel-eval-online/1.0 (https://github.com/miracle091/pocket-travel)"
# Indirizzo, modello Automatico di online-models.json, variabile della chiave e temperatura (null se il modello non la
# accetta), come OnlineProvider.kt e l'elenco dei modelli online dell'app.
SERVICES = {
    "gemini": ("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.6-flash", "GEMINI_API_KEY", None),
    "mistral": ("https://api.mistral.ai/v1", "mistral-small-latest", "MISTRAL_API_KEY", 0.3),
    "chatgpt": ("https://api.openai.com/v1", "gpt-6-luna", "OPENAI_API_KEY", None),
    "claude": ("https://api.anthropic.com/v1", "claude-haiku-5-5", "ANTHROPIC_API_KEY", None),
}
VARIANTS = ("rag", "norag")
MAX_WAIT_SECONDS = 70

# Le stesse stringhe di OnlinePromptTemplates.systemPrompt (test_eval_online.py controlla che coincidano).
SYSTEM_IT = ("Sei un assistente di viaggio.\n"
             "Rispondi solo su base verificabile; se non sei certo su visti o vaccinazioni,\n"
             "indica di consultare la fonte ufficiale (link OMS/ambasciata) invece di rispondere.")
CONTEXT_IT = ("\nRispondi usando i blocchi di contesto qui sotto, presi dalle guide dell'app, e cita la sezione che usi, "
              "per esempio (Wikivoyage, sezione Cosa vedere). Se il contesto non risponde alla domanda (un'altra "
              "città, un altro argomento o nessun contesto utile), comincia dicendo che l'informazione non è "
              "presente nelle guide dell'app; solo dopo puoi aggiungere quello che sai, presentandolo come "
              "conoscenza generale da verificare.\n\n")
SYSTEM_EN = ("You are a travel assistant.\n"
             "Answer only on a verifiable basis; if you are not sure about visas or vaccinations,\n"
             "tell the user to check the official source (WHO/embassy link) instead of answering.")
CONTEXT_EN = ("\nAnswer using the context blocks below, taken from the app's guides, and cite the section you use, "
              "for example (Wikivoyage, section What to see). If the context does not answer the question "
              "(another city, another topic or no useful context), start by saying that the information is not "
              "included in the app's guides; only then you may add what you know, presenting it as general "
              "knowledge to check.\n\n")


class DailyQuota(Exception):
    """Quota giornaliera del servizio finita: si ferma e si scrive il rapporto delle righe fatte."""


def system_prompt(context, lang):
    """Il messaggio system dell'app; con context un solo blocco, con la fonte generica della guida (le righe del test
    hanno il contesto gia' unito, senza il nome della sezione di ogni parte)."""
    base, intro = (SYSTEM_EN, CONTEXT_EN) if lang == "en" else (SYSTEM_IT, CONTEXT_IT)
    if not context:
        return base
    block = (f'<context source="Wikivoyage, app guide">\n{context}\n</context>' if lang == "en"
             else f'<contesto fonte="Wikivoyage, guida dell\'app">\n{context}\n</contesto>')
    return base + intro + block


def sample(rows, limit):
    """limit righe a passo fisso sulle righe ordinate per tipo: tutti i tipi in proporzione, e lo stesso campione a
    ogni esecuzione."""
    rows = sorted(rows, key=lambda r: (r["kind"], question_of(r)))
    if not limit or limit >= len(rows):
        return rows
    return [rows[i * len(rows) // limit] for i in range(limit)]


def retry_seconds(body, retry_after):
    """Attesa chiesta da un 429: Retry-After in secondi, poi il retryDelay di Gemini nel corpo, altrimenti 20 s."""
    if retry_after and retry_after.strip().isdigit():
        return int(retry_after.strip())
    m = re.search(r'"retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"', body)
    return float(m.group(1)) + 1 if m else 20


def chat(base, key, model, temperature, system, question):
    body = {"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": question}]}
    if temperature is not None:
        body["temperature"] = temperature
    request = urllib.request.Request(f"{base}/chat/completions", json.dumps(body).encode("utf-8"), method="POST", headers={
        "Authorization": f"Bearer {key}", "Content-Type": "application/json", "User-Agent": USER_AGENT})
    for _ in range(5):
        try:
            with urllib.request.urlopen(request, timeout=180) as r:
                reply = json.loads(r.read().decode("utf-8"))
            return reply["choices"][0]["message"]["content"] or ""
        except urllib.error.HTTPError as e:
            text = e.read().decode("utf-8", "replace")
            if e.code == 429 and "perday" in text.lower():
                raise DailyQuota(text[:300]) from e
            if e.code not in (429, 500, 502, 503, 504, 529):
                raise SystemExit(f"HTTP {e.code}: {text[:500]}") from e
            wait = min(retry_seconds(text, e.headers.get("Retry-After")), MAX_WAIT_SECONDS)
            print(f"  HTTP {e.code}, nuovo tentativo tra {wait:.0f} s", flush=True)
            time.sleep(wait)
    raise SystemExit("troppi errori temporanei di fila")


def correct(row, checks):
    """Risposta giusta: rifiuto per le righe negative, risposta per le positive (rifiuto anche con parole proprie)."""
    return checks["refused"] == row["kind"].startswith("neg")


def report(done):
    """Per variante: righe, giuste per neg e pos, e sulle risposte non rifiutate letterali, aderenti, fuori e la quota
    media delle parole lunghe del contesto; poi oltre 450 caratteri e lingua sbagliata."""
    print("| variante | righe | neg rifiutate | pos con risposta | letterali | aderenti | fuori | parole dal contesto | "
          "oltre 450 | lingua sbagliata |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for variant in VARIANTS:
        rows = [d for d in done if d["variant"] == variant]
        if rows:
            print(report_line(variant, rows))


def pct(part, whole):
    return f"{100 * len(part) / len(whole):.1f}% ({len(part)}/{len(whole)})" if whole else "-"


def report_line(variant, rows):
    neg = [d for d in rows if d["kind"].startswith("neg")]
    pos = [d for d in rows if d["kind"].startswith("pos")]
    answered = [d for d in rows if not d["refused"]]
    shares = [d["context_share"] for d in answered if d["context_share"] is not None]
    fidelity = " | ".join(pct([d for d in answered if d["fidelity"] == f], answered) for f in ("literal", "adherent", "out"))
    share = f"{sum(shares) / len(shares):.2f}" if shares else "-"
    return (f"| {variant} | {len(rows)} | {pct([d for d in neg if d['correct']], neg)} | "
            f"{pct([d for d in pos if d['correct']], pos)} | {fidelity} | {share} | "
            f"{sum(d['too_long'] for d in rows)} | {sum(d['wrong_lang'] for d in rows)} |")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--service", choices=SERVICES, required=True)
    ap.add_argument("--model", help="modello del servizio (default: quello di Automatico nell'app)")
    ap.add_argument("--dataset", default=DEFAULT_DATASET, help="dataset del test esteso (*.en.jsonl: test inglese)")
    ap.add_argument("--limit", type=int, default=100, help="righe del campione (0 = tutte)")
    ap.add_argument("--rpm", type=float, default=5, help="richieste al minuto (Gemini gratuito: 5)")
    ap.add_argument("--answers", type=Path, required=True, help="JSONL delle risposte; se esiste si riprende")
    a = ap.parse_args()

    base, default_model, key_var, temperature = SERVICES[a.service]
    key = os.environ.get(key_var)
    if not key:
        raise SystemExit(f"{key_var} assente nell'ambiente: serve la chiave API del servizio")
    model = a.model or default_model
    rows, _ = load_test_rows(True, a.dataset)
    rows = sample(rows, a.limit)
    done = [json.loads(line) for line in a.answers.open(encoding="utf-8")] if a.answers.exists() else []
    seen = {(d["variant"], d["question"], d["kind"]) for d in done}
    print(f"{a.service} {model}: {len(rows)} righe x {len(VARIANTS)} varianti, {len(done)} gia' fatte", flush=True)
    interval = 60 / a.rpm
    with a.answers.open("a", encoding="utf-8", buffering=1) as out:
        try:
            for n, row in enumerate(rows, 1):
                question, lang = question_of(row).strip(), dataset_lang(row)
                for variant in VARIANTS:
                    if (variant, question, row["kind"]) in seen:
                        continue
                    started = time.monotonic()
                    system = system_prompt(context_of(row) if variant == "rag" else "", lang)
                    got = chat(base, key, model, temperature, system, question)
                    checks = instruction_checks(row, got)
                    d = {"variant": variant, "kind": row["kind"], "region": row["region"], "question": question,
                         "got": got, "correct": correct(row, checks), **checks}
                    out.write(json.dumps(d, ensure_ascii=False) + "\n")
                    done.append(d)
                    time.sleep(max(0.0, interval - (time.monotonic() - started)))
                print(f"[online] {n}/{len(rows)}", flush=True)
        except DailyQuota as e:
            print(f"quota giornaliera finita dopo {len(done)} risposte: {e}", flush=True)
    print(f"servizio {a.service}, modello {model}, dataset {a.dataset}")
    report(done)


if __name__ == "__main__":
    main()
