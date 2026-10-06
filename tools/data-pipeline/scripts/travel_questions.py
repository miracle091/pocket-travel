#!/usr/bin/env python3
"""Domande di viaggio scritte come le scrivono le persone, per i dataset SFT (al posto dei soli modelli a mano).

Fonte: la prima domanda dell'utente di ogni conversazione di soniawmeyer/travel-conversations-finetuning (MIT), solo per
le righe di UltraChat (openbmb/UltraChat, MIT): domande scritte da un modello (ChatGPT) che imita l'utente, nessun testo
preso dal web. Restano fuori le altre due fonti del dataset: Reddit (post di utenti, senza licenza di ridistribuzione) e
Dolly (CC BY-SA 3.0, ma domande quasi tutte sul passo di Wikipedia allegato e poche decine di righe). Si usano solo le
domande, mai le risposte dell'assistente: il bersaglio dell'esempio e' sempre una risposta estratta dal contesto o il
rifiuto fisso, quindi la domanda e' solo l'input del modello.

Si tengono le domande che un utente potrebbe fare sulla regione in cui si trova senza nominare nessun luogo (inglese,
una frase, niente nomi propri ne' numeri), e che toccano una sola categoria delle tabelle di parole chiave del
generatore (le ambigue si scartano). Per il dataset italiano le domande si traducono con translate_dataset.Translator.

Il dataset e' ordinato per fonte (prima ultrachat, poi dolly, poi reddit): fetch si ferma alla prima pagina senza righe
ammesse dopo averne viste.

Prova: python travel_questions.py [cartella cache]   (scarica una volta, poi stampa conteggi ed esempi per categoria)
"""
import ast
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path

DATASET = "soniawmeyer/travel-conversations-finetuning"
DATASET_REVISION = "f07f32569d74755274b75178d8e61d5da9f350e4"  # require_revision
DATASET_URL = f"https://huggingface.co/datasets/{DATASET}/tree/{DATASET_REVISION}"
# fonte del dataset -> (dataset originale, licenza); le altre fonti (reddit, dolly) non si usano, vedi sopra
ALLOWED_SOURCES = {"ultrachat": ("openbmb/UltraChat", "MIT")}
# Riga di ATTRIBUTION come per OFF_TOPIC_SOURCES: ("-", "domande di viaggio (<fonte>)", url, licenza)
ATTRIBUTION = [("-", f"domande di viaggio ({DATASET}, da {up})", DATASET_URL, lic) for up, lic in ALLOWED_SOURCES.values()]

UA = {"User-Agent": "pocket-travel-sft/0.9 (https://github.com/miracle091/pocket-travel)"}
API = "https://datasets-server.huggingface.co/rows"
PAGE = 100
CACHE_NAME = "travel_questions.tsv"

MIN_CHARS, MAX_CHARS = 15, 120
STARTERS = {"what", "which", "where", "when", "how", "who", "why", "is", "are", "can", "could", "do", "does", "did",
            "should", "will", "would", "any", "am", "may", "must", "has", "have"}
URL_OR_MAIL = re.compile(r"https?:|www\.|\.com|\.org|@", re.I)
# Compiti di scrittura e rivolte all'assistente, non domande che un viaggiatore farebbe a una guida
CHAT = re.compile(r"\b(write|compose|draft|essay|poem|story|stories|article|blog|summari[sz]e|paraphrase|translate|"
                  r"generate|create|as an ai|language model|chatbot|recommend me|suggest me|tell me a|give me a|"
                  r"your (favou?rite|opinion|thoughts?|experiences?|personal)|have you|you ever|are you|"
                  r"movie|song|novel|documentary)\b", re.I)
# Temi da saggio o da corso (UltraChat ne e' pieno), non di una persona in viaggio; "these/those/such as" rimandano a un
# discorso precedente che qui non c'e'
ACADEMIC = re.compile(r"\b(impacts?|influenc\w*|affect\w*|evolv\w*|promot\w*|contribut\w*|industry|industries|economy|"
                      r"economic|sustainab\w*|environmental|international|government|polic(?:y|ies)|compan(?:y|ies)|"
                      r"business(?:es)?|employees?|airlines?|cruises?|students?|history|historical|development|"
                      r"conservation\w*|preserv\w*|tourism|these|those|such as|explain|describe|provide|elaborate|"
                      r"discuss|compare|analy[sz]e|breakdown|significan\w*|trends?|your (?:city|country|town|region|state)|"
                      r"how (?:has|have))\b", re.I)
# Racconti in prima persona o riferiti a un viaggio gia' pianificato: servono "can I", "should I", "do I", non "my trip"
STORY = re.compile(r"\b(my|our|we|we're|we've|we'd|us|mine)\b|\bI(?:'m| am| was| have|'ve| had|'d| went| visited| love| like)\b", re.I)
# "I" e le sue contrazioni sono le uniche parole con la maiuscola ammesse dopo la prima
ALLOWED_CAPS = re.compile(r"^I(?:'(?:m|ve|d|ll))?$")


def parse_data(raw):
    """Prima battuta dell'utente dal campo `data` (lista Python tra apostrofi/virgolette: utente, assistente, utente...).
    None se il campo non e' una lista di stringhe (le righe Reddit e Dolly sono dizionari)."""
    try:
        value = ast.literal_eval(raw)
    except (ValueError, SyntaxError, MemoryError, RecursionError):
        return None
    if isinstance(value, list) and value and isinstance(value[0], str):
        return re.sub(r"\s+", " ", value[0]).strip()
    return None


def _get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def require_revision(dataset, revision):
    """RuntimeError se [dataset] su HuggingFace non e' piu' alla revisione [revision]. datasets-server legge solo
    l'ultima revisione: senza questo controllo un dataset cambiato finirebbe nel training senza che nessuno l'abbia
    guardato. Va chiamata prima di riempire una cache (con la cache non serve la rete)."""
    sha = _get(f"https://huggingface.co/api/datasets/{dataset}")["sha"]
    if sha != revision:
        raise RuntimeError(f"{dataset} e' cambiato (revisione {sha[:7]}, fissata {revision[:7]}): controlla le "
                           "modifiche e aggiorna la revisione nel codice")


def _page(offset):
    url = f"{API}?" + urllib.parse.urlencode({"dataset": DATASET, "config": "default", "split": "train",
                                              "offset": offset, "length": PAGE})
    for attempt in range(1, 7):  # datasets-server risponde 429 se le richieste sono fitte
        try:
            return _get(url)
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503) or attempt == 6:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 6:
                raise
        time.sleep(10 * attempt)


def fetch(cache_path):
    """Prime domande delle sole fonti ammesse, in cache_path (una riga "fonte<TAB>domanda"); la cache, se c'e', si
    riusa senza rete. Restituisce la lista delle domande."""
    cache_path = Path(cache_path)
    if not cache_path.exists():
        require_revision(DATASET, DATASET_REVISION)
        rows, offset, seen = [], 0, False
        while True:
            page = _page(offset)["rows"]
            if not page:
                break
            kept = 0
            for r in page:
                row = r["row"]
                if row["source"] not in ALLOWED_SOURCES:
                    continue
                q = parse_data(row["data"])
                kept += 1
                if q:
                    rows.append(f"{row['source']}\t{q.replace(chr(9), ' ')}")
            if kept:
                seen = True
            elif seen:
                break  # ordinato per fonte: finite quelle ammesse
            offset += PAGE
            time.sleep(0.5)
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = cache_path.with_suffix(".tmp")
        tmp.write_text("\n".join(rows) + "\n", encoding="utf-8")
        os.replace(tmp, cache_path)
    return [l.split("\t", 1)[1] for l in cache_path.read_text(encoding="utf-8").splitlines() if "\t" in l]


def _category(low, keywords):
    """Categorie le cui radici compaiono a inizio parola (come covers, ma senza "dress" in "address")."""
    padded = " " + low
    return [c for c, ks in keywords.items()
            if any(re.search(re.escape(k) if k[0] == " " else r"(?<![a-z])" + re.escape(k), padded) for k in ks)]


def classify(question, keywords):
    """(categoria, None) se la domanda e' adatta, altrimenti (None, motivo dello scarto)."""
    q = question.replace("’", "'").strip()
    low = q.lower()
    if not MIN_CHARS <= len(q) <= MAX_CHARS:
        return None, "lunghezza"
    words = re.findall(r"[A-Za-z][A-Za-z'-]*", q)
    if any(c.isdigit() for c in q) or URL_OR_MAIL.search(q):
        return None, "cifre o url"
    if (not q.endswith("?") or any(c in q[:-1] for c in "?!.;:\n") or not q.isascii()
            or not words or words[0].lower() not in STARTERS):
        return None, "forma"
    if CHAT.search(q):
        return None, "chiacchiera"
    if ACADEMIC.search(q):
        return None, "da saggio"
    if STORY.search(q):
        return None, "prima persona"
    if any(w[0].isupper() and not ALLOWED_CAPS.match(w) for w in words[1:]):
        return None, "nome proprio"
    cats = _category(low, keywords)
    if not cats:
        return None, "nessuna categoria"
    if len(cats) > 1:
        return None, "ambigua"
    return cats[0], None


def select(questions, keywords, per_category=200, seed=42, stats=None):
    """{categoria: [domande]} con le domande adatte (vedi classify), senza doppioni (maiuscole ignorate), al massimo
    per_category per categoria; deterministico: ordine alfabetico e campione con seed. [stats] (Counter) conta i motivi
    di scarto."""
    by_cat = {c: [] for c in keywords}
    seen = set()
    for q in sorted(set(questions)):
        key = q.lower().strip()
        if key in seen:
            if stats is not None:
                stats["doppione"] += 1
            continue
        seen.add(key)
        cat, why = classify(q, keywords)
        if cat:
            by_cat[cat].append(q.strip())
        elif stats is not None:
            stats[why] += 1
    rng = random.Random(seed)
    return {c: sorted(rng.sample(qs, per_category)) if len(qs) > per_category else qs for c, qs in by_cat.items()}


def to_italian(by_cat, translator):
    """Come by_cat ma tradotto: [translator].translate_many(frasi) -> traduzioni nello stesso ordine, None se una e'
    sospetta. Si scartano None, vuote, senza "?" finale e i doppioni."""
    flat = [(c, q) for c, qs in by_cat.items() for q in qs]
    out = {c: [] for c in by_cat}
    for (c, _), t in zip(flat, translator.translate_many([q for _, q in flat])):
        t = (t or "").strip()
        if t.endswith("?") and t not in out[c]:
            out[c].append(t)
    return out


def load(cache_dir, lang, keywords, translator=None, per_category=200):
    """{categoria: [domande]} in `lang` ("en" o "it"): scarica o legge la cache in cache_dir; per "it" traduce con
    [translator] (di default Translator("en", "it", cache_dir): serve torch/transformers)."""
    by_cat = select(fetch(Path(cache_dir) / CACHE_NAME), keywords, per_category)
    if lang == "en":
        return by_cat
    if translator is None:
        from translate_dataset import Translator
        translator = Translator("en", "it", cache_dir)
    return to_italian(by_cat, translator)


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from generate_sft_dataset_en import KEYWORDS
    cache_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "data" / "sft" / "raw"
    qs = fetch(cache_dir / CACHE_NAME)
    drops = Counter()
    by_cat = select(qs, KEYWORDS, stats=drops)
    print(f"{len(qs)} domande scaricate; scartate: " + ", ".join(f"{w} {n}" for w, n in drops.most_common()))
    for c, v in by_cat.items():
        print(f"\n{c}: {len(v)}")
        for q in v[:3]:
            print("  ", q)
