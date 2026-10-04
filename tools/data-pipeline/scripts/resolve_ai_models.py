#!/usr/bin/env python3
"""Impronte (sha256, dimensione) dei modelli IA da scrivere in app-status.json, per publish-apk.yml.

Per ogni modello del catalogo dell'app (LlmModelCatalog.kt) sceglie, tra le impronte di fonti nostre, quella
che coincide con il file che HuggingFace serve ora (X-Linked-ETag e X-Linked-Size del suo url):
- quella del catalogo;
- quella dell'app-status.json online, solo se il chiamante ne ha verificato la firma (stesso modelId e nome
  file): un modello ricaricato con upload_hf.py --update-app-status non torna alla versione precedente al
  rilascio successivo, anche se il catalogo non e' stato aggiornato.
HuggingFace sceglie e non fornisce valori: un file ricaricato da altri non diventa mai un'impronta firmata.
Se per un modello nessuna impronta coincide, esce con errore: l'app rifiuterebbe quel download.

Uso: python resolve_ai_models.py <LlmModelCatalog.kt> [<app-status.json online, firma verificata>] > ai-models.json
"""
import http.client
import json
import re
import sys
import time
from urllib.parse import urlsplit


def catalog_models(kotlin_source):
    """Modelli pubblicati del catalogo (sha256 non nullo), con url."""
    content = kotlin_source[kotlin_source.index("listOf("):]
    models = []
    for block in re.findall(r"LlmModelDefinition\((.*?)\n\s*\),", content, re.S):
        def field(name):
            m = re.search(rf'\b{name}\s*=\s*"([^"]*)"', block)
            return m.group(1) if m else None

        size = re.search(r"\bsizeBytes\s*=\s*([0-9_]+)L", block)
        if field("id") and field("fileName") and field("sha256") and field("url") and size:
            models.append({"modelId": field("id"), "modelVersion": field("fileName"),
                           "sha256": field("sha256").lower(), "sizeBytes": int(size.group(1).replace("_", "")),
                           "url": field("url")})
    return models


def hf_fingerprint(url, attempts=3):
    """(sha256, dimensione) del file LFS dietro `url`, dalla risposta senza seguire il redirect; None se assenti."""
    parts = urlsplit(url)
    for attempt in range(attempts):
        try:
            conn = http.client.HTTPSConnection(parts.netloc, timeout=30)
            conn.request("HEAD", parts.path)
            response = conn.getresponse()
            etag, size = response.getheader("X-Linked-ETag"), response.getheader("X-Linked-Size")
            conn.close()
            if etag and size:
                return etag.strip('"').lower(), int(size)
            return None
        except OSError:
            if attempt == attempts - 1:
                raise
            time.sleep(10 * (attempt + 1))
    return None


def resolve(catalog, online, fingerprint_of):
    """Voci di aiModels; errore con l'elenco dei modelli per cui nessuna impronta coincide con HuggingFace."""
    resolved, errors = [], []
    for model in catalog:
        remote = fingerprint_of(model["url"])
        candidates = [model] + [o for o in online
                                if o["modelId"] == model["modelId"] and o["modelVersion"] == model["modelVersion"]]
        match = next((c for c in candidates if remote == (c["sha256"].lower(), c["sizeBytes"])), None)
        if match is None:
            errors.append(f"{model['modelId']} ({model['modelVersion']}): HuggingFace ha {remote}, "
                          f"catalogo e app-status.json online hanno {[(c['sha256'], c['sizeBytes']) for c in candidates]}")
            continue
        resolved.append({"modelId": model["modelId"], "modelVersion": model["modelVersion"],
                         "sha256": match["sha256"].lower(), "sizeBytes": match["sizeBytes"]})
    if errors:
        raise ValueError("impronte dei modelli diverse dai file su HuggingFace (l'app rifiuterebbe questi "
                         "download; ricarica con upload_hf.py --update-app-status o aggiorna il catalogo):\n  "
                         + "\n  ".join(errors))
    return resolved


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as f:
        catalog = catalog_models(f.read())
    online = []
    if len(sys.argv) > 2:
        with open(sys.argv[2], encoding="utf-8") as f:
            online = json.load(f).get("aiModels", [])
    if not catalog:
        sys.exit(f"nessun modello estratto da {sys.argv[1]}: formato di LlmModelCatalog.kt cambiato?")
    try:
        print(json.dumps(resolve(catalog, online, hf_fingerprint)))
    except ValueError as error:
        sys.exit(str(error))
