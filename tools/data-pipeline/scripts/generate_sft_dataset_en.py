#!/usr/bin/env python3
"""Genera il dataset SFT INGLESE (prompt inglese di PromptTemplates.onDevicePrompt, language = "en") dai dump di
Wikivoyage EN: stesso metodo, stessi limiti e stesso formato di generate_sft_dataset.py, di cui usa le funzioni
(pulizia delle sezioni con il cleaner dell'app, contesto, scelta delle frasi, domande fuori tema). Solo le tabelle
sono qui: domande, parole chiave, rifiuto e testo di fallback in inglese.

- positivi: pagine EN dei paesi (EN_HEADING_TO_CATEGORY) e delle citta' (pagine con {{usablecity}}, {{guidecity}},
  {{starcity}} o {{outlinecity}}), risposta = frasi della sezione (estrattivo);
- negativi: categoria assente dal contesto, domanda fuori tema, contesto di fallback dell'app
  ("No information available for this region."): risposta "The context does not contain information ...";
- con --guides-db (guides-en.db pubblicato): domande sui fatti rapidi (sezione FATTI_RAPIDI, righe "Electricity: ...",
  "Time zone: ...") e note personali inventate, nel contesto come "Personal note: <titolo>\\n<testo>".
Regioni di test (TEST_REGIONS) come nel dataset italiano: le loro righe restano nel file (train_lora.py le tiene
fuori dal training, il test base le usa); fuori invece le citta' e le regioni la cui pagina EN e' quella di una
regione di test o vi appartiene risalendo {{IsPartOf}} (es. figi-occidentali ha la stessa pagina "Fiji" di figi-lau).
Domande fuori tema anche da truthfulqa/truthful_qa (Apache 2.0) e yahma/alpaca-cleaned (CC BY 4.0): solo le domande,
la risposta e' sempre il rifiuto.

Uso: python generate_sft_dataset_en.py --dump-dir <cartella dei dump> [--dump-date AAAAMMGG] [--cities 1500]
     [--guides-db <guides-en.db>] [--seed 42]
Titoli delle pagine EN da sft-sources.tsv (come generate_sft_dataset.py --dump-dir). Output in data/sft/:
pocket_travel_sft.en.jsonl + ATTRIBUTION.en.tsv (il nome che train_lora.py --dataset pocket_travel_sft.en.jsonl
si aspetta); cache delle domande fuori tema in raw/offtopic_<fonte>.txt.
"""
import argparse
import importlib
import json
import random
import re
import urllib.parse
from collections import Counter
from pathlib import Path

import wiki_dump
from eval_common import TEST_REGIONS
from generate_sft_dataset import (CITY_MAX_QUESTIONS, CITY_MIN_SECTION, CITY_QUESTIONS_EN, DUMP_FILES,
                                  EN_HEADING_TO_CATEGORY, MAX_CONTEXT, OFF_TOPIC_MAX_USES, OFF_TOPIC_TRAIN_EN, OUT,
                                  QUESTIONS_EN, SOURCE_URL, covers, fetch_off_topic, load_quick_facts, load_regions,
                                  load_sources, make_context, parse_sections, pick_answer)
from status import Progress, phase

cities_en = importlib.import_module("extract-cities-dump-en")  # IS_PART_OF, CITY_STATUS, regions_of

# Copia di PromptTemplates.onDevicePrompt con language = "en" (trimIndent)
def on_device_prompt(context, question):
    return ("You are an offline travel guide.\n"
            "Answer in at most 3 sentences, in English, using only the information in the CONTEXT.\n"
            "If the context is not enough, say so explicitly.\n\n"
            f"CONTEXT: {context}\n\nQUESTION: {question}")

# PromptTemplates.emptyContext("en")
FALLBACK_CONTEXT = "No information available for this region."
REFUSAL = "The context does not contain information"  # inizio fisso del rifiuto: e' il criterio del test

# Altre formulazioni oltre a QUESTIONS_EN. Disgiunte da PARA_EN di generate_eval_set_en.py (test di generalizzazione).
QUESTIONS_EN_EXTRA = {
    "USI_COSTUMI": ["What should I avoid doing in {r}?", "What are good manners in {r}?", "What traditions should I know about in {r}?",
                    "What is considered rude in {r}?", "Are there unwritten rules in {r}?", "What cultural differences should I be aware of in {r}?"],
    "DOGANE": ["How can I travel to {r}?", "What documents do I need for {r}?", "Which routes lead to {r}?",
               "What are the main entry points to {r}?", "What should I know about getting to {r}?", "How do I arrive in {r} by land or sea?"],
    "SALUTE": ["What are the health risks in {r}?", "How is the medical care in {r}?", "Health tips for {r}?",
               "Are there diseases to worry about in {r}?"],
    "SICUREZZA": ["What dangers are there in {r}?", "Is there a lot of crime in {r}?", "Safety tips for {r}?",
                  "Are there areas to avoid in {r}?", "Is it dangerous to walk around in {r}?", "Should I worry about theft in {r}?"],
    "TRASPORTI": ["What is the best way to travel around {r}?", "Should I rent a car in {r}?", "Is public transport good in {r}?",
                  "How do I move between cities in {r}?"],
    "CIBO_BEVANDE": ["What are the typical dishes of {r}?", "What should I drink in {r}?", "Any tips on food and restaurants in {r}?",
                     "What is the local cuisine of {r}?"],
    "ACQUISTI": ["Do I need to exchange money for {r}?", "Are there ATMs in {r}?", "Is {r} expensive?",
                 "What should I know about money in {r}?"],
    "CONNETTIVITA": ["Do I need a local SIM card in {r}?", "How does wifi work in {r}?", "How do I get online in {r}?",
                     "Is it easy to make phone calls in {r}?"],
    "VITA_QUOTIDIANA": ["Which websites or newspapers should I check for {r}?", "Where do I find up-to-date news about {r}?",
                        "Are there local newspapers or radio stations in {r}?"],
}
QUESTIONS = {c: QUESTIONS_EN[c] + QUESTIONS_EN_EXTRA[c] for c in QUESTIONS_EN}

# Sezioni delle pagine EN delle citta' (stesse categorie di CITY_HEADING_TO_CATEGORY del dataset italiano)
CITY_HEADING_TO_CATEGORY = {
    "get in": "ARRIVARE", "get around": "TRASPORTI", "see": "COSA_VEDERE", "eat": "CIBO_BEVANDE",
    "sleep": "ALLOGGIO", "stay safe": "SICUREZZA", "connect": "CONNETTIVITA", "buy": "SHOPPING",
}
CITY_QUESTIONS_EN_EXTRA = {  # disgiunte da PARA_CITY_EN di generate_eval_set_en.py
    "ARRIVARE": ["What is the best way to get to {r}?", "How do I travel to {r}?"],
    "TRASPORTI": ["Can I walk around {r}?", "Is there public transport in {r}?"],
    "COSA_VEDERE": ["What can I visit in {r}?", "What shouldn't I miss in {r}?"],
    "CIBO_BEVANDE": ["Where can I have a good meal in {r}?", "What restaurants are there in {r}?"],
    "ALLOGGIO": ["Where can I sleep in {r}?", "Are there hotels in {r}?"],
    "SICUREZZA": ["Is it safe to visit {r}?", "What should I watch out for in {r}?"],
    "CONNETTIVITA": ["Is there internet access in {r}?", "Where can I connect to the internet in {r}?"],
    "SHOPPING": ["Where can I buy souvenirs in {r}?", "What are the best shops in {r}?"],
}
CITY_QUESTIONS = {c: CITY_QUESTIONS_EN[c] + CITY_QUESTIONS_EN_EXTRA[c] for c in CITY_QUESTIONS_EN}

KEYWORDS = {  # radici che il contesto deve contenere perche' la categoria sia davvero trattata (minuscolo)
    "USI_COSTUMI": ["custom", "etiquette", "respect", "polite", "rude", "tradition", "cultur", "dress", "tipping", "greet", "religio"],
    "DOGANE": ["visa", "passport", "border", "customs", "airport", "flight", "ferry", "train", "entry", "arriv"],
    "SALUTE": ["health", "vaccin", "disease", "medic", "hospital", "doctor", "pharmac", "malaria", "water", "clinic"],
    "SICUREZZA": ["safe", "crime", "theft", "danger", "police", "risk", "scam", "pickpocket", "violen"],
    "TRASPORTI": [" bus", "train", "metro", "taxi", "ferry", "bicycl", "transport", "road", "drive", "driving", "car rental", "flight"],
    "CIBO_BEVANDE": ["cuisine", "dish", "food", "restaurant", "drink", "wine", "beer", "meal", "snack", "breakfast", "dinner", "lunch"],
    "ACQUISTI": ["currency", "money", "cash", "card", " atm", "price", "exchange", "dollar", "euros", "€", "pay", "shop"],
    "CONNETTIVITA": ["internet", "wifi", "wi-fi", "phone", "cellular", "sim card", "roaming", "4g", "5g", "telecom"],
    # solo notizie e media: "Cope" parla anche di consolati, lavanderie, elettricita', e le domande sulle notizie
    # riceverebbero risposte su quelli
    "VITA_QUOTIDIANA": ["news", "radio", "televis", "media", "magazine", "broadcast"],
    "ARRIVARE": ["airport", "station", "train", " bus", "flight", "highway", "motorway", "ferry", "road", "harbour", "harbor"],
    "COSA_VEDERE": ["museum", "church", "palace", "castle", "square", "monument", "cathedral", "bridge", "park", "gallery", "temple", "ruins"],
    "ALLOGGIO": ["hotel", "hostel", "campsite", "camping", "guesthouse", "guest house", "b&b", "apartment", "rooms", "lodge", "resort", "motel"],
    "SHOPPING": ["shop", "market", "mall", "souvenir", "boutique", "store", "craft"],
}
TOPIC = {  # per la risposta negativa
    "USI_COSTUMI": "about local customs", "DOGANE": "about how to get there", "SALUTE": "about health and vaccinations",
    "SICUREZZA": "about safety", "TRASPORTI": "about getting around", "CIBO_BEVANDE": "about food and drink",
    "ACQUISTI": "about money and payments", "CONNETTIVITA": "about phone and internet", "VITA_QUOTIDIANA": "about practical information",
    "ARRIVARE": "about how to get there", "COSA_VEDERE": "about what to see", "ALLOGGIO": "about where to stay", "SHOPPING": "about shopping",
}
REFUSAL_TAILS = ["for reliable details, check an official source.", "I recommend checking official sources.",
                 "better check an up-to-date source before you leave.", "I can't answer with certainty using only this text."]

# Domande fuori tema scritte a mano (oltre a OFF_TOPIC_TRAIN_EN), diverse da OFF_TOPIC_EN di generate_eval_set_en.py
OFF_TOPIC_TRAIN = OFF_TOPIC_TRAIN_EN + [
    "Who is the president of the United States?", "Tell me a joke.", "How do I write a CV?", "What is the largest planet?",
    "How do I learn to play the guitar?", "Recommend a movie to watch.", "What is inflation?", "What is 45 divided by 9?",
    "Who painted the Mona Lisa?", "What will the weather be like tomorrow in London?"]
# (dataset, config, split, colonna della domanda, licenza, quante righe tenere), come OFF_TOPIC_SOURCES
OFF_TOPIC_SOURCES = {
    "truthful_qa": ("truthfulqa/truthful_qa", "generation", "validation", "question", "Apache 2.0", 800),
    "alpaca_cleaned": ("yahma/alpaca-cleaned", "default", "train", "instruction", "CC BY 4.0", 1500),
}
TRAVEL_STEMS = ("travel", "touris", "vacation", "holiday", "trip", "abroad", "visit", "city", "cities", "country",
                "countries", "nation")

# Fatti rapidi di guides-en.db ("Campo: valore" per riga). Nel catalogo EN ci sono solo Electricity e Time zone;
# Language ed Emergency numbers servono se compaiono.
QUICK_FACT_QUESTIONS = {
    "Language": ["What language is spoken in {r}?", "Which languages do people speak in {r}?", "What language do they use in {r}?"],
    "Electricity": ["What plugs are used in {r}?", "Do I need a plug adapter for {r}?", "What is the voltage in {r}?"],
    "Time zone": ["What time zone is {r} in?", "What is the time difference with {r}?", "What is the time zone of {r}?"],
    "Emergency numbers": ["What is the ambulance number in {r}?", "What number do I call in an emergency in {r}?",
                          "What is the police number in {r}?"],
}
QUICK_FACT_TOPIC = {"Language": "about the language", "Electricity": "about electrical plugs", "Time zone": "about the time zone",
                    "Emergency numbers": "about emergency numbers"}
QUICK_FACT_KEYWORDS = {"Language": ("language", "spoken"), "Electricity": ("plug", "socket", "volt", "electric"),
                       "Time zone": ("time zone", "utc", "gmt"), "Emergency numbers": ("emergenc", "ambulance", "112", "911", "police")}

# Note personali inventate (nessun dato vero), come NOTE_SAMPLES: (titolo, testo, domande, risposta: una frase del testo).
NOTE_SAMPLES = [
    ("Hotel", "Booking at Hotel Aurora, room 214. Check-in is from 2 pm to 10 pm. Breakfast is included.",
     ["What time can I check in?", "From what time can I get into the hotel?"], "Check-in is from 2 pm to 10 pm."),
    ("Hotel", "Booking at Hotel Aurora, room 214. Check-in is from 2 pm to 10 pm. Breakfast is included.",
     ["Is breakfast included?", "Do I get breakfast at the hotel?"], "Breakfast is included."),
    ("Return flight", "Flight BA 2611 on 14 May, departing at 6:40 pm from terminal 3. Checked bag of 23 kg.",
     ["What time does my return flight leave?", "When do I fly back?"], "Flight BA 2611 on 14 May, departing at 6:40 pm from terminal 3."),
    ("Return flight", "Flight BA 2611 on 14 May, departing at 6:40 pm from terminal 3. Checked bag of 23 kg.",
     ["How much checked luggage can I bring?", "What is my baggage allowance?"], "Checked bag of 23 kg."),
    ("Rental car", "Pick up the car at the Rent Easy desk in the airport. Return it with a full tank by 10 am on Sunday.",
     ["Where do I pick up the rental car?", "Where do I collect the car?"], "Pick up the car at the Rent Easy desk in the airport."),
    ("Rental car", "Pick up the car at the Rent Easy desk in the airport. Return it with a full tank by 10 am on Sunday.",
     ["When do I have to return the car?", "By when must I bring the car back?"], "Return it with a full tank by 10 am on Sunday."),
    ("Museum", "Tickets for the national museum booked for Thursday at 11 am. The booking code is K7Q2.",
     ["What time is the museum visit?", "When am I going to the museum?"], "Tickets for the national museum booked for Thursday at 11 am."),
    ("Museum", "Tickets for the national museum booked for Thursday at 11 am. The booking code is K7Q2.",
     ["What is the museum booking code?", "Which code do I show at the museum?"], "The booking code is K7Q2."),
    ("Medicines", "Always carry the blood pressure pills, one in the morning. The prescription is in the inner pocket of the backpack.",
     ["Where did I put the prescription?", "Where is my prescription?"], "The prescription is in the inner pocket of the backpack."),
    ("Train", "Train to the coast on 9 June at 7:55 am, coach 6, seat 42. The ticket is in the railway app.",
     ["Which seat do I have on the train?", "What coach am I in on the train?"], "Train to the coast on 9 June at 7:55 am, coach 6, seat 42."),
    ("Restaurant", "Table booked at Harbour Grill on Saturday at 8:30 pm for four people.",
     ["What time is dinner on Saturday?", "When is my restaurant booking?"], "Table booked at Harbour Grill on Saturday at 8:30 pm for four people."),
    ("Insurance", "Travel insurance helpline +44 20 1234 5678, open day and night. Policy no. 55-0192.",
     ["What number do I call for the insurance?", "What is the insurance helpline?"], "Travel insurance helpline +44 20 1234 5678, open day and night."),
]


# Fine frase come SENTENCE_END, ma non dopo le abbreviazioni frequenti nelle pagine EN ("U.S. Route", "e.g. the")
# ne' prima di una minuscola: altrimenti la risposta conterrebbe frammenti come "or Polynesian peoples, ...".
SENTENCE_END = r"(?<!\bU\.S\.)(?<!\bSt\.)(?<!\bMt\.)(?<!\bDr\.)(?<!\bNo\.)(?<!\bvs\.)(?<!\be\.g\.)(?<!\bi\.e\.)(?<=[.!?])\s+(?![a-z])"
# Parole della domanda che non dicono nulla sul tema: pick_answer sceglie le frasi con le parole in comune con la
# domanda, e "there", "which", "where" sono in quasi ogni frase.
STOPWORDS = {"what", "which", "where", "when", "there", "should", "would", "could", "about", "does", "have", "with", "from",
             "this", "that", "they", "their", "into", "much", "many", "some", "tips", "know", "need", "best", "good", "people", "find", "like", "local"}
# Nomi di paese che in inglese vogliono l'articolo ("in the Philippines", "in the United States")
THE_NAMES = {"Philippines", "Netherlands", "Maldives", "Gambia", "Comoros", "Seychelles", "Vatican City", "Caribbean Netherlands",
             "Democratic Republic of the Congo", "Republic of the Congo", "Federated States of Micronesia"}


def with_article(name):
    return f"the {name}" if name in THE_NAMES or re.search(r"\b(Islands|Republic|Kingdom|States|Emirates)$", name) else name


def answer_for(context, body, q, cat, name):
    """pick_answer con le parole vuote tolte dalla domanda e la divisione in frasi inglese; "" se nessuna frase scelta
    contiene una parola chiave della categoria o una radice della domanda (pick_answer ripiega allora sulla prima frase
    del contesto, spesso fuori tema: es. "newspapers" -> una frase sulla corrente elettrica)."""
    words = [w for w in re.findall(r"\w+", q) if w.lower() not in STOPWORDS]
    answer = pick_answer(context, body, " ".join(words), cat, name, KEYWORDS, SENTENCE_END)
    stems = {w.lower()[:5] for w in words if len(w) >= 4} - {w[:5] for w in re.findall(r"\w{4,}", name.lower())}
    low = answer.lower()
    return answer if any(k in low for k in KEYWORDS[cat]) or any(s in low for s in stems) else ""


def refusal(topic, tail):
    return f"{REFUSAL} {topic}: {tail}"


def page_title(rid, sources):
    """Titolo della pagina EN di una regione: da sft-sources.tsv, altrimenti dalla cache raw/<rid>.en.url di
    generate_sft_dataset.py (canada e antartide non sono tra le regioni pilota), altrimenti None."""
    title = sources.get((rid, "en"), "-")
    if title != "-":
        return wiki_dump.norm_title(title)
    url = OUT / "raw" / f"{rid}.en.url"
    if url.exists() and (u := url.read_text(encoding="utf-8").strip()):
        return wiki_dump.norm_title(urllib.parse.unquote(u.rsplit("/wiki/", 1)[1]))
    return None


def display_name(title):
    return re.sub(r"\s*\(.*\)$", "", title)  # "Georgia (U.S. state)" -> "Georgia"


def load_en_dump(path):
    """({titolo: testo}, {figlio: genitore isPartOf}, {titoli delle citta'}) dal dump di Wikivoyage EN, come
    extract-cities-dump-en.py; i redirect si seguono sia nei titoli che nei genitori."""
    pages, parent, cities, redirects = {}, {}, set(), {}
    for title, text, redirect in wiki_dump.iter_pages(path):
        if redirect:
            redirects[title] = redirect
            continue
        pages[title] = text
        head = text[:6000] + text[-3000:]
        if m := cities_en.IS_PART_OF.search(head):
            parent[title] = wiki_dump.norm_title(m.group(1))
        if cities_en.CITY_STATUS.search(head):
            cities.add(title)
    parent = {child: redirects.get(p, p) for child, p in parent.items()}
    for title, target in redirects.items():
        if target in pages:
            pages.setdefault(title, pages[target])
    return pages, parent, cities


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump-dir", type=Path, required=True, help="cartella con i dump di dumps.wikimedia.org (DUMP_FILES)")
    ap.add_argument("--dump-date", help="data dei dump (AAAAMMGG), di default il nome della cartella")
    ap.add_argument("--negatives", type=float, default=0.33, help="negativi per positivo (0.33 = ~25%% del totale)")
    ap.add_argument("--off-topic", type=float, default=0.25, help="quota di negativi con domanda fuori tema")
    ap.add_argument("--empty", type=float, default=0.1, help="quota di negativi con il contesto di fallback")
    ap.add_argument("--cities", type=int, default=0, help="quante citta' di Wikivoyage EN usare (0 = nessuna)")
    ap.add_argument("--guides-db", type=Path, help="guides-en.db pubblicato: fatti rapidi e note personali")
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    (OUT / "raw").mkdir(parents=True, exist_ok=True)

    sources = load_sources()
    date = a.dump_date or a.dump_dir.name
    phase("dump", f"Wikivoyage EN del {date}")
    pages, parent, city_titles = load_en_dump(a.dump_dir / DUMP_FILES["en"].format(d=date))
    test_titles = {t for r in TEST_REGIONS if (t := page_title(r, sources))}
    in_test = lambda t: t in test_titles or bool(cities_en.regions_of(t, parent, {x: x for x in test_titles}))

    # Regioni con pagina EN; fuori quelle (non di test) con la pagina di una regione di test o al suo interno
    regions, skipped = [], []
    for rid, _, _ in load_regions():
        title = page_title(rid, sources)
        if not title or title not in pages:
            continue
        if rid not in TEST_REGIONS and (in_test(title) or any(rid.startswith(f"{t}-") for t in TEST_REGIONS)):
            skipped.append(rid)
            continue
        regions.append((rid, with_article(display_name(title)), title))
    print(f"regioni escluse perche' dentro una regione di test: {', '.join(skipped)}")

    data, attribution = {}, []
    phase("fonti", f"{len(regions)} regioni")
    progress = Progress("fonti", len(regions), "regione", every=20)
    for n, (rid, name, title) in enumerate(regions, 1):
        progress.update(n - 1, rid)
        if secs := parse_sections(pages[title], EN_HEADING_TO_CATEGORY):
            data[rid] = (name, secs)
            attribution.append((rid, display_name(title), SOURCE_URL["en"] + urllib.parse.quote(title.replace(" ", "_")), "CC BY-SA 4.0"))
    progress.update(len(regions), f"{len(data)} regioni con testo")

    def question(cat, name, city=False):
        return rng.choice((CITY_QUESTIONS if city else QUESTIONS)[cat]).format(r=name)

    off_topic_pool = OFF_TOPIC_TRAIN + [q for qs in fetch_off_topic(OFF_TOPIC_SOURCES, KEYWORDS, TRAVEL_STEMS).values() for q in qs]
    off_topic_uses = Counter()

    def off_topic_question():
        free = [q for q in off_topic_pool if off_topic_uses[q] < OFF_TOPIC_MAX_USES]
        q = rng.choice(free or off_topic_pool)
        off_topic_uses[q] += 1
        return q

    def row(kind, rid, cat, context, q, ans):
        return {"messages": [{"role": "user", "content": on_device_prompt(context, q)},
                             {"role": "assistant", "content": ans}], "kind": kind, "region": rid, "category": cat}

    phase("righe", "positivi, poi negativi")
    # positivi: sezione giusta + 0-2 sezioni distraenti, come generate_sft_dataset.py
    rows, pos, seen = [], 0, set()
    for rid, (name, secs) in data.items():
        for cat, body in secs:
            if not covers(cat, body, KEYWORDS):
                continue
            others = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
            for _ in range(3):
                q = question(cat, name)
                extra = rng.sample(others, min(len(others), rng.choice([0, 0, 1, 1, 2])))
                context = make_context(rng, [body] + extra, q, name)
                answer = answer_for(context, body, q, cat, name)
                if len(answer) < 40:  # la sezione giusta e' stata troncata: riprova da sola
                    context = make_context(rng, [body], q, name)
                    answer = answer_for(context, body, q, cat, name)
                if len(answer) < 40 or (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, cat, context, q, answer)); pos += 1
    # negativi: categoria assente dal contesto, domanda fuori tema, oppure contesto di fallback
    n_neg = int(pos * a.negatives)
    ids, tries, neg = list(data), 0, 0
    while neg < n_neg and tries < n_neg * 20:
        tries += 1
        rid = rng.choice(ids); name, secs = data[rid]
        tail = rng.choice(REFUSAL_TAILS)
        x = rng.random()
        if x < a.off_topic:
            q = off_topic_question()
            cat, ans = "OFF", refusal("useful to answer the question", tail)
            context = make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))), q, name)
        else:
            # Categoria qualsiasi, contesto dalle sole sezioni di altre categorie che non la trattano: le pagine EN
            # hanno quasi tutte le sezioni, e con le sole categorie assenti dalla pagina (come nel dataset italiano)
            # i rifiuti sarebbero per ~40% su VITA_QUOTIDIANA ("Cope" manca spesso).
            cat = rng.choice(list(QUESTIONS))
            q, ans = question(cat, name), refusal(TOPIC[cat], tail)
            if x < a.off_topic + a.empty:
                context = FALLBACK_CONTEXT
            else:
                bodies = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
                if not bodies:
                    continue
                context = make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))), q, name)
        if (context, q) in seen:
            continue
        seen.add((context, q))
        rows.append(row("neg", rid, cat, context, q, ans)); neg += 1

    # Citta' (--cities): come generate_sft_dataset.py, ma si analizzano solo le pagine che servono (in ordine casuale
    # fino ad averne --cities con sezioni utili). Fuori le citta' delle regioni di test e le pagine delle regioni.
    city_pos = city_neg = 0
    if a.cities:
        phase("citta'", f"al massimo {a.cities} citta' di Wikivoyage EN")
        region_titles = {t for _, _, t in regions} | test_titles
        candidates = sorted(t for t in city_titles if t not in region_titles and not in_test(t))
        rng.shuffle(candidates)
        cities = []
        for title in candidates:
            if len(cities) >= a.cities:
                break
            secs = [(c, b) for c, b in parse_sections(pages[title], CITY_HEADING_TO_CATEGORY)
                    if covers(c, b, KEYWORDS) and len(b) >= CITY_MIN_SECTION]
            if secs:
                cities.append((title, secs))
        city_cats = Counter()
        for title, secs in cities:
            chosen = sorted(secs, key=lambda s: (city_cats[s[0]], rng.random()))[:CITY_MAX_QUESTIONS]
            city_cats.update(c for c, _ in chosen)
            rid, name = f"citta:{title}", display_name(title)  # stesso prefisso del dataset italiano
            attribution.append((rid, name, SOURCE_URL["en"] + urllib.parse.quote(title.replace(" ", "_")), "CC BY-SA 4.0"))
            for cat, body in chosen:
                q = question(cat, name, city=True)
                others = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
                context = make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
                answer = answer_for(context, body, q, cat, name)
                if len(answer) < 40 or (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, cat, context, q, answer)); city_pos += 1
            missing = [c for c in CITY_QUESTIONS if c not in {c for c, _ in secs}]
            if missing and rng.random() < a.negatives * 2:
                cat = rng.choice(missing)
                q = question(cat, name, city=True)
                context = make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))), q, name)
                if (context, q) not in seen:
                    seen.add((context, q))
                    rows.append(row("neg", rid, cat, context, q, refusal(TOPIC[cat], rng.choice(REFUSAL_TAILS)))); city_neg += 1

    # Fatti rapidi e note personali (--guides-db), come generate_sft_dataset.py; fuori le regioni di test
    quick_pos = quick_neg = note_pos = 0
    if a.guides_db:
        phase("fatti rapidi", str(a.guides_db))
        for rid, (qf_body, fields) in sorted(load_quick_facts(a.guides_db, QUICK_FACT_QUESTIONS).items()):
            if rid not in data or rid in TEST_REGIONS:
                continue
            name, secs = data[rid]
            others = [b for _, b in secs]
            for field, line in fields.items():
                for q in rng.sample(QUICK_FACT_QUESTIONS[field], 2):
                    q = q.format(r=name)
                    context = make_context(rng, [qf_body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
                    if line not in context or (context, q) in seen:
                        continue
                    seen.add((context, q))
                    rows.append(row("pos", rid, "FATTI_RAPIDI", context, q, line)); quick_pos += 1
                unrelated = [b for b in others if not any(k in b.lower() for k in QUICK_FACT_KEYWORDS[field])]
                if unrelated and rng.random() < a.negatives * 2:
                    q = rng.choice(QUICK_FACT_QUESTIONS[field]).format(r=name)
                    context = make_context(rng, rng.sample(unrelated, min(len(unrelated), rng.randint(1, 3))), q, name)
                    if (context, q) not in seen:
                        seen.add((context, q))
                        rows.append(row("neg", rid, "FATTI_RAPIDI", context, q,
                                        refusal(QUICK_FACT_TOPIC[field], rng.choice(REFUSAL_TAILS)))); quick_neg += 1
        note_regions = [rid for rid in data if rid not in TEST_REGIONS]
        for title, body, questions, answer in NOTE_SAMPLES:
            for rid in rng.sample(note_regions, min(len(note_regions), 8)):
                secs = [b for _, b in data[rid][1]]
                q = rng.choice(questions)
                note = f"Personal note: {title}\n{body}"  # etichetta di buildOnDeviceContext in inglese
                sections = make_context(rng, rng.sample(secs, min(len(secs), rng.choice([0, 1, 2]))), q, data[rid][0],
                                        max_chars=MAX_CONTEXT - len(note) - 2)
                context = "\n\n".join(x for x in (sections, note) if x)
                if (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, "NOTE", context, q, answer)); note_pos += 1
    rng.shuffle(rows)

    for name, (dataset, *_, lic, _) in OFF_TOPIC_SOURCES.items():  # solo domande, con rifiuto come risposta
        attribution.append(("-", f"off-topic questions ({name})", f"https://huggingface.co/datasets/{dataset}", lic))
    data_out, attr_out = OUT / "pocket_travel_sft.en.jsonl", OUT / "ATTRIBUTION.en.tsv"
    with open(data_out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(attr_out, "w", encoding="utf-8") as f:
        f.write("regionId\tdisplayName\tsourceUrl\tlicense\n")
        for rid, name, url, lic in attribution:
            f.write(f"{rid}\t{name}\t{url}\t{lic}\n")
    print(f"regioni con guida EN: {len(data)}/{len(regions)}; positivi={pos} negativi={neg} "
          f"citta' positivi={city_pos} negativi={city_neg} totale={len(rows)}")
    print("per categoria:", dict(Counter(r["category"] for r in rows)))
    print(f"domande fuori tema distinte usate: {len(off_topic_uses)} (max {max(off_topic_uses.values(), default=0)} volte l'una)")
    if a.guides_db:
        print(f"fatti rapidi: {quick_pos} positivi, {quick_neg} rifiuti; note personali: {note_pos} positivi")
    print(f"scritto {data_out}")


if __name__ == "__main__":
    main()
