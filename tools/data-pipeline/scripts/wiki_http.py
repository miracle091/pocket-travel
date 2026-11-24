#!/usr/bin/env python3
"""Richieste JSON a Wikidata e Wikimedia con nuovi tentativi, condivise da city_population, poi_wikidata e
wikidata_missions: attese crescenti con un po' di casualita', Retry-After della risposta, tetto all'attesa.

Si riprova su 429 e 5xx, errori di rete e risposte che non sono JSON; gli altri 4xx (richiesta sbagliata) si rilanciano
subito. Ogni chiamante sceglie i suoi tentativi e la sua scadenza.

Uso: from wiki_http import get_json
"""
import json
import random
import sys
import time
import urllib.error
import urllib.request

UA = {"User-Agent": "pocket-travel-cities/1.0 (https://github.com/miracle091/pocket-travel)"}
# Tre shard chiedono a Wikimedia insieme e ricevono 429 a raffiche: fino a 5 attese (10 s, 20 s... 160 s, o il
# Retry-After della risposta) prima di arrendersi, al massimo 5 minuti l'una.
RETRY_ATTEMPTS = 6
RETRY_BASE_S = 10
RETRY_MAX_S = 300
RETRY_JITTER_S = 5


def retry_wait(error, attempt):
    """Secondi di attesa prima del tentativo [attempt] + 1: il Retry-After di un 429 o 503 se c'e', altrimenti 10, 20,
    40... secondi; piu' fino a 5 secondi a caso, perche' gli shard che girano insieme non riprovino nello stesso istante."""
    retry_after = error.headers.get("Retry-After") if isinstance(error, urllib.error.HTTPError) and error.headers else None
    wait = int(retry_after) if retry_after and retry_after.isdigit() else RETRY_BASE_S * 2 ** attempt
    return min(wait, RETRY_MAX_S) + random.uniform(0, RETRY_JITTER_S)


def get_json(url, data=None, headers=UA, timeout=60, service="Wikimedia", attempts=RETRY_ATTEMPTS, deadline=None):
    """JSON di [url] (POST se c'e' [data]), con nuovi tentativi e attese crescenti su 429, 5xx e errori di rete.
    Con [deadline] (time.monotonic()) non inizia un tentativo dopo la scadenza (TimeoutError) e il timeout della
    richiesta non la supera."""
    for attempt in range(attempts):
        remaining = None if deadline is None else deadline - time.monotonic()
        if remaining is not None and remaining <= 0:
            raise TimeoutError("tempo massimo superato")
        try:
            request = urllib.request.Request(url, data=data, headers=headers)
            with urllib.request.urlopen(request, timeout=timeout if remaining is None else min(timeout, remaining)) as r:
                return json.loads(r.read().decode("utf-8"))
        except (OSError, ValueError) as e:  # URLError e HTTPError sono OSError; un corpo non JSON e' ValueError
            if isinstance(e, urllib.error.HTTPError) and e.code != 429 and e.code < 500:
                raise
            if attempt == attempts - 1:
                raise
            wait = retry_wait(e, attempt)
            print(f"-- {service}: {e}, riprovo tra {wait:.0f}s", file=sys.stderr)
            time.sleep(wait)
