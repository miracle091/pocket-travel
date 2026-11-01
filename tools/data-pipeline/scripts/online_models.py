#!/usr/bin/env python3
"""Aggiunge a online-models.json gli altri modelli di chat di ogni servizio, con i prezzi, per l'elenco firmato.

L'elenco scritto a mano (feature/ai/src/main/assets/online-models.json) propone uno o due modelli per servizio;
questo script ci aggiunge `otherModels`: gli altri modelli di chat dei quattro servizi, presi da due elenchi
pubblici, LiteLLM (model_prices_and_context_window.json, MIT) e models.dev (api.json, MIT). Nessun servizio ha
un'API dei prezzi. L'app mostra un modello di `otherModels` solo se compare anche in `/models` dell'account.

Un modello entra solo se e' in tutti e due gli elenchi, come modello di chat che legge e scrive testo, non
ritirato e fuori dalle famiglie escluse (voce, immagini, ricerca, agenti, codice). Una versione con la data
(`gpt-4o-2024-08-06`, `claude-haiku-4-5-20251001`) si salta se c'e' anche il nome senza data. Prezzi e
temperatura vengono da models.dev, che li tiene per servizio; LiteLLM conferma che il modello esiste ed e' di
chat, e un prezzo diverso tra le due fonti si stampa come avviso (LiteLLM ha errori noti, come mistral-large-4).

Uso: python online_models.py <online-models.json> <litellm.json> <models.dev api.json> <uscita>
"""
import json
import re
import sys

# Servizio dell'app -> (litellm_provider e prefisso della chiave in LiteLLM, servizio in models.dev).
PROVIDERS = {
    "CHATGPT": ("openai", "", "openai"),
    "MISTRAL": ("mistral", "mistral/", "mistral"),
    "GEMINI": ("gemini", "gemini/", "google"),
    "CLAUDE": ("anthropic", "", "anthropic"),
}

# Famiglie che non rispondono a una domanda con del testo, o non dall'endpoint di chat. LiteLLM segna alcuni di
# questi come "chat" (lyria, tts), quindi il filtro per nome serve anche dove la modalita' dice chat.
EXCLUDED = re.compile(
    r"tts|audio|realtime|transcri|speech|image|imagen|veo|lyria|embed|moderation|search|deep-research|"
    r"computer-use|codex|codestral|voxtral|customtools|ocr|robotics|live",
    re.IGNORECASE,
)

MODEL_ID = re.compile(r"\A[A-Za-z0-9._:-]+\Z")
DATED = re.compile(r"\A(.+)-(\d{8}|\d{4}-\d{2}-\d{2})\Z")
PER_MTOK = 1_000_000


def litellm_prices(entry):
    """(ingresso, uscita) in dollari per milione di token di un modello di chat di LiteLLM, o None."""
    if not isinstance(entry, dict) or entry.get("mode") != "chat":
        return None
    endpoints = entry.get("supported_endpoints")
    if endpoints is not None and "/v1/chat/completions" not in endpoints:
        return None
    inp, out = entry.get("input_cost_per_token"), entry.get("output_cost_per_token")
    if not isinstance(inp, (int, float)) or not isinstance(out, (int, float)):
        return None
    return round(inp * PER_MTOK, 4), round(out * PER_MTOK, 4)


def modelsdev_model(entry):
    """(ingresso, uscita, temperatura) di un modello di models.dev che legge e scrive testo, o None."""
    modalities = entry.get("modalities") or {}
    if "text" not in modalities.get("input", []) or modalities.get("output") != ["text"]:
        return None
    if entry.get("status") == "deprecated":
        return None
    cost = entry.get("cost") or {}
    inp, out = cost.get("input"), cost.get("output")
    if not isinstance(inp, (int, float)) or not isinstance(out, (int, float)):
        return None
    return inp, out, bool(entry.get("temperature", True))


def other_models(curated, litellm, modelsdev):
    """otherModels per servizio e avvisi sui prezzi diversi tra le due fonti."""
    result, warnings = {}, []
    for provider, (litellm_provider, prefix, modelsdev_provider) in PROVIDERS.items():
        proposed = {m["id"] for m in curated["providers"].get(provider, [])}
        listed_ids = set((modelsdev.get(modelsdev_provider) or {}).get("models", {}))
        models = []
        for model_id, entry in sorted((modelsdev.get(modelsdev_provider) or {}).get("models", {}).items()):
            if model_id in proposed or not MODEL_ID.match(model_id) or EXCLUDED.search(model_id):
                continue
            dated = DATED.match(model_id)
            if dated and (dated.group(1) in listed_ids or dated.group(1) in proposed):
                continue
            listed = litellm.get(prefix + model_id)
            if not isinstance(listed, dict) or listed.get("litellm_provider") != litellm_provider:
                continue
            first, second = modelsdev_model(entry), litellm_prices(listed)
            if first is None or second is None:
                continue
            inp, out = first[0], first[1]
            if (inp, out) != second:
                warnings.append(f"{provider} {model_id}: models.dev {first[0]}/{first[1]}, LiteLLM {second[0]}/{second[1]}")
            models.append({"id": model_id, "temperature": first[2], "inputUsdPerMTok": inp, "outputUsdPerMTok": out})
        if models:
            result[provider] = models
    return result, warnings


def main(argv):
    if len(argv) != 5:
        sys.exit(__doc__)
    paths = argv[1:4]
    curated, litellm, modelsdev = (json.load(open(p, encoding="utf-8")) for p in paths)
    others, warnings = other_models(curated, litellm, modelsdev)
    for warning in warnings:
        print(f"prezzi diversi, vale models.dev: {warning}")
    print(", ".join(f"{p}: {len(m)}" for p, m in others.items()) or "nessun altro modello")
    if not others:
        sys.exit("nessun altro modello trovato: le fonti sono cambiate?")
    with open(argv[4], "w", encoding="utf-8") as f:
        json.dump({**curated, "otherModels": others}, f, ensure_ascii=False, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main(sys.argv)
