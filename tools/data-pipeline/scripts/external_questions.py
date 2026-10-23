#!/usr/bin/env python3
"""Domande da dataset esterni per i rifiuti del dataset SFT: si prendono SOLE le domande, mai le risposte.

Il bersaglio dell'esempio resta il rifiuto fisso ("Il contesto non contiene informazioni ..."): le guide non coprono questi
temi, quindi la domanda e' solo l'input del modello.

Fonti:
- WebFAQ (PaDaS-Lab/webfaq, CC BY 4.0, un file JSONL per lingua: ita, eng): domande di FAQ di siti di hotel e servizi
  turistici (check-in, animali ammessi, colazione, cancellazione...) di argomento "Traveling and Hospitality". Sono i
  rifiuti realistici per le guide, che non parlano di cio' che un albergo decide per conto suo. Il file inglese pesa
  ~27 GB: se ne leggono WINDOWS finestre di WINDOW_BYTES, equidistanti (il file e' ordinato per sito, quindi coprono
  tutto l'alfabeto), senza mai salvare il file intero; le righe dell'argomento finiscono in un file intermedio.
- MKQA (apple/mkqa, CC BY 3.0, 10.000 domande di cultura generale uguali in 24 lingue): coppie (it, en) fuori
  argomento. Il file e' su GitHub, non nel repository Hugging Face (che ha solo lo script di caricamento).

Filtri (conteggi e motivi di scarto nel Counter `stats` che i caricatori ricevono):
  cache (una volta, dai dati grezzi): forma (lunghezza, caratteri, maiuscole e nomi propri, lingua e traduzioni
  automatiche, errori di articolo), servizio di un alloggio, parole rare (nomi e refusi), doppioni normalizzati;
  caricamento (a ogni chiamata): radici di KEYWORDS e TRAVEL_STEMS che una guida potrebbe coprire, domande del test
  esteso, tetto per sito (MAX_PER_SITE) e per schema (SKELETON_MAX).
La cache in raw_dir non dipende da KEYWORDS ne' dal test: cambiarli non lascia una cache vecchia.

Prova: python external_questions.py [cartella cache] [cartella download]
(scarica una volta, poi stampa conteggi ed esempi; i file grandi vanno in una cartella temporanea del sistema se non si
indica quella dei download)
"""
import gzip
import hashlib
import json
import os
import random
import re
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent

WEBFAQ = "PaDaS-Lab/webfaq"
WEBFAQ_REVISION = "692dd5d911a9f0172215e82e5fa1fb1fc2cc9c6e"
WEBFAQ_CONFIG = {"it": "ita", "en": "eng"}
WEBFAQ_TOPIC = "Traveling and Hospitality"
WINDOWS, WINDOW_BYTES = 20, 50_000_000  # 20 finestre da 50 MB per lingua (il file italiano, 1,5 GB, si legge a meta')
LINE_SLACK = 2_000_000  # byte oltre la fine della finestra per finire l'ultima riga

MKQA = "apple/mkqa"
MKQA_REVISION = "325131889721ae0ed885b76ecb8011369d75abad"  # repository HF: solo lo script di caricamento
# Il file sta su GitHub; l'URL e' fissato al commit che l'ha modificato per ultimo e il contenuto al suo SHA-256
MKQA_URL = ("https://raw.githubusercontent.com/apple-aiml-research/ml-mkqa/94ed242c7b8b97e72ba6ada6615362955d1b2d1e/"
            "dataset/mkqa.jsonl.gz")
MKQA_SHA256 = "f77ac6bf6306e98816c5c047880db71a3c67d2f1491b169896d63e9bb14ef3d3"
MKQA_PAGE = MKQA_URL.replace("raw.githubusercontent.com", "github.com").replace("ml-mkqa/", "ml-mkqa/blob/")

# Righe di ATTRIBUTION (regionId, nome, URL, licenza) come travel_questions.ATTRIBUTION; note nella lingua del dataset
ATTRIBUTION = {
    "it": [("-", "domande di FAQ di siti di hotel e servizi turistici (PaDaS-Lab/webfaq; solo le domande)",
            f"https://huggingface.co/datasets/{WEBFAQ}/tree/{WEBFAQ_REVISION}", "CC BY 4.0"),
           ("-", "domande di cultura generale (apple/mkqa; solo le domande)",
            MKQA_PAGE, "CC BY 3.0")],
    "en": [("-", "questions from FAQs of hotel and tourist service sites (PaDaS-Lab/webfaq; questions only)",
            f"https://huggingface.co/datasets/{WEBFAQ}/tree/{WEBFAQ_REVISION}", "CC BY 4.0"),
           ("-", "general knowledge questions (apple/mkqa; questions only)",
            MKQA_PAGE, "CC BY 3.0")],
}

FAQ_CACHE = "faq_refusals.{lang}.tsv"
MKQA_CACHE = "mkqa_off_topic.tsv"
MAX_PER_SITE = 3  # domande dello stesso sito: una catena di hotel ne ha centinaia, tutte con lo stesso modello

_UA = None


def _ua():
    """User-Agent del generatore SFT (UA di generate_sft_dataset.py), letto una volta sola."""
    global _UA
    if _UA is None:
        sys.path.insert(0, str(HERE))
        from generate_sft_dataset import UA
        _UA = UA
    return _UA


def _open(url, headers=None, method="GET"):
    req = urllib.request.Request(url, headers={**_ua(), **(headers or {})}, method=method)
    return urllib.request.urlopen(req, timeout=120)


def require_revision(dataset, revision):
    """RuntimeError se [dataset] su Hugging Face non e' piu' alla revisione [revision] (come
    travel_questions.require_revision, ma con lo User-Agent del generatore). Va chiamata prima di riempire una cache."""
    with _open(f"https://huggingface.co/api/datasets/{dataset}") as r:
        sha = json.loads(r.read())["sha"]
    if sha != revision:
        raise RuntimeError(f"{dataset} e' cambiato (revisione {sha[:7]}, fissata {revision[:7]}): controlla le "
                           "modifiche e aggiorna la revisione nel codice")


def _retry(fn, what):
    """fn() con nuovi tentativi (6) su errori di rete e risposte 429/5xx, attese crescenti."""
    for attempt in range(1, 7):
        try:
            return fn()
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503, 504) or attempt == 6:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == 6:
                raise
        print(f"  {what}: nuovo tentativo {attempt}", file=sys.stderr)
        time.sleep(10 * attempt)


# ----- download a finestre di WebFAQ -----

def windows(size, count=WINDOWS, width=WINDOW_BYTES):
    """[(inizio, fine)] di `count` finestre equidistanti di `width` byte in un file di `size`. Se le finestre
    coprirebbero piu' di meta' del file, tutto il file (una sola finestra)."""
    if size <= count * width * 2:
        return [(0, size)]
    step = (size - width) // (count - 1)
    return [(i * step, i * step + width) for i in range(count)]


def stream_lines(url, start, end, size):
    """Righe (bytes, senza a capo) che iniziano in [start, end) di un file di `size` byte, lette con richieste Range e
    riprese dal byte giunto in caso di errore. La prima riga di una finestra che non parte da 0 si salta (e' tagliata)."""
    stop = min(size, end + LINE_SLACK)
    pos, line_start, buf, skip = start, start, b"", start > 0
    failures = 0
    while pos < stop:
        try:
            with _open(url, {"Range": f"bytes={pos}-{stop - 1}"}) as r:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    pos += len(chunk)
                    failures = 0
                    *lines, buf = (buf + chunk).split(b"\n")
                    for ln in lines:
                        at, line_start = line_start, line_start + len(ln) + 1
                        if skip:
                            skip = False
                        elif at >= end:
                            return
                        else:
                            yield ln
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            failures += 1
            if failures > 6 or (isinstance(e, urllib.error.HTTPError) and e.code not in (429, 500, 502, 503, 504)):
                raise
            print(f"  lettura interrotta a {pos}: nuovo tentativo {failures}", file=sys.stderr)
            time.sleep(10 * failures)
    if buf and not skip and line_start < end and stop == size:
        yield buf  # ultima riga del file senza a capo


def webfaq_rows(lang, download_dir):
    """[(sito, domanda)] delle righe di WebFAQ nella lingua `lang` ("it" o "en") con argomento WEBFAQ_TOPIC.
    Le righe si estraggono da WINDOWS finestre del file e si tengono in download_dir (JSONL): al secondo giro non si
    scarica nulla. Le risposte non si leggono."""
    config = WEBFAQ_CONFIG[lang]
    out = Path(download_dir) / f"webfaq-{config}-{WEBFAQ_REVISION[:7]}-{WINDOWS}x{WINDOW_BYTES // 1_000_000}mb.jsonl"
    if not out.exists():
        require_revision(WEBFAQ, WEBFAQ_REVISION)
        url = f"https://huggingface.co/datasets/{WEBFAQ}/resolve/{WEBFAQ_REVISION}/data/{config}.jsonl"
        with _retry(lambda: _open(url, method="HEAD"), "dimensione") as r:
            size = int(r.headers["Content-Length"])
        mark = f'"topic": "{WEBFAQ_TOPIC}"'.encode()
        rows = []
        spans = windows(size)
        for i, (start, end) in enumerate(spans, 1):
            before = len(rows)
            for ln in stream_lines(url, start, end, size):
                if mark not in ln:
                    continue
                try:
                    d = json.loads(ln)
                except ValueError:
                    continue
                if d.get("topic") == WEBFAQ_TOPIC and isinstance(d.get("question"), str):
                    rows.append({"o": d.get("origin") or "", "q": d["question"]})
            print(f"  webfaq {config}: finestra {i}/{len(spans)}, {len(rows) - before} righe", file=sys.stderr)
        Path(download_dir).mkdir(parents=True, exist_ok=True)
        tmp = out.with_suffix(".tmp")
        tmp.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        os.replace(tmp, out)
    return [(d["o"], d["q"]) for d in map(json.loads, out.read_text(encoding="utf-8").splitlines())]


# ----- filtri di forma: domande ben scritte, in lingua, senza nomi propri -----

LENGTH = (15, 95)  # caratteri
CHARSET = {"it": re.compile(r"^[A-Za-zÀ-ÖØ-öø-ÿ' ,\-?]+$"), "en": re.compile(r"^[A-Za-z' ,\-?]+$")}
# Prime parole ammesse: pronomi e ausiliari interrogativi, verbi di servizio. Una domanda che inizia con un nome ("Yile
# Hotel accetta...") o con altro non e' una domanda generica sul servizio
STARTERS = {
    "it": {"come", "cosa", "che", "quale", "quali", "quanto", "quanta", "quanti", "quante", "dove", "quando", "chi",
           "perche", "posso", "possiamo", "possono", "si", "ci", "c'e", "e", "sono", "devo", "dobbiamo", "serve", "servono",
           "bisogna", "occorre", "accettate", "accettano", "avete", "ha", "hanno", "offrite", "offre", "offrono",
           "fornite", "fornisce", "esiste", "esistono", "viene", "vengono", "vi", "il", "la", "le", "gli", "i", "in", "a",
           "da", "per", "entro", "fino", "prima", "dopo", "fate", "fanno", "l", "un", "una", "questo", "questa", "questi",
           "queste", "cosa", "c", "forniscono", "permettete", "permette",
           "consentite", "consente", "ammettete", "ammette", "ammettono", "mettete", "mette", "prevede", "prevedete"},
    "en": {"what", "which", "where", "when", "how", "who", "why", "is", "are", "can", "could", "do", "does", "did", "should",
           "will", "would", "may", "am", "has", "have", "was", "were", "must", "if", "to", "in", "at", "for", "on", "there"},
}
# Parole funzionali della lingua: almeno una per domanda
OWN = {
    "it": {"il", "lo", "la", "le", "gli", "un", "una", "di", "del", "della", "dei", "delle", "da", "in", "con", "su", "per",
           "al", "alla", "nel", "nella", "sono", "e", "o", "ci", "si", "che", "come", "dove", "quando", "quanto", "quali",
           "quale", "posso", "possiamo", "devo", "accettate", "avete", "ha", "non", "i", "c'e", "cosa", "se", "ai", "agli",
           "alle", "noi", "voi", "vostri", "vostre", "nostri", "nostre"},
    "en": {"the", "a", "an", "of", "to", "in", "for", "is", "are", "do", "does", "can", "i", "my", "you", "your", "we",
           "what", "how", "where", "when", "which", "with", "at", "on", "there", "it", "if"},
}
# Parole di un'altra lingua (inglese nel testo italiano e viceversa, piu' spagnolo, francese, tedesco): domande miste
# o tradotte male. Solo parole che nella lingua giusta non esistono (o quasi)
FOREIGN = {
    "it": {"the", "and", "you", "your", "is", "are", "do", "does", "can", "for", "with", "to", "of", "at", "it", "we", "our",
           "my", "this", "that", "what", "how", "where", "when", "which", "there", "have", "has", "will", "be", "by", "from",
           "or", "not", "los", "las", "el", "les", "des", "du", "und", "der", "die", "das", "ist", "est", "para",
           "pour", "avec", "mit", "por", "que", "et", "y", "de", "en"},
    "en": {"il", "lo", "gli", "di", "che", "con", "una", "sono", "e", "non", "della", "del", "nel", "alla", "quale", "quali",
           "dove", "quando", "cosa", "anche", "nella", "delle", "dei", "ci", "si", "mi", "vi", "los", "las", "el",
           "les", "des", "du", "und", "der", "die", "das", "ist", "est", "para", "pour", "avec", "mit", "por", "que", "et",
           "y", "de", "la", "le", "en", "se"},
}
# Marchi e siti di prenotazione: la domanda non riguarda il servizio ma un fornitore preciso
BRANDS = re.compile(r"\b(airbnb|tripadvisor|expedia|trivago|skyscanner|hostelworld|vrbo|agoda|momondo|edreams|lastminute|"
                    r"ryanair|easyjet|lufthansa|alitalia|iberia|hilton|marriott|hyatt|accor|ibis|sixt|hertz|paypal|"
                    r"mastercard|amex|whatsapp|facebook|google|uber|msc|grimaldi|tui)\b", re.I)
# Errori tipici di traduzione automatica o di testo scritto male
VOWEL_OR_SPECIAL = r"[aeiouàèéìòù]"
BAD_PATTERNS = {
    "it": re.compile(r"\b(?:dall|nell|sull|all|dell|quest|quell|bell|sant)'\s*[bcdfgjklmnpqrstvwxyz]|\bl'\s*[bcdfgjklmnpqrtvwxyz]|"
                     r"\bil\s+(?:" + VOWEL_OR_SPECIAL + r"|z|gn|ps|s[bcdfgmnpqrstvz])|\bun\s+(?:z|gn|ps|s[bcdfgmnpqrstvz])|"
                     r"\buna\s+" + VOWEL_OR_SPECIAL + r"|\b(?:di|a|in|da|su)\s+(?:il|la|le|i)\b|\becc\b", re.I),
    "en": re.compile(r"\ba\s+(?!one\b|once\b)[aeio]\w+|\ban\s+[bcdfgjklmnpqrstvwxyz]\w+|\b(?:the|a|an)\s+(?:the|a|an)\b|\betc\b", re.I),
}
TRAILING_FUNCTION = {  # in inglese una preposizione puo' chiudere la domanda ("Which bus is it assigned to?")
    # (?<![-\w]): "check-in?" non finisce con la preposizione "in"
    "it": re.compile(r"(?<![-\w])(di|a|da|in|con|su|per|tra|fra|il|lo|la|le|i|gli|un|una|e|o|del|della|dei|delle|al|alla)\s*\?$",
                     re.I),
    "en": re.compile(r"(?<![-\w])(the|a|an|and|or)\s*\?$", re.I)}
I_FORMS = ("I", "I'm", "I'd", "I'll", "I've")


def _words(q):
    return re.findall(r"[A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ'-]*", q)


def _fold(word):
    """Parola minuscola senza accenti e senza apostrofi o trattini ai bordi ("è" e "e'" diventano "e")."""
    w = unicodedata.normalize("NFKD", word.lower())
    return "".join(c for c in w if not unicodedata.combining(c)).strip("'-")


def form_reject(question, lang):
    """Motivo per cui [question] non e' una domanda ben scritta e generica, o None se lo e'. Euristiche:
    - lunghezza tra LENGTH caratteri e una riga sola; solo lettere della lingua, spazi, virgole, trattini e un "?" finale
      (niente cifre, URL, prezzi, parentesi, due punti: un testo curato non ne ha bisogno, uno preso da una scheda si)
    - maiuscola solo all'inizio (e "I" in inglese): niente parole intere in maiuscolo ("COME FACCIO...") ne' nomi propri
      dopo la prima parola, che di norma sono nomi di hotel, luoghi e marchi
    - la prima parola e' un interrogativo, un ausiliare o un verbo di servizio (STARTERS): esclude le domande che iniziano
      con un nome e i titoli senza verbo
    - almeno una parola funzionale della lingua e nessuna di un'altra lingua (FOREIGN): testo misto o tradotto male
    - errori tipici di traduzione ("l'" davanti a consonante, "il" davanti a vocale, "a" davanti a vocale, preposizioni
      doppie), parole ripetute, domanda che finisce con una preposizione o un articolo, marchi e siti (BRANDS)"""
    q = question.replace("’", "'").replace("‘", "'")
    if "\n" in q or "  " in q or q != q.strip():
        return "spazi o a capo"
    if not LENGTH[0] <= len(q) <= LENGTH[1]:
        return "lunghezza"
    if not CHARSET[lang].match(q):
        return "caratteri"
    if not q.endswith("?") or "?" in q[:-1] or q.endswith(" ?") or "- " in q or " -" in q or ",," in q:
        return "punteggiatura"
    words = _words(q)
    if len(words) < 3 or len(words) > 16 or any(len(w) > 20 for w in words):
        return "numero di parole"
    if not q[0].isupper():
        return "maiuscola iniziale"
    if any(len(w) > 1 and w.isupper() and w not in I_FORMS for w in words):
        return "maiuscole"
    if any(w[0].isupper() and w not in I_FORMS for w in words[1:]):
        return "nome proprio"
    folded = [_fold(w) for w in words]
    if folded[0] not in STARTERS[lang] and folded[0].split("'")[0] not in STARTERS[lang]:  # "L'hotel", "What's"
        return "inizio"
    if not set(folded) & OWN[lang]:
        return "lingua"
    if set(folded) & FOREIGN[lang]:
        return "lingua mista"
    if BAD_PATTERNS[lang].search(q) or TRAILING_FUNCTION[lang].search(q):
        return "traduzione o forma"
    if any(a == b for a, b in zip(folded, folded[1:])):
        return "parola ripetuta"
    if BRANDS.search(q):
        return "marchio"
    return None


# ----- argomento: solo cio' che una guida turistica non dice -----

# Parole che riportano la domanda a un alloggio o a un servizio con un gestore ("l'hotel", "la struttura", "your property")
LODGING = {
    "it": re.compile(r"\b(hotel|alberg\w*|struttur\w*|alloggi\w*|camer\w*|stanz\w*|appartament\w*|casa vacanze|case vacanze|"
                     r"villa|ville|b&b|bed and breakfast|ostell\w*|residence|campegg\w*|agriturism\w*|stabiliment\w*|"
                     r"pension\w*|soggiorn\w*|ospit\w*|resort|suite|bungalow|cottage|chalet)\b", re.I),
    "en": re.compile(r"\b(hotels?|propert\w*|rooms?|accommodations?|apartments?|villas?|hostels?|campsites?|stays?|resorts?|"
                     r"guesthouses?|guest house|lodg\w*|inns?|b&b|bed and breakfast|suites?|guests?|cottages?|chalets?|"
                     r"bungalows?)\b", re.I),
}
# Il gestore decide da solo: la domanda vale per quell'esercizio, non per il paese o la citta' che la guida descrive
POLICY = {
    "it": re.compile(r"\b(accettat\w*|accettano|ammess\w*|consentit\w*|permess\w*|previst\w*|inclus\w*|compres\w*|offrite|"
                     r"offre|avete|disponete|dispone|vostr\w*|nostr\w*|fornit\w*|forniscono|fornite|richiest\w*)\b", re.I),
    "en": re.compile(r"\b(accept\w*|allow\w*|permitted|included|offer\w*|do you|your|our|provide\w*|require\w*|charge\w*)\b", re.I),
}
# Servizi e regole tipici di un alloggio: bastano da soli (STRONG) oppure servono un alloggio o un verbo di regola (WEAK)
STRONG = {
    "it": re.compile(r"check.?in|check.?out|colazione|cancellazion\w*|annullament\w*|culla|lettino|letto aggiunt\w*|"
                     r"letti aggiunt\w*|navett\w*|fumator\w*|fumare|ricevimento|reception|pulizie|asciugamani|biancheria|"
                     r"lavanderia|minibar|cassaforte|ascensore|caparra|mezza pensione|pensione completa|arrivo tardi|"
                     r"arrivare tardi|eta minima|deposito bagagli|servizio in camera", re.I),
    "en": re.compile(r"check.?in|check.?out|breakfast|cancell\w*|crib|cot\b|extra bed|rollaway|shuttle|smoking|smoke|"
                     r"front desk|reception|housekeeping|towels|linen|laundry|minibar|elevator|lift\b|deposit|half board|"
                     r"full board|late arrival|arrive late|minimum age|luggage storage|room service|late check", re.I),
}
WEAK = {
    "it": re.compile(r"animali|\bcani\b|\bgatti\b|\bcane\b|parcheggi\w*|posteggi\w*|piscina|palestra|\bspa\b|sauna|"
                     r"aria condizionata|climatizzat\w*|riscaldament\w*|cucina|cucinino|pagament\w*|cart[ae] di credito|"
                     r"bancomat|bambini|neonati|bagagli|prenotazion\w*|orari\w*|lavatrice|balcone|terrazza|"
                     r"accessibil\w*|disabili|sedia a rotelle|famigli\w*|tassa|fattura|sconti|fumatori|tv\b|televisione",
                     re.I),
    "en": re.compile(r"\bpets?\b|\bdogs?\b|\bcats?\b|parking|\bpool\b|\bgym\b|\bspa\b|sauna|air.?condition\w*|heating|"
                     r"kitchen\w*|payments?|credit cards?|debit cards?|\bcards?\b|children|kids|\bbabies\b|baby|luggage|"
                     r"baggage|reservations?|bookings?|amenities|hours|washing machine|balcony|terrace|"
                     r"accessib\w*|wheelchair|famil\w*|\btax\b|invoice|discount\w*|tv\b|television|noise|quiet", re.I),
}
# Domande per scegliere o cercare dove stare o mangiare: le guide hanno la sezione "dove dormire"
DISCOVERY = {
    "it": re.compile(r"\b(migliori|migliore|consigliat\w*|consigli\w*|vicin\w*|nei pressi|nelle vicinanze|in zona|centro|"
                     r"trovare|dove si trovano|quali (?:hotel|alberghi|strutture|alloggi|sistemazioni|camere)|dove (?:dormire|alloggiare|"
                     r"mangiare|soggiornare)|economic\w*|convenient\w*|popolari|piu richiest\w*)\b", re.I),
    "en": re.compile(r"\b(best|top|nearby|near|close|cheap\w*|recommend\w*|find|popular|where are|in the area|around|"
                     r"which (?:hotels?|accommodations?|properties|hostels?|rooms?)|where (?:to|can i) (?:stay|eat|sleep))\b",
                     re.I),
}


# Altri settori che le FAQ del tema "viaggi" mescolano agli alloggi (traslochi, catering, matrimoni, spedizioni...)
OTHER_BUSINESS = {
    "it": re.compile(r"\b(trasloc\w*|catering|matrimon\w*|nozze|banchett\w*|funebr\w*|funerale|fest[ae]|event[oi]|"
                     r"spedizion\w*|spedire|ordin[ei]|consegn\w*|noleggio|noleggiare|volo|voli|compagnia)\b", re.I),
    "en": re.compile(r"\b(mov(?:e|ing|ers?)|catering|wedding\w*|banquets?|funerals?|furniture|part(?:y|ies)|events?|"
                     r"shipping|ship|orders?|deliver\w*|rent(?:al)?|flights?|airlines?|car hire)\b", re.I),
}
# Nome dell'esercizio in minuscolo: "presso <nome>" e "Il <tre parole> dispone di..." (italiano)
IT_NAMED = re.compile(r"\bpresso\s+(?!(?:questo|questa|quest|il|lo|la|l'|un|una|uno|vostr\w+|nostr\w+)\s*(?:\w+\s+)?"
                      r"(?:hotel|alberg\w*|struttur\w*|alloggi\w*|camer\w*|appartament\w*|casa|villa|b&b|ostell\w*|"
                      r"residence|campegg\w*|agriturism\w*|stabiliment\w*|resort|cottage|chalet|bungalow)\b)|"
                      r"^(?:il|la|lo|l'|le|gli|i)\s*(?:\w+\s+){3,}(?:dispone|offre|accetta|ha|permette|ammette|consente|"
                      r"fornisce)\b", re.I)


def _plain(question):
    """Domanda senza accenti e con l'apostrofo dritto, per i confronti con le espressioni regolari."""
    q = unicodedata.normalize("NFKD", question.replace("’", "'"))
    return "".join(c for c in q if not unicodedata.combining(c))


def service_reject(question, lang):
    """Motivo per cui [question] non riguarda un servizio o una regola di un alloggio, o None se la riguarda: serve una
    parola di servizio forte (STRONG: check-in, colazione, cancellazione...) oppure una debole (WEAK: animali, parcheggio,
    pagamenti...) insieme a un alloggio (LODGING) o a un verbo di regola (POLICY). Sono scartate le domande per cercare dove
    stare o mangiare (DISCOVERY), che le guide trattano."""
    q = _plain(question)
    strong, weak = STRONG[lang].search(q), WEAK[lang].search(q)
    if not strong and not (weak and (LODGING[lang].search(q) or POLICY[lang].search(q))):
        return "nessun servizio"
    if DISCOVERY[lang].search(q):
        return "ricerca di un posto"
    if OTHER_BUSINESS[lang].search(q):
        return "altro settore"
    if lang == "it" and IT_NAMED.search(q):
        return "nome dell'esercizio"
    return None


# ----- filtri di testo: parole chiave delle guide, test esteso, doppioni -----

# Radici di KEYWORDS che restano ammesse quando la domanda riguarda un servizio dell'alloggio: l'alloggio stesso (la sezione
# "dove dormire" resta coperta dalle altre radici e da DISCOVERY), la colazione e i pagamenti con carta
EXEMPT_ROOTS = {"carta", "card", "pay", "breakfast", "colazione"}
SKELETON_MAX = 2  # domande con lo stesso schema, a parte il nome dell'alloggio ("presso questa villa/questo cottage")
ARTICLES = re.compile(r"\b(questo|questa|questi|queste|this|the|your|our|il|lo|la|l|le|un|una|uno|a|an)\b")


def normalize(question):
    """Forma per confrontare domande: minuscolo, senza accenti ne' punteggiatura, spazi singoli."""
    q = unicodedata.normalize("NFKD", question.replace("’", "'").lower())
    q = "".join(c for c in q if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", q).strip()


def skeleton(question, lang):
    """normalize() senza articoli e con ogni nome di alloggio sostituito da "x"."""
    return re.sub(r"\s+", " ", ARTICLES.sub(" ", normalize(LODGING[lang].sub(" x ", question)))).strip()


def _root_regex(roots):
    """Radici a inizio parola (come travel_questions._category); quelle che iniziano con uno spazio restano letterali."""
    parts = [re.escape(r) if r.startswith(" ") else r"(?<![a-zà-ü])" + re.escape(r) for r in roots]
    return re.compile("|".join(parts)) if parts else None


def topic_matcher(keywords, travel_stems, exempt=()):
    """funzione(domanda) -> True se la domanda contiene una radice di [keywords] (dict categoria -> radici) o di
    [travel_stems], tranne quelle di [exempt]."""
    roots = {r for rs in keywords.values() for r in rs if r not in exempt} | {s for s in travel_stems if s not in exempt}
    rx = _root_regex(sorted(roots))
    return lambda q: bool(rx and rx.search(" " + q.replace("’", "'").lower()))


def _flatten(obj):
    """Tutte le stringhe di una struttura annidata di liste, tuple e dizionari (le chiavi non contano)."""
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from _flatten(v)
    elif isinstance(obj, (list, tuple, set)):
        for v in obj:
            yield from _flatten(v)


def eval_questions():
    """Insieme delle domande normalizzate del test esteso (generate_eval_set.py e generate_eval_set_en.py): PARA, PARA_EN,
    PARA_CITY, PARA_WIKI, OFF_TOPIC, NEAR_QUESTIONS, DEP_QUESTIONS, PARA_CITY_EN e PARA e OFF_TOPIC inglesi."""
    sys.path.insert(0, str(HERE))
    import generate_eval_set as it
    import generate_eval_set_en as en
    lists = [it.PARA, it.PARA_EN, it.PARA_CITY, it.PARA_WIKI, it.OFF_TOPIC, it.NEAR_QUESTIONS, it.DEP_QUESTIONS,
             en.PARA, en.PARA_CITY_EN, en.OFF_TOPIC]
    return {normalize(q) for obj in lists for q in _flatten(obj)}


def _guide_filters(lang):
    """(KEYWORDS, TRAVEL_STEMS) del generatore SFT nella lingua `lang`."""
    sys.path.insert(0, str(HERE))
    if lang == "it":
        import generate_sft_dataset as m
    else:
        import generate_sft_dataset_en as m
    return m.KEYWORDS, m.TRAVEL_STEMS


# ----- WebFAQ -----

def _read_cache(path):
    """(righe, statistiche) di una cache TSV: righe separate da tab, prima riga "#<TAB>json" con i conteggi dei filtri."""
    stats, rows = {}, []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.startswith("#\t"):
            stats = json.loads(line[2:])
        elif line:
            rows.append(line.split("\t"))
    return rows, stats


def _write_cache(path, rows, stats):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    text = "#\t" + json.dumps(stats, ensure_ascii=False, sort_keys=True) + "\n"
    tmp.write_text(text + "".join("\t".join(r) + "\n" for r in rows), encoding="utf-8", newline="\n")
    os.replace(tmp, path)


MIN_SITES = 8  # una parola che compare in meno siti diversi e' un nome (anche in minuscolo: "casa mare bodrum") o un refuso


def _tokens(question):
    """Parole minuscole senza accenti; gli apostrofi separano ("l'acquolina" -> "l", "acquolina")."""
    return re.findall(r"[a-z]+", _plain(question).lower())


def common_words(rows):
    """Parole che compaiono in almeno MIN_SITES siti diversi delle righe [(sito, domanda)] (tutte le righe dell'argomento,
    non solo quelle ben scritte: i nomi degli hotel stanno nei titoli di un solo sito)."""
    sites, common = {}, set()
    for site, q in rows:
        host = re.sub(r"^https?://(www\.)?", "", site)
        for w in set(_tokens(q)) - common:
            seen = sites.setdefault(w, set())
            seen.add(host)
            if len(seen) >= MIN_SITES:
                common.add(w)
                del sites[w]
    return common


def build_faq_cache(rows, lang):
    """([(sito, domanda)], statistiche) dalle righe grezze [(sito, domanda)] di WebFAQ: filtri di forma e di servizio, poi
    parole rare (common_words) e doppioni normalizzati (resta la prima). Le statistiche contano le righe che entrano in ogni filtro e i motivi di scarto."""
    stats = Counter(righe=len(rows))
    out, seen, common = [], set(), common_words(rows)
    for site, q in rows:
        why = form_reject(q, lang)
        if why:
            stats["forma: " + why] += 1
            continue
        stats["dopo la forma"] += 1
        why = service_reject(q, lang)
        if why:
            stats["servizio: " + why] += 1
            continue
        stats["dopo il servizio"] += 1
        if not set(_tokens(q)) <= common:
            stats["parola rara (nome o refuso)"] += 1
            continue
        stats["dopo le parole rare"] += 1
        key = normalize(q)
        if key in seen:
            stats["doppione"] += 1
            continue
        seen.add(key)
        out.append((site, q.replace("’", "'")))
    stats["dopo i doppioni"] = len(out)
    return out, dict(stats)


def select_faq(rows, lang, keywords, travel_stems, test, stats=None, seed=42):
    """[domanda] da [(sito, domanda)] senza le domande che una guida potrebbe coprire (radici di [keywords] e
    [travel_stems], salvo EXEMPT_ROOTS e le radici di alloggio quando la domanda e' di servizio), quelle uguali (normalizzate)
    a una del test [test], e con al massimo MAX_PER_SITE domande per sito e SKELETON_MAX per schema. Ordine: mescolato con
    [seed], cosi' un prefisso e' un campione. [stats] (Counter) conta i motivi di scarto."""
    stats = stats if stats is not None else Counter()
    exempt = EXEMPT_ROOTS | set(keywords.get("ALLOGGIO", ()))
    strict, loose = topic_matcher(keywords, travel_stems), topic_matcher(keywords, travel_stems, exempt)
    per_site, per_skeleton, kept = Counter(), Counter(), []
    for site, q in sorted(rows):
        if normalize(q) in test:
            stats["uguale al test"] += 1
        elif (loose if STRONG[lang].search(q) or LODGING[lang].search(q) or POLICY[lang].search(q) else strict)(q):
            stats["parole chiave delle guide"] += 1
        elif per_skeleton[skeleton(q, lang)] >= SKELETON_MAX:
            stats["stesso schema"] += 1
        elif per_site[site] >= MAX_PER_SITE:
            stats["troppe dal sito"] += 1
        else:
            per_site[site] += 1
            per_skeleton[skeleton(q, lang)] += 1
            kept.append(q)
    random.Random(seed).shuffle(kept)
    stats["tenute"] = len(kept)
    return kept


def default_download_dir():
    return Path(tempfile.gettempdir()) / "pocket-travel-sft-downloads"


def load_faq_refusals(raw_dir, lang, download_dir=None, keywords=None, travel_stems=None, test=None, stats=None):
    """Domande di servizio di alloggi e siti turistici (WebFAQ) in `lang` ("it" o "en") per i rifiuti, senza temi delle
    guide e senza domande del test esteso. La cache in [raw_dir] (faq_refusals.<lang>.tsv) tiene il risultato dei filtri di
    forma; se manca, si estraggono le righe da WebFAQ (file grandi in [download_dir], di default una cartella temporanea
    del sistema). [keywords], [travel_stems] e [test] di default vengono dal generatore; [stats] (Counter) riceve i
    conteggi dei filtri, anche quelli della costruzione della cache."""
    cache = Path(raw_dir) / FAQ_CACHE.format(lang=lang)
    if not cache.exists():
        rows = webfaq_rows(lang, download_dir or default_download_dir())
        _write_cache(cache, *build_faq_cache(rows, lang))
    rows, build = _read_cache(cache)
    if keywords is None or travel_stems is None:
        keywords, travel_stems = _guide_filters(lang)
    if stats is not None:
        stats.update({"cache: " + k: v for k, v in build.items()})
    return select_faq([(r[0], r[1]) for r in rows], lang, keywords, travel_stems,
                      eval_questions() if test is None else test, stats)


# ----- MKQA -----

MKQA_LENGTH = (15, 110)
MKQA_STARTERS = {
    "it": {"chi", "che", "qual", "cosa", "quale", "quali", "quando", "dove", "come", "quanto", "quanta", "quanti", "quante",
           "perche", "in", "a", "da", "di", "per", "con", "su", "il", "la", "lo", "gli", "le", "i", "e", "c"},
    "en": {"who", "what", "which", "when", "where", "how", "why", "is", "are", "was", "were", "do", "does", "did", "can",
           "could", "will", "would", "has", "have", "had", "in", "on", "at", "for", "from", "to", "the", "of"},
}
MKQA_INTERROGATIVES = {
    "it": {"chi", "che", "qual", "cosa", "quale", "quali", "quando", "dove", "come", "quanto", "quanta", "quanti", "quante",
           "perche"},
    "en": {"who", "what", "which", "when", "where", "how", "why", "is", "are", "was", "were", "do", "does", "did", "can",
           "could", "will", "would", "has", "have", "had"},
}
MKQA_BAD_CHARS = re.compile(r"[\"()\[\]{}:;@/\\|<>*_=+#]|https?:|www\.")


def mkqa_reject(text, lang):
    """Motivo per cui la query MKQA [text] non va bene come domanda, o None. Le query sono minuscole e senza "?": si
    chiede un interrogativo nelle prime due parole (restano fuori le affermazioni), nessun carattere da elenco o da
    glossa (virgolette, parentesi, due punti: le glosse come "(la brezza estiva...)" sono traduzioni del titolo)."""
    if not text or not MKQA_LENGTH[0] <= len(text) <= MKQA_LENGTH[1]:
        return "lunghezza"
    if MKQA_BAD_CHARS.search(text) or "?" in text or "\n" in text:
        return "caratteri"
    words = [_fold(w) for w in _words(text.replace("’", "'"))]
    if len(words) < 3 or not set(words[:2]) & MKQA_INTERROGATIVES[lang]:
        return "senza interrogativo"
    if words[0] not in MKQA_STARTERS[lang] and words[0].split("'")[0] not in MKQA_STARTERS[lang]:
        return "inizio"
    return None


def mkqa_question(text):
    """Query MKQA come domanda scritta: spazi singoli, maiuscola iniziale e "?" finale."""
    text = re.sub(r"\s+", " ", text.replace("’", "'")).strip()
    return text[:1].upper() + text[1:] + "?"


def _sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def mkqa_file(download_dir):
    """Percorso di mkqa.jsonl.gz in [download_dir]: scaricato dal commit fissato e verificato con MKQA_SHA256 (anche un
    file gia' presente: se non corrisponde, RuntimeError e il file resta com'e')."""
    path = Path(download_dir) / "mkqa.jsonl.gz"
    if not path.exists():
        require_revision(MKQA, MKQA_REVISION)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")

        def download():
            with _open(MKQA_URL) as r, open(tmp, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
        _retry(download, "mkqa")
        if _sha256(tmp) != MKQA_SHA256:
            tmp.unlink()
            raise RuntimeError("mkqa.jsonl.gz scaricato non corrisponde a MKQA_SHA256: controlla il file e aggiorna "
                               "MKQA_URL e MKQA_SHA256")
        os.replace(tmp, path)
    elif _sha256(path) != MKQA_SHA256:
        raise RuntimeError(f"{path} non corrisponde a MKQA_SHA256: cancellarlo per scaricarlo di nuovo")
    return path


def build_mkqa_cache(lines):
    """([(it, en)], statistiche) dalle righe JSON [lines] di MKQA: coppie con entrambe le query che passano mkqa_reject,
    senza doppioni (normalizzati, sul testo inglese)."""
    stats = Counter()
    out, seen = [], set()
    for line in lines:
        q = json.loads(line)["queries"]
        stats["righe"] += 1
        if not q.get("it") or not q.get("en"):
            stats["manca una lingua"] += 1
            continue
        why = mkqa_reject(q["it"], "it") or mkqa_reject(q["en"], "en")
        if why:
            stats["forma: " + why] += 1
            continue
        pair = (mkqa_question(q["it"]), mkqa_question(q["en"]))
        key = normalize(pair[1])
        if key in seen:
            stats["doppione"] += 1
            continue
        seen.add(key)
        out.append(pair)
    stats["dopo i doppioni"] = len(out)
    return out, dict(stats)


def select_mkqa(pairs, keywords_it, stems_it, keywords_en, stems_en, test, stats=None, seed=42):
    """[(it, en)] senza coppie con radici di viaggio o delle guide (italiano con le radici italiane, inglese con le
    inglesi) e senza coppie uguali (normalizzate) a una domanda del test [test]. Ordine mescolato con [seed]."""
    stats = stats if stats is not None else Counter()
    topic_it, topic_en = topic_matcher(keywords_it, stems_it), topic_matcher(keywords_en, stems_en)
    kept = []
    for it, en in sorted(pairs):
        if normalize(it) in test or normalize(en) in test:
            stats["uguale al test"] += 1
        elif topic_it(it) or topic_en(en):
            stats["parole chiave delle guide"] += 1
        else:
            kept.append((it, en))
    random.Random(seed).shuffle(kept)
    stats["tenute"] = len(kept)
    return kept


def load_mkqa_off_topic(raw_dir, download_dir=None, test=None, stats=None):
    """Coppie (italiano, inglese) di domande di cultura generale (MKQA) fuori argomento, senza temi di viaggio e delle
    guide e senza domande del test esteso. La cache e' mkqa_off_topic.tsv in [raw_dir] (il file di 12 MB va in
    [download_dir], di default una cartella temporanea del sistema)."""
    cache = Path(raw_dir) / MKQA_CACHE
    if not cache.exists():
        with gzip.open(mkqa_file(download_dir or default_download_dir()), "rt", encoding="utf-8") as f:
            _write_cache(cache, *build_mkqa_cache(f))
    rows, build = _read_cache(cache)
    if stats is not None:
        stats.update({"cache: " + k: v for k, v in build.items()})
    kw_it, st_it = _guide_filters("it")
    kw_en, st_en = _guide_filters("en")
    return select_mkqa([(r[0], r[1]) for r in rows if len(r) == 2], kw_it, st_it, kw_en, st_en,
                       eval_questions() if test is None else test, stats)


def main(argv):
    raw = Path(argv[1]) if len(argv) > 1 else HERE.parent / "data" / "sft" / "raw"
    download = Path(argv[2]) if len(argv) > 2 else None
    for lang in ("it", "en"):
        stats = Counter()
        qs = load_faq_refusals(raw, lang, download, stats=stats)
        print(f"\nWebFAQ {lang}: {len(qs)} domande")
        for k, v in sorted(stats.items()):
            print(f"  {k}: {v}")
        for q in qs[:15]:
            print("   ", q)
    stats = Counter()
    pairs = load_mkqa_off_topic(raw, download, stats=stats)
    print(f"\nMKQA: {len(pairs)} coppie")
    for k, v in sorted(stats.items()):
        print(f"  {k}: {v}")
    for it, en in pairs[:15]:
        print("   ", it, "|", en)


if __name__ == "__main__":
    main(sys.argv)
