#!/usr/bin/env python3
"""Guide arricchite dall'altra lingua. Oggi solo le guide italiane, con le sezioni del governo canadese (travel.gc.ca)
della guida inglese tradotte (pick_travel_advice): generateGuides (--translated) le aggiunge alla guida italiana. Spento
dopo la revisione a campione (vedi WIKIVOYAGE_SECTIONS): per ogni regione (o citta') e categoria si confronta la sezione
della guida con quella nell'altra lingua (translate_dataset.needs_translation: assente, sotto 300 caratteri o lunga meno
della meta') e, dove l'altra e' molto piu' ricca, la si traduce, italiano dall'inglese o inglese dall'italiano (--from).
Scrive un file di sezioni tradotte (una riga JSON per regione e categoria) che generateGuides / generateCities
(--translated) mettono al posto di quelle povere, segnandole con translated = 1 e con il modello usato in translatedBy nel database.

Uso: translate_guides.py guides|cities <guides.db|cities.db da arricchire> <idem nell'altra lingua> <tradotte.jsonl>
         --cache-dir DIR [--backend marian|llama|file] [--from en|it] [--max-seconds N] [--deadline EPOCH]
         marian: --model-dir DIR
         llama:  --translations FILE --gguf FILE --llama-cpp DIR --device DEV --morphit FILE [--missing FILE] [--parallel N]
         file:   --translations FILE [--missing FILE]
Con --from en (default) il database da arricchire e' quello italiano, con --from it quello inglese.

Motore marian (default): CTranslate2 (int8, CPU) sui modelli Helsinki-NLP/opus-mt-tc-big-en-it e opus-mt-tc-big-it-en
(CC BY 4.0), convertiti da convert-translation-model.sh (--model-dir e' quello della direzione); la divisione in frasi, la
cache delle frasi (un file per direzione in --cache-dir) e lo scarto delle frasi sospette sono quelli di
translate_dataset.py. Il testo tradotto e' un'opera derivata di Wikivoyage (CC BY-SA 4.0): l'app lo dichiara tra le licenze.

Motore llama (sul PC, solo inglese -> italiano): un modello di chat in GGUF (Index-Translate-9B, scelto dalla revisione
cieca del 2026-10-31) su llama-server, sotto il lucchetto di gpu_lock.py, con --parallel blocchi alla volta in una
cache KV comune. Traduce per paragrafo (una riga del corpo, senza
il segno iniziale "▸ " o "• "), a blocchi di righe numerate di BLOCK_CHARS caratteri con regione e categoria nel prompt,
con il registro impersonale chiesto nel prompt; i paragrafi in cui register_check.py (lessico Morph-it) trova ancora il tu
o il voi tornano al modello una alla volta (fix_register). Un paragrafo con numeri diversi dall'originale o di lunghezza
fuori misura non si salva. Ogni paragrafo tradotto va subito in --translations, una riga JSON {"k": sha1("en-it\\n" +
paragrafo), "t": traduzione, "m": modello, "v": versione del prompt}: il file e' la cache dei giri successivi e
l'asset che la CI legge con il motore file. Un file contiene le traduzioni di un solo modello.

Motore file (in CI): nessun modello, solo --translations. Una categoria con un paragrafo assente resta com'e'; i paragrafi
assenti vanno in --missing, una riga JSON {"k", "src"} per paragrafo, da tradurre al giro locale successivo.

Tetto di tempo: --max-seconds per questa chiamata (3600 di default, nessuno con llama) e --deadline (secondi dal 1970) per un tetto comune a piu'
chiamate; passato il primo si smette di tradurre frasi nuove e le sezioni restanti seguono nella run successiva (la
cache in --cache-dir tiene il lavoro fatto). Le sezioni gia' interamente in cache si includono sempre, a prescindere dal
tempo, cosi' una guida gia' tradotta non torna povera a pezzi. Una sezione con una frase sospetta resta com'e'.
Con il motore o il modello mancanti lo script fallisce senza scrivere nulla: il chiamante pubblica la guida originale.

Citta': l'omologo di una pagina e' il titolo di Wikivoyage nell'altra lingua collegato dallo stesso elemento Wikidata
(il titolo e' spesso diverso: Venezia / Venice), in mancanza lo stesso titolo; le pagine che esistono solo nell'altra
lingua non si aggiungono (Londra e London comparirebbero due volte).
"""
import argparse
import hashlib
import json
import os
import re
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import city_population
import translate_dataset as ts
from eval_common import Synced, drop_partial_line
from status import Progress

# Dai fatti rapidi in inglese non si traduce: li costruisce la pipeline dai Fatti rapidi italiani.
SKIP_CATEGORIES = {"FATTI_RAPIDI"}
# Revisione a campione del 2026-10-09 (1786 frasi tradotte dall'inglese, controllate una per una per omissioni,
# aggiunte, numeri e senso): le sezioni di
# Wikivoyage hanno il 21% di frasi con errori critici o gravi (senso sbagliato delle parole ambigue: "Muggings" -> "I
# muggini", "tip" -> "consigliare"), quelle del governo canadese il 5,8%, con testo formale e frasi ripetute da un paese
# all'altro. Si traducono solo queste ultime (guide italiane, aggiunte alle sezioni di Wikivoyage, vedi
# pick_travel_advice); il confronto con le sezioni povere (pick_sections) resta spento, in tutte e due le direzioni e
# per le citta', finche' un motore migliore non passa la stessa revisione.
WIKIVOYAGE_SECTIONS = False
TRAVEL_ADVICE_HOST = "://travel.gc.ca/"
# Titoli delle sezioni del governo canadese (GenerateTravelAdvice.kt): fissi, il modello fuori contesto li sbaglia.
FIXED_TITLES = {
    "Safety and security (Government of Canada)": "Sicurezza (Governo del Canada)",
    "Laws and culture (Government of Canada)": "Leggi e cultura (Governo del Canada)",
    "Natural disasters and climate (Government of Canada)": "Catastrofi naturali e clima (Governo del Canada)",
    "Health (Government of Canada)": "Salute (Governo del Canada)",
}
# Traduzioni corrette a mano delle frasi inglesi piu' frequenti (una riga "inglese<TAB>italiano"): prevalgono sulla cache
# e sul modello.
CORRECTIONS = Path(__file__).resolve().parent.parent / "translation-corrections.en-it.tsv"
CHUNK_CHARS = 40_000  # testo inglese per chiamata al modello: tra un lotto e l'altro si controlla il tempo
# Sezioni che in realta' vengono dalla pagina nell'altra lingua (generateGuides usa quella inglese quando la pagina
# italiana non ha sezioni, es. la Siberia) equivalgono a una sezione assente: group() le salta per il prefisso dell'url.


class Ct2Translator(ts.Translator):
    """translate_dataset.Translator con CTranslate2 al posto di torch: stessa cache e stessi controlli."""

    def __init__(self, src, tgt, cache_dir, model_dir):
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        super().__init__(src, tgt, cache_dir, batch=64)
        self.model_dir = Path(model_dir)

    def _load(self):
        import ctranslate2
        import sentencepiece
        self.tokenizer = sentencepiece.SentencePieceProcessor(model_file=str(self.model_dir / "source.spm"))
        self.target = sentencepiece.SentencePieceProcessor(model_file=str(self.model_dir / "target.spm"))
        self.model = ctranslate2.Translator(str(self.model_dir), device="cpu", compute_type="int8",
                                            intra_threads=os.cpu_count() or 4)
        print(f"[traduzione] {self.model_dir.name} su cpu (CTranslate2 int8)", file=sys.stderr)

    def _decode(self, batch):
        results = self.model.translate_batch([self.tokenizer.encode(s, out_type=str) + ["</s>"] for s in batch],
                                             beam_size=4, max_decoding_length=512, max_input_length=512)
        return [self.target.decode(r.hypotheses[0]) for r in results]


# Motore llama. Prompt della prova del 2026-10-31 (revisione cieca: 0,3% di frasi con un difetto critical o major
# su 190 paragrafi di Wikivoyage e del Canada), nel messaggio utente: il template del modello non ha il turno system.
LLAMA_PROMPT_VERSION = 1
BLOCK_CHARS = 1500
LLAMA_PROMPT = (
    "Sei un traduttore professionista di guide di viaggio dall'inglese all'italiano. Traduci in italiano naturale e "
    "corretto, come in una guida pubblicata, il senso giusto per un viaggiatore. Regole: non aggiungere e non togliere "
    "nulla; numeri, prezzi, orari, date e unita' identici; non tradurre nomi propri di luoghi, enti, piatti, giornali, "
    "parole in lingua locale citate o tra virgolette, ma usa il nome italiano corrente dei luoghi famosi (es. Mosca); "
    "conserva i caratteri non latini e gli accenti; mantieni i segni '▸ ' (sottotitolo) e '• ' (voce di elenco) a inizio riga. "
    "Ricevi righe numerate [n]: rispondi solo con le stesse righe numerate [n] tradotte, una per riga, nessun commento. "
    "Registro impersonale, come in una guida pubblicata: non rivolgerti al lettore con tu, voi o Lei; usa l'infinito "
    "o il si impersonale per consigli e avvertenze.")
NUMBERED = re.compile(r"^\[(\d+)\]\s?(.*)$")
FIX_PROMPT = (
    "La traduzione qui sotto si rivolge al lettore con tu o voi, ma la guida usa il registro impersonale. "
    "Riscrivi la traduzione dell'inglese con l'infinito (\"Evitare...\", \"Portare...\") o con il si impersonale "
    "(\"Si consiglia di...\", \"se si viaggia\"), mai tu, voi o Lei, senza cambiare il senso, i numeri o i nomi. "
    "Rispondi solo con il testo italiano corretto, conservando i segni iniziali ▸ e •.")
FIX_TRIES = ((0, 0), (0.7, 1), (0.7, 2), (0.9, 3))  # (temperatura, seme)
DIGITS = re.compile(r"\d+")
# parole inglesi che in una riscrittura indicano una frase lasciata in inglese, e prima persona ("consigliamo")
ENGLISH_WORDS = re.compile(r"\b(the|your|you|and|with|keep|do|not|of|to|at|in case|only|always|never|before|after)\b", re.I)
FIRST_PERSON = re.compile(r"\b(consigliamo|raccomandiamo|suggeriamo|invitiamo|ricordiamo|noi|nostr[oaie])\b", re.I)


def paragraph_key(text, src="en", tgt="it"):
    return hashlib.sha1(f"{src}-{tgt}\n{text}".encode("utf-8")).hexdigest()


def split_line(line):
    """(segno iniziale "▸ ", "• " o "", testo) di una riga del corpo."""
    marker = next((m for m in ts.MARKERS if line.startswith(m)), "")
    return marker, line[len(marker):].strip()


def paragraphs(rows):
    """Testi da tradurre di [rows] (titolo, corpo, sourceUrl), senza doppioni: titoli fuori da FIXED_TITLES, righe dei corpi."""
    texts = [t for t, _, _ in rows if t not in FIXED_TITLES]
    texts += [text for _, body, _ in rows for text in (split_line(line)[1] for line in body.split("\n")) if text]
    return list(dict.fromkeys(texts))


class TranslationStore:
    """Il file --translations: {chiave del paragrafo: traduzione} di un solo modello; add() scrive subito la riga, cosi'
    un'interruzione, anche un blocco del PC, non perde il lavoro fatto (Synced, chiuso a ogni riga)."""

    def __init__(self, path, model=None):
        self.path, self.model, self.done = Path(path), model, {}
        if not self.path.exists():
            return
        drop_partial_line(self.path)
        for line in self.path.read_text(encoding="utf-8").splitlines():
            try:
                d = json.loads(line)
                key, text, row_model = d["k"], d["t"], d["m"]
            except (ValueError, KeyError, TypeError):
                continue  # riga vuota o troncata da un'interruzione
            if self.model and row_model != self.model:
                sys.exit(f"{self.path.name}: traduzioni di {row_model}, non di {self.model}: un file per modello")
            self.model = row_model
            self.done[key] = text

    def get(self, text):
        return self.done.get(paragraph_key(text))

    def add(self, text, translation):
        key = paragraph_key(text)
        self.done[key] = translation
        drop_partial_line(self.path)
        with Synced(self.path) as f:
            f.write(json.dumps({"k": key, "t": translation, "m": self.model, "v": LLAMA_PROMPT_VERSION},
                               ensure_ascii=False) + "\n")


def overlay_from_store(picks, store):
    """(traduzioni come _translate_chunk, {chiave: paragrafo assente}): una categoria con un paragrafo assente resta
    com'e', cosi' una guida non mescola due lingue."""
    translated, missing = {}, {}
    for owner, category, rows in picks:
        absent = [t for t in paragraphs(rows) if store.get(t) is None]
        if absent:
            missing.update((paragraph_key(t), t) for t in absent)
            continue
        taken, out = set(), []
        for title, body, url in rows:
            new_title = FIXED_TITLES.get(title) or store.get(title)
            if new_title.lower() in taken:  # due titoli uguali nella stessa categoria: stessa chiave di lista nell'app
                new_title = title
            taken.add(new_title.lower())
            lines = []
            for line in body.split("\n"):
                marker, text = split_line(line)
                lines.append(marker + store.get(text) if text else line)
            out.append((new_title, "\n".join(lines), url))
        translated[(owner, category)] = out
    return translated, missing


def from_corrections(text, corrections):
    """Traduzione di [text] dalle frasi corrette a mano ({inglese: italiano}), se le contengono tutte; altrimenti None."""
    sentences = [s for s in ts.SENTENCE_SPLIT.split(text) if s.strip()]
    if sentences and all(s in corrections for s in sentences):
        return " ".join(corrections[s] for s in sentences)
    return None


def blocks(texts, size=BLOCK_CHARS):
    """[texts] in gruppi consecutivi fino a [size] caratteri (un testo piu' lungo sta da solo)."""
    out, current, length = [], [], 0
    for t in texts:
        if current and length + len(t) > size:
            out.append(current)
            current, length = [], 0
        current.append(t)
        length += len(t)
    return out + ([current] if current else [])


def translate_block(chat, head, texts):
    """Traduzioni di [texts] (stesso ordine) in una richiesta a righe numerate; una riga mancante o sospetta (numeri
    diversi, lunghezza fuori misura) si ritraduce da sola, poi None se resta sospetta."""
    got = {}
    for line in chat(f"{head}\n\n" + "\n".join(f"[{i}] {t}" for i, t in enumerate(texts, 1))).splitlines():
        if m := NUMBERED.match(line.strip()):
            got[int(m.group(1))] = m.group(2).strip()
    out = []
    for i, text in enumerate(texts, 1):
        result = got.get(i)
        if not result or not ts._plausible(text, result):
            result = NUMBERED.sub(r"\2", chat(f"{head}\n\n[1] {text}").strip()).strip()
        result = split_line(result)[1]  # il segno iniziale lo rimette overlay_from_store dalla riga inglese
        out.append(result if result and "\n" not in result and ts._plausible(text, result) else None)
    return out


def register_ok(new, old, second_person):
    """Riscrittura accettabile: niente seconda ne' prima persona, stessi numeri, una riga, nessuna parola inglese in piu',
    almeno il 70% della lunghezza (il modello a volte toglie frasi intere)."""
    return (len(new) >= 0.7 * len(old) and "\n" not in new and not second_person(new) and not FIRST_PERSON.search(new)
            and sorted(DIGITS.findall(new)) == sorted(DIGITS.findall(old))
            and len(ENGLISH_WORDS.findall(new)) <= len(ENGLISH_WORDS.findall(old)))


def fix_register(chat, english, italian, second_person):
    """(traduzione, esito): [italian] se non si rivolge al lettore (esito None), altrimenti la prima riscrittura del
    modello che passa register_ok (True) o [italian] com'era (False)."""
    if not second_person(italian):
        return italian, None
    for temperature, seed in FIX_TRIES:
        raw = NUMBERED.sub(r"\2", chat(f"{FIX_PROMPT}\n\nInglese: {english}\nTraduzione da correggere: {italian}",
                                       temperature, seed).strip()).strip()
        # a volte il modello da' piu' versioni, una per riga: vale ognuna
        for candidate in [raw] + raw.splitlines():
            candidate = split_line(candidate.strip())[1]
            if register_ok(candidate, italian, second_person):
                return candidate, True
    return italian, False


def start_llama_server(gguf, llama_cpp, device, log_path, parallel=1):
    """llama-server su una porta libera di localhost, tutto sulla GPU [device], con [parallel] richieste insieme in una
    cache KV comune da 4096 token ciascuna, stderr in [log_path]; (processo, url) a modello carico."""
    import vram
    from eval_common import find_llama_bin, llama_env
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    exe = find_llama_bin("llama-server", llama_cpp)
    vram.check_llama(exe, llama_env(exe), device, vram.DEFAULT_MAX_HELD_PCT)
    cmd = [exe, "-m", str(gguf), "--host", "127.0.0.1", "--port", str(port), "-c", str(4096 * parallel),
           "-np", str(parallel), "--kv-unified", "-fa", "on", "-b", "2048", "-ub", "512", "--load-mode", "none", "--jinja",
           "--no-webui", "-ngl", "99", "--device", device]
    # stderr in un file: se il server esce subito (un'opzione che la build non conosce) il motivo si stampa, se cade a
    # meta' giro resta da leggere
    log = open(log_path, "w+b")
    server = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=log, env=llama_env(exe))
    url = f"http://127.0.0.1:{port}"
    for _ in range(600):
        if server.poll() is not None:
            log.seek(0)
            tail = log.read().decode("utf-8", "replace")[-2000:]
            log.close()
            sys.exit(f"llama-server uscito con codice {server.returncode}: {' '.join(cmd)}\n{tail}")
        try:
            with urllib.request.urlopen(f"{url}/health", timeout=5) as r:
                if r.status == 200:
                    log.close()  # il server tiene il suo handle e continua a scriverci
                    return server, url
        except OSError:
            pass  # porta non ancora aperta o modello in caricamento (503)
        time.sleep(0.5)
    server.kill()
    log.close()
    sys.exit("llama-server non pronto dopo 5 minuti")


def llama_chat(url):
    """chat(testo, temperatura=0, seme=0) -> risposta del modello, con LLAMA_PROMPT davanti al testo."""
    def chat(text, temperature=0, seed=0):
        body = {"messages": [{"role": "user", "content": f"{LLAMA_PROMPT}\n\n{text}"}], "temperature": temperature,
                "seed": seed, "max_tokens": 2048, "chat_template_kwargs": {"enable_thinking": False}}
        request = urllib.request.Request(f"{url}/v1/chat/completions", json.dumps(body).encode("utf-8"),
                                         {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=600) as r:
            return json.load(r)["choices"][0]["message"]["content"]
    return chat


def translate_paragraphs(picks, store, chat, corrections, second_person, deadline, parallel=1):
    """Traduce in [store] i paragrafi di [picks] che mancano, fino a [deadline], [parallel] blocchi alla volta; stampa
    i conteggi."""
    counts = dict.fromkeys(("modello", "correzioni", "sospetti", "errori", "registro corretto", "registro rimasto"), 0)
    started = time.time()
    jobs, queued = [], set()
    for owner, category, rows in picks:
        todo = []
        for text in paragraphs(rows):
            if store.get(text) is not None or text in queued:
                continue
            queued.add(text)
            if (fixed := from_corrections(text, corrections)) is not None:
                store.add(text, fixed)
                counts["correzioni"] += 1
            else:
                todo.append(text)
        jobs += [(f"Guida: {owner}, sezione {category}.", block) for block in blocks(todo)]

    def work(job):
        head, block = job
        if time.time() >= deadline:
            return []
        return [(text, result) if result is None else (text, *fix_register(chat, text, result, second_person))
                for text, result in zip(block, translate_block(chat, head, block))]

    # come nei training: velocita' e tempo che manca ogni minuto, accanto alla riga [hw] di hw_monitor.py
    progress, finished = Progress("traduzione", sum(len(block) for _, block in jobs), "paragrafo", every=60), 0
    # i thread aspettano solo llama-server; store.add resta qui. Un blocco fallito (server caduto, risposta non JSON)
    # non ferma gli altri, e a un'interruzione i blocchi in coda non partono
    pool = ThreadPoolExecutor(parallel)
    try:
        futures = {pool.submit(work, job): job for job in jobs}
        for future in as_completed(futures):
            try:
                done = future.result()
            except (OSError, ValueError) as e:
                counts["errori"] += 1
                print(f"blocco non tradotto ({futures[future][0]}): {e}", file=sys.stderr)
                continue
            finished += len(done)
            if done:
                progress.update(finished)
            for text, result, *register in done:
                if result is None:
                    counts["sospetti"] += 1
                    continue
                if register[0] is not None:
                    counts["registro corretto" if register[0] else "registro rimasto"] += 1
                store.add(text, result)
                counts["modello"] += 1
    finally:
        pool.shutdown(cancel_futures=True)
    minutes = (time.time() - started) / 60
    print(f"paragrafi tradotti in {minutes:.1f} min: " + ", ".join(f"{k} {v}" for k, v in counts.items()), file=sys.stderr)
    return counts


def write_missing(path, missing):
    with open(path, "w", encoding="utf-8") as f:
        for key, text in sorted(missing.items()):
            f.write(json.dumps({"k": key, "src": text}, ensure_ascii=False) + "\n")


def open_readonly(db):
    """Il database in sola lettura: sqlite3.connect su un percorso che manca ne creerebbe uno vuoto."""
    db = Path(db)
    if not db.is_file():
        sys.exit(f"database assente: {db}")
    return sqlite3.connect(f"{db.resolve().as_uri()}?mode=ro", uri=True)


def read_rows(db, table, owner_column):
    """[(proprietario, categoria, titolo, corpo, sourceUrl)] di una tabella di sezioni; [] se la tabella manca."""
    con = open_readonly(db)
    try:
        return con.execute(f"SELECT {owner_column}, category, title, body, sourceUrl FROM {table} ORDER BY rowid").fetchall()
    except sqlite3.OperationalError:
        return []
    finally:
        con.close()


def group(rows, drop_url_prefix=None):
    """{(proprietario, categoria): [(titolo, corpo, sourceUrl)]}, nell'ordine delle righe."""
    grouped = {}
    for owner, category, title, body, url in rows:
        if drop_url_prefix and url.startswith(drop_url_prefix):
            continue
        grouped.setdefault((owner, category), []).append((title, body, url))
    return grouped


def pick_sections(own_sections, source, source_owner):
    """[(proprietario, categoria, righe d'origine)] da tradurre. [own_sections] e [source]: group(), della guida da
    arricchire e di quella nell'altra lingua; [source_owner]: {proprietario: omologo nell'altra lingua}; vale il
    confronto per categoria (testo di tutte le sue sezioni)."""
    by_owner = {}
    for (owner, category), rows in source.items():
        by_owner.setdefault(owner, {})[category] = rows
    picks = []
    for owner, other in sorted(source_owner.items()):
        for category, rows in sorted(by_owner.get(other, {}).items()):
            if category in SKIP_CATEGORIES:
                continue
            own = "\n".join(body for _, body, _ in own_sections.get((owner, category), []))
            if ts.needs_translation(own, "\n".join(body for _, body, _ in rows)):
                picks.append((owner, category, rows))
    return picks


def pick_travel_advice(source, source_owner):
    """[(proprietario, categoria, righe d'origine)]: le sezioni di travel.gc.ca di [source] (group()) per i proprietari di
    [source_owner], tutte, a prescindere da cosa c'e' nella guida da arricchire: non la sostituiscono, si aggiungono.
    Fuori le sezioni per una sola nazionalita' (link con "#for-nationality=": ingresso e uffici consolari per i canadesi):
    requisiti d'ingresso, indirizzi e numeri non passano dalla traduzione automatica e restano nella guida inglese."""
    picks = []
    for owner, other in sorted(source_owner.items()):
        for (o, category), rows in sorted(source.items()):
            advice = [r for r in rows if TRAVEL_ADVICE_HOST in r[2] and "#for-nationality=" not in r[2]]
            if o == other and advice:
                picks.append((owner, category, advice))
    return picks


def read_corrections(path=CORRECTIONS):
    """{inglese: italiano} dalle righe "inglese<TAB>italiano" di [path] (# per i commenti); {} se il file manca."""
    if not path.exists():
        return {}
    corrections = {}
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = [f.strip() for f in line.split("\t")]
        if len(fields) != 2 or not all(fields):
            raise ValueError(f"{path.name}, riga {number}: serve \"inglese<TAB>italiano\"")
        corrections[fields[0]] = fields[1]
    return corrections


def load_corrections(translator, path=CORRECTIONS):
    """Mette nella cache di [translator] le traduzioni di [path] (read_corrections), senza controllo di plausibilita':
    i numeri possono essere scritti in altro modo ("8AM-10PM" -> "dalle 8 alle 22")."""
    for en, it in read_corrections(path).items():
        key = translator._key(en)
        translator.cache[key] = it
        translator.trusted.add(key)


def _all_cached(translator, texts):
    return all(translator._key(s) in translator.cache for t in texts for _, ss in translator._pieces(t) for s in ss)


def _translate_chunk(translator, picks):
    """{(proprietario, categoria): [(titolo, corpo, sourceUrl)] tradotti}; salta le sezioni con una frase sospetta."""
    flat = [(p, row) for p in picks for row in p[2]]
    outs = translator.translate_many([row[1] for _, row in flat] + [row[0] for _, row in flat])
    bodies, titles = outs[:len(flat)], outs[len(flat):]
    translated, failed, position = {}, set(), 0
    for p in picks:
        key, taken = (p[0], p[1]), set()
        for title, body, url in p[2]:
            new_body, new_title = bodies[position], titles[position]
            position += 1
            if new_body is None:
                failed.add(key)
                continue
            new_title = FIXED_TITLES.get(title, new_title)
            # due titoli uguali nella stessa categoria darebbero la stessa chiave di lista nell'app
            if new_title is None or new_title.lower() in taken:
                new_title = title
            taken.add(new_title.lower())
            translated.setdefault(key, []).append((new_title, new_body, url))
    # una categoria tradotta solo in parte mescolerebbe due lingue: si scarta intera
    return {k: v for k, v in translated.items() if k not in failed}, len(failed)


def translate_picks(picks, translator, deadline, priority=None):
    """Traduce [picks] (vedi pick_sections) fino a [deadline] (secondi dal 1970): prima quelle gia' in cache, poi le
    altre in ordine di [priority] ({proprietario: peso}, i piu' pesanti per primi). Ritorna (traduzioni, rimandate)."""
    priority = priority or {}
    picks = sorted(picks, key=lambda p: (-priority.get(p[0], 0), p[0], p[1]))
    ready = [p for p in picks if _all_cached(translator, [r[1] for r in p[2]] + [r[0] for r in p[2]])]
    pending = [p for p in picks if p not in ready]
    result, failed = {}, 0
    if ready:
        done, bad = _translate_chunk(translator, ready)
        result.update(done)
        failed += bad
    while pending and time.time() < deadline:
        chunk, size = [], 0
        while pending and size < CHUNK_CHARS:
            chunk.append(pending.pop(0))
            size += sum(len(r[1]) for r in chunk[-1][2])
        done, bad = _translate_chunk(translator, chunk)
        result.update(done)
        failed += bad
    print(f"traduzioni: {len(picks)} categorie da tradurre, {len(result)} tradotte (di cui {len(ready)} gia' in cache), "
          f"{failed} scartate per frasi sospette, {len(pending)} rimandate per il tempo", file=sys.stderr)
    return result, pending


def other_titles(own_titles, other_titles, own_lang, other_lang, cache_dir):
    """{titolo nella lingua [own_lang]: titolo in [other_lang]} delle citta': sitelink di Wikivoyage nell'altra lingua
    dell'elemento Wikidata della pagina, altrimenti lo stesso titolo se c'e' tra [other_titles]. I collegamenti trovati
    stanno in --cache-dir."""
    cache_path = Path(cache_dir) / f"city-titles.{own_lang}-{other_lang}.jsonl"
    known = {}
    if cache_path.exists():
        for line in cache_path.read_text(encoding="utf-8").splitlines():
            if line:
                d = json.loads(line)
                known[d["own"]] = d["other"]
    todo = sorted(t for t in own_titles if t not in known)
    found = {}
    try:
        items = city_population.wikibase_items(todo, own_lang)
        ids = sorted(set(items.values()))
        title_of_item = {}
        for i in range(0, len(ids), city_population.BATCH):
            url = ("https://www.wikidata.org/w/api.php?action=wbgetentities&props=sitelinks"
                   f"&sitefilter={other_lang}wikivoyage&format=json&ids="
                   + urllib.parse.quote("|".join(ids[i:i + city_population.BATCH]), safe="|"))
            for q, entity in city_population._get(url).get("entities", {}).items():
                if link := entity.get("sitelinks", {}).get(f"{other_lang}wikivoyage"):
                    title_of_item[q] = link["title"]
            time.sleep(0.2)
        found = {t: title_of_item[q] for t, q in items.items() if q in title_of_item}
    except Exception as e:  # rete o Wikidata: si ripiega sul titolo uguale
        print(f"-- titoli delle citta' nell'altra lingua da Wikidata non disponibili ({e})", file=sys.stderr)
    if found:
        with open(cache_path, "a", encoding="utf-8") as f:
            for t, other in sorted(found.items()):
                f.write(json.dumps({"own": t, "other": other}, ensure_ascii=False) + "\n")
    known.update(found)
    return {t: known.get(t, t) for t in own_titles if known.get(t, t) in other_titles}


def write_overlay(path, translated, model):
    """Scrive le sezioni tradotte, ognuna con il [model] che le ha tradotte (id del modello): generateGuides e
    generateCities lo conservano in translatedBy, e il generatore del dataset SFT tiene solo quelle di un modello con una licenza verificata."""
    with open(path, "w", encoding="utf-8") as f:
        for (owner, category), rows in sorted(translated.items()):
            f.write(json.dumps({"owner": owner, "category": category, "model": model,
                                "sections": [{"title": t, "body": b, "sourceUrl": u} for t, b, u in rows]},
                               ensure_ascii=False) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("kind", choices=["guides", "cities"])
    ap.add_argument("own_db")
    ap.add_argument("source_db")
    ap.add_argument("overlay")
    ap.add_argument("--cache-dir", required=True)
    ap.add_argument("--backend", choices=["marian", "llama", "file"], default="marian")
    ap.add_argument("--model-dir", help="marian: modello CTranslate2 della direzione")
    ap.add_argument("--translations", type=Path, help="llama, file: traduzioni per paragrafo (jsonl)")
    ap.add_argument("--missing", type=Path, help="llama, file: dove scrivere i paragrafi senza traduzione")
    ap.add_argument("--gguf", type=Path, help="llama: modello di traduzione")
    ap.add_argument("--llama-cpp", type=Path, help="llama: cartella dei binari di llama.cpp (get_llama_tools.py)")
    ap.add_argument("--device", help="llama: GPU discreta, come in `llama-server --list-devices` (es. Vulkan0)")
    ap.add_argument("--morphit", type=Path, help="llama: lessico Morph-it m.utf8 (register_check.py)")
    ap.add_argument("--from", dest="source_lang", choices=["en", "it"], default="en")
    ap.add_argument("--parallel", type=int, default=1, help="llama: blocchi tradotti insieme")
    ap.add_argument("--max-seconds", type=float, help="default 3600; con llama nessun tetto (giro sul PC)")
    ap.add_argument("--deadline", type=float, default=float("inf"))
    args = ap.parse_args(argv)
    if args.max_seconds is None:
        args.max_seconds = float("inf") if args.backend == "llama" else 3600
    needed = {"marian": ["model_dir"], "file": ["translations"],
              "llama": ["translations", "gguf", "llama_cpp", "device", "morphit"]}[args.backend]
    if absent := [n for n in needed if getattr(args, n) is None]:
        ap.error(f"--backend {args.backend} richiede " + ", ".join("--" + n.replace("_", "-") for n in absent))
    if args.backend != "marian" and (args.kind, args.source_lang) != ("guides", "en"):
        ap.error(f"--backend {args.backend}: per ora solo guide dall'inglese")

    source_lang = args.source_lang
    own_lang = "it" if source_lang == "en" else "en"
    table, owner_column = ("guide_sections", "regionId") if args.kind == "guides" else ("city_sections", "city")
    own_rows, source_rows = read_rows(args.own_db, table, owner_column), read_rows(args.source_db, table, owner_column)
    if args.kind == "guides":  # le sezioni di Wikipedia delle guide vengono dalla voce nella lingua della guida, mai tradotte
        source_rows = [r for r in source_rows if ".wikipedia.org/" not in r[4]]
    # sezioni il cui url e' nella lingua sbagliata (la pagina inglese usata per la guida italiana senza sezioni) non valgono
    source = group(source_rows, drop_url_prefix=f"https://{own_lang}.")
    owners = {r[0] for r in own_rows}
    source_owners = {k[0] for k in source}
    priority = None
    if args.kind == "guides":
        source_owner = {o: o for o in owners if o in source_owners}
    else:
        source_owner = other_titles(owners, source_owners, own_lang, source_lang, args.cache_dir)
        con = open_readonly(args.own_db)
        try:
            priority = dict(con.execute("SELECT city, MAX(population) FROM city_sections WHERE population IS NOT NULL GROUP BY city"))
        except sqlite3.OperationalError:  # cities.db senza la colonna population
            pass
        finally:
            con.close()
    picks = pick_travel_advice(source, source_owner) if (args.kind, source_lang) == ("guides", "en") else []
    if WIKIVOYAGE_SECTIONS:
        picks += pick_sections(group(own_rows, drop_url_prefix=f"https://{source_lang}."), source, source_owner)
    if not picks:
        print(f"traduzioni {source_lang}->{own_lang} ({args.kind}): niente da tradurre", file=sys.stderr)
        write_overlay(args.overlay, {}, ts.MODELS[(source_lang, own_lang)])
        return 0

    deadline = min(time.time() + args.max_seconds, args.deadline)
    if args.backend != "marian":
        store = TranslationStore(args.translations, args.gguf.stem.lower().replace(".", "-") if args.gguf else None)
        if args.backend == "llama":
            import gpu_lock
            import register_check
            register_check.load(args.morphit)
            gpu_lock.gpu_job("translate_guides.py")
            server_log = args.translations.with_suffix(".server.log")
            server, url = start_llama_server(args.gguf, args.llama_cpp, args.device, server_log, args.parallel)
            # l'attesa del lucchetto e il caricamento del modello non consumano il tempo della traduzione
            deadline = min(time.time() + args.max_seconds, args.deadline)
            try:
                counts = translate_paragraphs(picks, store, llama_chat(url), read_corrections(),
                                              lambda t: bool(register_check.hits(t)), deadline, args.parallel)
                if counts["errori"]:
                    print(f"blocchi non tradotti: log di llama-server in {server_log}", file=sys.stderr)
            finally:
                server.terminate()
                server.wait()
        translated, missing = overlay_from_store(picks, store)
        print(f"traduzioni: {len(picks)} categorie da tradurre, {len(translated)} tradotte, {len(missing)} paragrafi "
              "senza traduzione", file=sys.stderr)
        write_overlay(args.overlay, translated, store.model)
        if args.missing:
            write_missing(args.missing, missing)
        return 0

    translator = Ct2Translator(source_lang, own_lang, args.cache_dir, args.model_dir)
    if source_lang == "en":
        load_corrections(translator)
    before = len(translator.cache)
    translated, _ = translate_picks(picks, translator, deadline, priority)
    write_overlay(args.overlay, translated, ts.MODELS[(source_lang, own_lang)])
    marker = os.environ.get("TRANSLATE_GROWN_MARKER")
    if marker and len(translator.cache) > before:
        Path(marker).touch()
    return 0


if __name__ == "__main__":
    sys.exit(main())
