#!/usr/bin/env python3
"""Accuratezza della vision dei modelli sul set di foto etichettate (tools/data-pipeline/vision/photo-set.json).

Per ogni foto e lingua manda la domanda e l'immagine a un servizio con l'API di chat di OpenAI (/chat/completions, immagine
come data URI JPEG) e conta giusta la risposta che contiene, senza accenti ne' maiuscole, almeno una variante di ogni gruppo
di "expected". Le immagini vengono da Wikimedia Commons (miniatura larga IMAGE_WIDTH px) e restano in --cache.

Uso: vision_eval.py --base-url URL --model MODELLO --key-env NOME_VARIABILE [--lang it|en|both] [--limit N]
                    [--cache CARTELLA] [--out risultati.jsonl] [--set photo-set.json]
La chiave si legge dalla variabile d'ambiente --key-env (con .claude/scripts/with-env.mjs, mai sulla riga di comando).
Esempio: bun .claude/scripts/with-env.mjs -- python vision_eval.py --base-url https://api.mistral.ai/v1
         --model mistral-small-latest --key-env MISTRAL_API_KEY
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

USER_AGENT = "pocket-travel-vision_eval/1.0 (https://github.com/miracle091/pocket-travel)"
IMAGE_WIDTH = 1280
SET_FILE = Path(__file__).resolve().parent.parent / "vision" / "photo-set.json"
INSTRUCTION = {
    "it": "Rispondi in italiano, in una o due frasi, guardando solo la foto.",
    "en": "Answer in English, in one or two sentences, looking only at the photo.",
}


def fold(text):
    """[text] in minuscolo e senza accenti, per il confronto con le parole attese."""
    return "".join(c for c in unicodedata.normalize("NFKD", text.lower()) if not unicodedata.combining(c))


def is_correct(answer, expected):
    """True se [answer] contiene almeno una variante di ogni gruppo di [expected]."""
    folded = fold(answer)
    return all(any(fold(variant) in folded for variant in group) for group in expected)


def load_set(path=SET_FILE):
    with open(path, encoding="utf-8") as f:
        return json.load(f)["photos"]


def request_body(model, question, lang, jpeg):
    """Il corpo di /chat/completions con la domanda, l'istruzione della lingua e l'immagine."""
    image = "data:image/jpeg;base64," + base64.b64encode(jpeg).decode("ascii")
    return {"model": model, "temperature": 0, "max_tokens": 300, "messages": [{"role": "user", "content": [
        {"type": "text", "text": f"{question}\n{INSTRUCTION[lang]}"},
        {"type": "image_url", "image_url": {"url": image}},
    ]}]}


def summary(rows):
    """{(gruppo, lingua): (giuste, totale)} e il totale per lingua sotto il gruppo "tutte"."""
    out = defaultdict(lambda: [0, 0])
    for row in rows:
        for key in ((row["group"], row["lang"]), ("tutte", row["lang"])):
            out[key][0] += row["correct"]
            out[key][1] += 1
    return {k: tuple(v) for k, v in out.items()}


def _get(url, data=None, headers=None, timeout=120):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def cache_name(title):
    """Nome del file della cache per [title]: un hash, perche' i titoli di Commons hanno caratteri che Windows non accetta."""
    return hashlib.sha256(title.encode("utf-8")).hexdigest()[:24] + ".jpg"


def commons_jpeg(title, cache):
    """La miniatura JPEG di [title] (File:...) da Commons, salvata in [cache]."""
    cache.mkdir(parents=True, exist_ok=True)
    local = cache / cache_name(title)
    if local.is_file():
        return local.read_bytes()
    query = urllib.parse.urlencode({"action": "query", "titles": title, "prop": "imageinfo", "iiprop": "url",
                                    "iiurlwidth": str(IMAGE_WIDTH), "format": "json", "formatversion": "2"})
    info = json.loads(_get("https://commons.wikimedia.org/w/api.php?" + query))
    url = info["query"]["pages"][0]["imageinfo"][0]["thumburl"]
    data = _get(url)
    local.write_bytes(data)
    time.sleep(0.5)
    return data


def ask(base_url, key, body):
    """La risposta di testo del servizio, con un nuovo tentativo dopo un 429 o un 5xx."""
    for attempt in range(3):
        try:
            raw = _get(base_url.rstrip("/") + "/chat/completions", data=json.dumps(body).encode("utf-8"),
                       headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
            return json.loads(raw)["choices"][0]["message"]["content"] or ""
        except urllib.error.HTTPError as e:
            if e.code != 429 and e.code < 500 or attempt == 2:
                raise
            time.sleep(5 * (attempt + 1))
    return ""


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--key-env", required=True)
    ap.add_argument("--lang", choices=["it", "en", "both"], default="both")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--cache", type=Path, default=Path("vision-cache"))
    ap.add_argument("--out", type=Path)
    ap.add_argument("--set", type=Path, default=SET_FILE)
    args = ap.parse_args(argv)
    key = os.environ.get(args.key_env)
    if not key:
        print(f"Variabile {args.key_env} assente", file=sys.stderr)
        return 2
    langs = ["it", "en"] if args.lang == "both" else [args.lang]
    rows = []
    for photo in load_set(args.set)[:args.limit]:
        jpeg = commons_jpeg(photo["file"], args.cache)
        for lang in langs:
            answer = ask(args.base_url, key, request_body(args.model, photo["question"][lang], lang, jpeg))
            row = {"id": photo["id"], "group": photo["group"], "lang": lang, "correct": is_correct(answer, photo["expected"]), "answer": answer}
            rows.append(row)
            print(f"{'OK ' if row['correct'] else 'NO '} {photo['id']} {lang}: {answer[:120]!r}")
            time.sleep(1)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.writelines(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
    for (group, lang), (right, total) in sorted(summary(rows).items()):
        print(f"{group:10} {lang}: {right}/{total} ({100 * right / total:.0f}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
