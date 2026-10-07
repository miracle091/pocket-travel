#!/usr/bin/env python3
"""Genera il dataset SFT v10 (v9 con --user-style-questions 0, --no-city-daily-life, --no-balanced-negatives,
--no-clear-questions e senza le altre opzioni nuove) in italiano o in inglese con lo stesso metodo, cosi' i due dataset restano equivalenti:
stesse fonti (Wikivoyage IT ed EN dello stesso dump, Wikipedia IT o EN per le categorie deboli, fatti rapidi e note), stessa
composizione per categoria, stessi tipi di domanda, stesso rapporto di rifiuti. Cambiano solo le tabelle della lingua
(domande, parole chiave, rifiuto, prompt dell'app), prese da generate_sft_dataset.py (italiano) e
generate_sft_dataset_en.py (inglese).

Traduzione incrociata (translate_dataset.py): per ogni regione e categoria, se la sezione nella lingua del dataset
manca o e' molto piu' povera di quella nell'altra lingua (needs_translation), si usa la sezione dell'altra lingua
tradotta con MarianMT. Il dataset inglese usa gli articoli originali di Wikipedia EN (enwiki) e traduce quelli di
Wikipedia IT solo per i temi senza articolo inglese. Le righe con una sezione tradotta
hanno "translated": true e ATTRIBUTION indica la traduzione automatica (CC BY-SA 4.0, opera derivata).

- positivi: sezione giusta + 0-2 sezioni distraenti, risposta = frasi della sezione (estrattivo); una risposta senza
  parole chiave della categoria o della domanda si scarta;
- negativi: categoria qualsiasi con le sole sezioni di altre categorie che non la trattano (di default in proporzione
  ai positivi della categoria, --balanced-negatives), domanda fuori tema, contesto di fallback dell'app; una quota con il
  contesto nell'altra lingua;
- citta': --cities pagine della lingua del dataset, fino a --city-questions sezioni per citta'; di default
  (--city-daily-life) anche la sezione VITA_QUOTIDIANA (Informazioni utili / Cope), con le citta' oltre --cities che la
  hanno (solo quella domanda, nessun rifiuto); le sue radici in piu' (uffici turistici, farmacie, consolati) valgono anche
  per i paesi, e i rifiuti delle citta' hanno nel contesto solo sezioni che non trattano la categoria;
- di default (--clear-questions) niente domande che una sezione di un'altra categoria soddisfa (AMBIGUOUS_QUESTIONS) ne'
  domande sui vaccini in SALUTE (sono di VACCINAZIONI, come nell'app);
- fatti rapidi e note personali con --guides-db (guides.db per l'italiano, guides-en.db per l'inglese);
- con --nearby, domande su cosa c'e' qui vicino e sulle prossime partenze con i blocchi di contesto dell'app
  (sft_nearby.py, dati sintetici), per una quota del dataset finale;
- con --distances (e --cities), domande sulla distanza tra due citta' con le sezioni di entrambe nel contesto: la
  frase della guida con i km o il tempo di viaggio, o il rifiuto quando le guide non li riportano;
- con --cities-db, Storia e Clima delle citta' da Wikipedia (sezioni STORIA e CLIMA dei cities.db pubblicati): domande
  di storia e clima con la sezione nel contesto, rifiuti quando la citta' non le ha, e domande pratiche con Storia o
  Clima in coda al contesto (distrattori), per al massimo --wikipedia-cities citta';
- con --emergency, domande sui numeri di emergenza con la riga dell'app (emergencyNumbersContext) in testa al contesto
  per le regioni con numeri in emergency-numbers.tsv, il rifiuto per le altre, per una quota del dataset finale;
- di default (--user-style-questions 0.2), nelle categorie di USER_STYLE_QUESTION_CATS una domanda su cinque e' una
  domanda di viaggio in stile utente (travel_questions.py, UltraChat MIT, tradotta in italiano per il dataset italiano)
  invece di un modello.
Fuori dal training le regioni di test e quelle la cui pagina (IT o EN) e' la pagina di una regione di test o vi
appartiene (es. figi-occidentali ha la pagina "Figi"/"Fiji" di figi-lau): restano nel file solo le regioni di test.

Uso: python generate_sft.py --lang it|en --dump-dir <cartella dei dump> [--cities 4500] [--guides-db <db>] [--nearby 0.03]
     [--distances 0.02] [--cities-db <cities.db> ...] [--wikipedia-cities 1500] [--emergency 0.01] [--user-style-questions 0.2] [--version v10] [--seed 42]
--cities-db: i cities.db pubblicati della lingua del dataset (cities.db per l'italiano, cities-en.db per l'inglese), uno
per regione; la regione e' il nome del file fino al primo "--" (<regionId>--<versione>--cities.db, come gli asset delle
release) oppure si scrive <regionId>=<file>.
Output in data/sft/: pocket_travel_sft.<versione>.<lang>.jsonl, ATTRIBUTION.<versione>.<lang>.tsv ed EXCLUDED.<versione>.<lang>.tsv
(le fonti e le sezioni rimaste fuori e perche', vedi write_excluded di generate_sft_dataset.py); traduzioni in cache in
raw/translations.<src>-<tgt>.jsonl. Senza --version: v10 con --nearby, --distances, --cities-db, --emergency,
--user-style-questions diverso da 0, --city-daily-life, --balanced-negatives o --clear-questions (i default),
altrimenti v9; il v9, che
usano i training, si sovrascrive solo dando
--version v9. Fonti e pulizia sono cambiate dal v9 generato: gli stessi argomenti non ridanno quel file.
"""
import argparse
import json
import math
import random
import re
import sqlite3
import sys
import urllib.parse
from collections import Counter, defaultdict
from contextlib import closing
from pathlib import Path

import city_population
import generate_sft_dataset as it
import generate_sft_dataset_en as en
import sft_nearby
import travel_questions
import wiki_dump
from eval_common import TEST_REGIONS
from status import Progress, phase
from translate_dataset import LICENSE as MT_LICENSE, MODELS as MT_MODELS, Translator, needs_translation

# Parole delle domande che non dicono nulla sul tema (vedi en.answer_for): "come", "dove", "quali" sono in quasi ogni frase
IT_STOPWORDS = {"quali", "quale", "come", "dove", "quando", "sono", "devo", "posso", "cosa", "serve", "servono", "sapere",
                "conviene", "meglio", "consigli", "trovo", "fare", "nella", "nelle", "della", "delle", "degli", "sulla",
                "sulle", "questo", "questa", "fanno", "usano", "usare", "gente", "posto", "locale"}

LANGS = {
    "it": dict(
        prompt=it.on_device_prompt, fallback=it.FALLBACK_CONTEXT,
        questions={c: it.QUESTIONS[c] + it.QUESTIONS_EXTRA[c] for c in it.QUESTIONS}, other_questions=it.QUESTIONS_EN,
        city_questions=it.CITY_QUESTIONS, other_city_questions=it.CITY_QUESTIONS_EN,
        keywords=it.KEYWORDS, topic=it.TOPIC, tails=it.REFUSAL_TAILS,
        refusal=lambda topic, tail: f"Il contesto non contiene informazioni {topic}: {tail}", off_topic_topic="utili a rispondere",
        off_topic_train=it.OFF_TOPIC_TRAIN + it.OFF_TOPIC_TRAIN_EN, off_topic_sources=it.OFF_TOPIC_SOURCES,
        travel_stems=it.TRAVEL_STEMS, quick_questions=it.QUICK_FACT_QUESTIONS, quick_topic=it.QUICK_FACT_TOPIC,
        quick_keywords=it.QUICK_FACT_KEYWORDS, notes=it.NOTE_SAMPLES, note_label="Nota personale",
        headings=it.HEADING_TO_CATEGORY, city_headings=it.CITY_HEADING_TO_CATEGORY, split=it.SENTENCE_END,
        stopwords=IT_STOPWORDS, translated="tradotta automaticamente dall'inglese",
        wiki_questions=it.WIKI_QUESTIONS, other_wiki_questions=it.WIKI_QUESTIONS_EN, wiki_keywords=it.WIKI_KEYWORDS),
    "en": dict(
        prompt=en.on_device_prompt, fallback=en.FALLBACK_CONTEXT,
        questions=en.QUESTIONS, other_questions=it.QUESTIONS,
        city_questions=en.CITY_QUESTIONS, other_city_questions=it.CITY_QUESTIONS,
        keywords=en.KEYWORDS, topic=en.TOPIC, tails=en.REFUSAL_TAILS,
        refusal=en.refusal, off_topic_topic="useful to answer the question",
        off_topic_train=en.OFF_TOPIC_TRAIN + it.OFF_TOPIC_TRAIN[:6], off_topic_sources=en.OFF_TOPIC_SOURCES,
        travel_stems=en.TRAVEL_STEMS, quick_questions=en.QUICK_FACT_QUESTIONS, quick_topic=en.QUICK_FACT_TOPIC,
        quick_keywords=en.QUICK_FACT_KEYWORDS, notes=en.NOTE_SAMPLES, note_label="Personal note",
        headings=it.EN_HEADING_TO_CATEGORY, city_headings=en.CITY_HEADING_TO_CATEGORY, split=en.SENTENCE_END,
        stopwords=en.STOPWORDS, translated="machine-translated from Italian",
        wiki_questions=en.WIKI_QUESTIONS, other_wiki_questions=it.WIKI_QUESTIONS, wiki_keywords=en.WIKI_KEYWORDS),
}
OTHER = {"it": "en", "en": "it"}

# VITA_QUOTIDIANA delle citta' (--city-daily-life, v10): le sezioni "Informazioni utili" (IT) e "Cope" (EN) delle pagine
# delle citta', che GenerateCities.kt mette in cities.db e l'assistente quindi vede. Nel v9 la categoria aveva solo le
# sezioni dei paesi ("Tenersi informati"/"Cope", in meno di un quarto delle pagine): IT 364 positivi e 288 rifiuti, EN 263
# e 294. Le sezioni delle citta' parlano di uffici turistici, farmacie e consolati piu' che di giornali, da qui le radici in
# piu'. Domande generiche come le altre e disgiunte da PARA/PARA_CITY di generate_eval_set*.py.
DAILY_LIFE = "VITA_QUOTIDIANA"
CITY_DAILY_LIFE = {
    # domande sui servizi di cui parlano le sezioni: una domanda generica ("Consigli utili per chi visita X?") la
    # soddisferebbe anche una sezione di un'altra categoria, e il suo rifiuto sarebbe ambiguo (vedi AMBIGUOUS_QUESTIONS)
    "it": dict(heading="informazioni utili",
               questions=["Dove trovo un ufficio turistico o una farmacia a {r}?",
                          "Ci sono uffici informazioni, farmacie o consolati a {r}?"],
               keywords=["ufficio turistic", "uffici turistic", "ufficio del turismo", "farmaci", "ospedal", "consolat",
                         "ambasciat", "lavanderi", "uffici postal", "ufficio postal"]),
    "en": dict(heading="cope",
               questions=["Where can I find a tourist office or a pharmacy in {r}?",
                          "Are there tourist information offices or consulates in {r}?"],
               keywords=["tourist office", "tourist information", "visitor cent", "information cent", "pharmac", "hospital",
                         "consulat", "embass", "laundr", "post office"]),
}


def with_city_daily_life(L, lang):
    """Le tabelle della lingua [lang] con la sezione VITA_QUOTIDIANA delle citta': titolo della sezione, domande (anche
    quelle dell'altra lingua, per --other-lang) e radici in piu'."""
    own, other = CITY_DAILY_LIFE[lang], CITY_DAILY_LIFE[OTHER[lang]]
    return {**L, "city_headings": {**L["city_headings"], own["heading"]: DAILY_LIFE},
            "city_questions": {**L["city_questions"], DAILY_LIFE: own["questions"]},
            "other_city_questions": {**L["other_city_questions"], DAILY_LIFE: other["questions"]},
            "keywords": {**L["keywords"], DAILY_LIFE: L["keywords"][DAILY_LIFE] + own["keywords"]}}


# Domande che una sezione di un'altra categoria soddisfa quanto quella giusta (--clear-questions, v10): ogni sezione e'
# "informazione pratica", e "senza perdersi" e' anche Cosa vedere. Un positivo prende frasi qualsiasi e un rifiuto ha la
# risposta nel contesto.
AMBIGUOUS_QUESTIONS = {"Quali informazioni pratiche mi servono in {r}?", "Consigli pratici per la vita di tutti i giorni in {r}.",
                       "What practical info do I need for {r}?", "Come si visita {r} senza perdersi?"}


def with_clear_questions(L):
    """Le tabelle della lingua senza AMBIGUOUS_QUESTIONS e senza domande sui vaccini in SALUTE: con una parola di
    VACC_WORDS l'app mette nel contesto il riassunto delle vaccinazioni (isVaccinationQuestion), quindi sono domande di
    VACCINAZIONI. Nel v9 "Devo fare vaccinazioni per X?" in SALUTE aveva quasi sempre per risposta frasi su acqua e
    ospedali (IT 110 righe, EN 74), mentre VACCINAZIONI rifiutava domande quasi uguali."""
    def clear(pools):
        return {cat: [q for q in qs if q not in AMBIGUOUS_QUESTIONS and not (cat == "SALUTE" and VACC_WORDS.search(q))]
                for cat, qs in pools.items()}
    return {**L, **{k: clear(L[k]) for k in ("questions", "other_questions", "city_questions", "other_city_questions")}}


def weighted_category(rng, cats, counts):
    """Una categoria di [cats] con probabilita' proporzionale a [counts] (almeno 1 ciascuna): i rifiuti seguono quanto la
    categoria e' presente, invece di darne lo stesso numero a ognuna (--balanced-negatives)."""
    return rng.choices(cats, weights=[max(counts[c], 1) for c in cats])[0]

# Vaccinazioni (--vaccinations): riassunti di VaccinationSummaryExport (toSummaryText dell'app, lo stesso testo che
# TravelAssistant mette nel contesto per le domande sui vaccini). Domande disgiunte da PARA di generate_eval_set*.py.
VACC_QUESTIONS = {
    "it": {"any": ["Quali vaccini servono per andare in {r}?", "Devo vaccinarmi per il viaggio in {r}?",
                   "Serve qualche certificato di vaccinazione per entrare in {r}?", "Che vaccinazioni mi servono per {r}?"],
           "yf": ["Serve il vaccino contro la febbre gialla per {r}?", "Mi chiedono il certificato della febbre gialla in {r}?"],
           "rec": ["Quali vaccini sono consigliati per {r}?", "Che vaccinazioni mi consigliate per un viaggio in {r}?"]},
    "en": {"any": ["Which vaccines do I need to go to {r}?", "Do I need any vaccinations for my trip to {r}?",
                   "Is a vaccination certificate required to enter {r}?", "What shots do I need for {r}?"],
           "yf": ["Do I need a yellow fever vaccine for {r}?", "Will {r} ask for a yellow fever certificate?"],
           "rec": ["Which vaccines are recommended for {r}?", "What vaccinations do you recommend for a trip to {r}?"]},
}
VACC_TOPIC = {"it": "sulla salute e sulle vaccinazioni", "en": "about health and vaccinations"}
VACC_HEADERS = {"it": ("Certificati richiesti:", ("Raccomandate per la destinazione:","Da valutare con il medico"), "Verifica sempre"),
                "en": ("Required certificates:", ("Recommended for the destination:", "To discuss with a doctor"), "Always check")}
VACC_YF = {"it": "Febbre gialla", "en": "Yellow fever"}
# Stesse parole di isVaccinationQuestion in TravelAssistant.kt: una sezione che le contiene parla di vaccini
VACC_WORDS = re.compile(r"vaccin|febbre gialla|yellow fever|polio|meningococc|meningitis|profilassi|certificat|hajj|umrah", re.I)


def vaccination_answers(text, lang):
    """{tipo di domanda: risposta} estratti dal riassunto (righe intere, come le risposte estrattive delle guide):
    certificati o la riga "nessun certificato nei nostri dati", poi i vaccini raccomandati (o da valutare col medico),
    e sempre la riga di verifica; al massimo 3 frasi come chiede il prompt."""
    required_head, rec_heads, check_head = VACC_HEADERS[lang]
    lines = text.split("\n")
    check = next(l for l in lines if l.startswith(check_head))
    sentence = lambda l: l.removeprefix("- ").rstrip(".") + "."
    if required_head in lines:
        i = lines.index(required_head)
        certs = []
        for l in lines[i + 1:]:
            if not l.startswith("- "):
                break
            certs.append(sentence(l))
        # tutti i certificati in una frase: una risposta che ne tralascia uno insegnerebbe a ometterlo
        required = (f"{required_head} " + "; ".join(c.rstrip(".") for c in certs) + ".") if len(certs) > 1 else certs[0]
    else:
        required = lines[1]
    rec = next((sentence(l) for head in rec_heads for l in lines if l.startswith(head)), None)

    def fit(main, extra):
        """[main], [extra] e la riga di verifica entro MAX_ANSWER; senza [extra] se non ci sta, None se nemmeno cosi'
        (i certificati non si tagliano: la domanda di quel tipo non si fa)."""
        for parts in ((main, extra, check), (main, check)):
            if len(text := " ".join(x for x in parts if x)) <= it.MAX_ANSWER:
                return text
        return None

    out = {"any": fit(required, rec)}
    if rec:
        out["rec"] = fit(rec, None)
    yf = next((sentence(l) for l in lines if l.startswith("- " + VACC_YF[lang])), None)
    if yf or required_head not in lines:
        # febbre gialla non richiesta ma raccomandata (paese a rischio): anche la riga delle raccomandate
        rec_yf = rec if not yf and rec and VACC_YF[lang] in rec else None
        out["yf"] = fit(yf or required, rec_yf)
    return {k: v for k, v in out.items() if v}


# Distanze tra citta' (--distances): "Quanto dista X da Y?" con nel contesto le sezioni delle due citta', come le cerca
# l'app quando la domanda nomina due citta' (namedCities in TravelAssistant.kt). Se l'app ha le coordinate delle due
# citta', il contesto si apre con la distanza calcolata (cityDistanceContext): in linea d'aria e, con la rete stradale
# scaricata, il percorso in auto; la risposta e' quel testo. Senza, le guide danno km o tempi solo per alcune coppie,
# spesso non tra le grandi citta': risposta estrattiva quando una frase del contesto nomina l'altra citta' con una
# distanza o un tempo, altrimenti il rifiuto. Domande disgiunte da DISTANCE_Q di eval_retrieval.py.
DIST_QUESTIONS = {
    "it": {"distance": ["Quanti chilometri ci sono da {o} a {c}?", "Qual e' la distanza tra {c} e {o}?",
                        "Quanto e' lontana {c} da {o}?"],
           "time": ["Quanto ci si mette da {o} a {c}?", "Quanto tempo serve per andare da {o} a {c}?"]},
    "en": {"distance": ["What is the distance between {c} and {o}?", "How many kilometres is {c} from {o}?",
                        "Is {c} far from {o}?"],
           "time": ["How long is the trip from {o} to {c}?", "How long does the drive from {o} to {c} take?"]},
}
DIST_TOPIC = {"it": "sulla distanza tra {c} e {o}", "en": "about the distance between {c} and {o}"}
DIST_CATS = ("ARRIVARE", "TRASPORTI")  # Come arrivare e Come spostarsi delle pagine delle citta'
# Una distanza o un tempo di viaggio ("72 km", "3 h 30", "20 minuti", "2 hours"): TRAVEL_FIGURE di eval_retrieval.py
# piu' le unita' scritte per esteso.
TRAVEL_FIGURE = re.compile(r"\d+\s*(km|chilometri|kilomet(?:er|re)s?|miglia|miles?|h|ore|minuti|min|hours?|minutes?)\b", re.I)
# Quota degli esempi con la distanza calcolata nel contesto: percorso in auto (rete stradale scaricata), sola linea
# d'aria, nessun blocco (cities.db senza coordinate: guide o rifiuto).
DIST_VARIANTS = (("car", 0.3), ("air", 0.3), ("none", 0.4))
# Percorso in auto sintetico per il training, dalla distanza in linea d'aria vera (coordinate di Wikidata): il modello
# deve solo riportare i numeri del contesto, che sul telefono calcola BRouter.
ROAD_FACTOR = (1.15, 1.45)
CAR_KMH = (55, 95)


def _round(x):
    """Arrotondamento come Math.round di Kotlin (meta' verso l'alto), non quello bancario di Python."""
    return math.floor(x + 0.5)


def travel_time(seconds):
    """Come travelTime in TravelAssistant.kt: "45 min", "2 h", "1 h 35 min"."""
    minutes = max(1, _round(seconds / 60))
    hours, rest = divmod(minutes, 60)
    return f"{rest} min" if hours == 0 else f"{hours} h" if rest == 0 else f"{hours} h {rest} min"


def distance_context(a, b, straight_m, car, lang):
    """Come cityDistanceContext in TravelAssistant.kt: [car] e' (metri, secondi) o None."""
    a, b = en.display_name(a), en.display_name(b)
    km = lambda m: max(1, _round(m / 1000))
    air = (f"{a} and {b} are {km(straight_m)} km apart in a straight line." if lang == "en"
           else f"{a} e {b} distano {km(straight_m)} km in linea d'aria.")
    if car is None:
        road = "By road the distance is longer." if lang == "en" else "Su strada la distanza è maggiore."
    elif lang == "en":
        road = f"By car the route is {km(car[0])} km, about {travel_time(car[1])}."
    else:
        road = f"In auto il percorso è di {km(car[0])} km, circa {travel_time(car[1])}."
    return f"{air} {road}"


def haversine_m(p, q):
    """Distanza in metri sulla sfera tra (lat, lon) [p] e [q]: l'app usa l'ellissoide (Location.distanceBetween), la
    differenza e' sotto l'1%."""
    la1, lo1, la2, lo2 = map(math.radians, (*p, *q))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def distance_sentences(body, other, split=it.SENTENCE_END):
    """Frasi di [body] che nominano [other] (parola intera) con una distanza o un tempo di viaggio."""
    name = re.compile(rf"\b{re.escape(other)}\b")
    return [s for s in it.sentences(body, split) if name.search(s) and TRAVEL_FIGURE.search(s)]


def distance_pairs(by_name, split=it.SENTENCE_END):
    """{(citta', altra citta'): con distanza} per ogni citta' di [by_name] ({nome: [(categoria, corpo)]}) nominata nelle
    sezioni DIST_CATS di un'altra (nome di una parola di almeno 5 lettere, come distance_pairs di eval_retrieval.py);
    con distanza se una di quelle frasi la nomina con km o tempi di viaggio."""
    names = {n for n in by_name if " " not in n and len(n) >= 5}
    pairs = {}
    for city, secs in by_name.items():
        for cat, body in secs:
            if cat in DIST_CATS:
                for other in names & set(re.findall(r"\w+", body)) - {city}:
                    pairs[city, other] = pairs.get((city, other), False) or bool(distance_sentences(body, other, split))
    return pairs


def distance_example(rng, city, other, by_name, lang, refusal, split=it.SENTENCE_END, coords=None):
    """(contesto, domanda, risposta, tipo, con la distanza calcolata) sulla distanza tra [city] e [other], o None senza
    sezioni DIST_CATS. Contesto: le sezioni DIST_CATS delle due citta', con davanti la distanza calcolata
    (distance_context) se [coords] ({nome: (lat, lon)}) ha le due citta' e la variante estratta (DIST_VARIANTS) la
    prevede; la risposta e' allora quel testo.
    Altrimenti fino a 2 frasi del contesto che danno la distanza (dalle pagine delle due citta', ognuna che nomina
    l'altra), o il rifiuto. Come l'app, niente blocco in linea d'aria per una domanda sul solo tempo di viaggio."""
    own = [(b, other) for c, b in by_name[city] if c in DIST_CATS]
    oth = [(b, city) for c, b in by_name.get(other, []) if c in DIST_CATS]
    if not own:
        return None
    coords = coords or {}
    variant = "none"
    if city in coords and other in coords:
        names, weights = zip(*DIST_VARIANTS)
        variant = rng.choices(names, weights)[0]
    kind = "distance" if variant == "air" else rng.choice(["distance", "time"])
    q = rng.choice(DIST_QUESTIONS[lang][kind]).format(c=city, o=other)
    bodies = [b for b, _ in own + rng.sample(oth, min(len(oth), 1))]
    if variant != "none":
        straight = haversine_m(coords[city], coords[other])
        car = None
        if variant == "car":
            road = straight * rng.uniform(*ROAD_FACTOR)
            car = (road, road / 1000 / rng.uniform(*CAR_KMH) * 3600)
        a, b = (city, other) if rng.random() < 0.5 else (other, city)
        block = distance_context(a, b, straight, car, lang)
        sections = it.make_context(rng, bodies, q, max_chars=it.MAX_CONTEXT - len(block) - 2)
        return "\n\n".join(x for x in (block, sections) if x), q, block, "pos", True
    context = it.make_context(rng, bodies, q)
    named = [other] * len(own) + [city] * (len(bodies) - len(own))
    found = [s for body, n in zip(bodies, named) for s in distance_sentences(body, n, split)
             if s in context and not it.URL.search(s) and len(s) <= it.MAX_ANSWER]
    answer = []
    for s in dict.fromkeys(found):
        if len(answer) < 2 and sum(len(x) + 1 for x in answer) + len(s) <= it.MAX_ANSWER:
            answer.append(s)
    if answer:
        return context, q, " ".join(answer), "pos", False
    return context, q, refusal(DIST_TOPIC[lang].format(c=city, o=other)), "neg", False


def flag_regions():
    """{codice paese: [regionId]} da regions.sh (flagCode, ottavo campo)."""
    src = (it.HERE / "regions.sh").read_text(encoding="utf-8")
    out = defaultdict(list)
    for f in (r.split("|") for r in re.findall(r'^\s*"([^"]+\|[^"]+)"\s*$', src, re.M)):
        if len(f) > 7 and f[7]:
            out[f[7].lower()].append(f[0])
    return out


def nearby_context(rng, block, q, name, bodies, L, transport=False, empty=0.1):
    """Contesto di una domanda --nearby come in TravelAssistant: il blocco "qui vicino" o "prossime partenze" prima di 0-2
    sezioni della guida, che prendono lo spazio rimasto (selectContext). Senza blocco (nessuna posizione o fermata) 1-3
    sezioni che non hanno parole della domanda (ne' trattano i trasporti, se [transport]), o il contesto di fallback."""
    if block:
        sections = it.make_context(rng, rng.sample(bodies, min(len(bodies), rng.choice([0, 1, 1, 2]))), q, name,
                                   max_chars=it.MAX_CONTEXT - len(block) - 2)
        return "\n\n".join(x for x in (block, sections) if x)
    stems = it.question_stems(" ".join(w for w in re.findall(r"\w+", q) if w.lower() not in L["stopwords"]), name)
    unrelated = [b for b in bodies if not any(s in it._folded(b) for s in stems)
                 and not (transport and it.covers("TRASPORTI", b, L["keywords"]))]
    if not unrelated or rng.random() < empty:
        return L["fallback"]
    return it.make_context(rng, rng.sample(unrelated, min(len(unrelated), rng.randint(1, 3))), q, name)


# Righe positive con la stessa domanda e la stessa risposta (cambiano solo i distrattori del contesto): due insegnano a
# ignorarli, di piu' ripetono la stessa coppia (nel v9 c'erano gruppi fino a 6).
SAME_ANSWER_MAX = 2


def question_of(content, prompt):
    """La domanda di un prompt costruito con [prompt] (on_device_prompt di una lingua)."""
    probe = prompt("\x00", "\x01")
    sep, tail = probe[probe.index("\x00") + 1:probe.index("\x01")], probe[probe.index("\x01") + 1:]
    return content[content.rindex(sep) + len(sep):len(content) - len(tail)]


def cap_repeats(rows, prompt, limit=SAME_ANSWER_MAX):
    """[rows] senza le righe positive oltre la [limit]-esima con la stessa domanda e la stessa risposta."""
    uses, out = Counter(), []
    for r in rows:
        if r["kind"] == "pos":
            key = (question_of(r["messages"][0]["content"], prompt), r["messages"][1]["content"])
            uses[key] += 1
            if uses[key] > limit:
                continue
        out.append(r)
    return out


def cached_coordinates(titles, lang, cache_path, fetch=city_population.wikidata_coordinates):
    """{titolo: (lat, lon)} delle pagine [titles] di Wikivoyage [lang], da [cache_path] (JSON, anche i titoli senza
    coordinate, come null) e da Wikidata per quelli che la cache non ha ancora. Un errore di rete si propaga e la cache
    resta com'era: senza coordinate lo stesso seme darebbe un altro dataset."""
    cache = json.loads(cache_path.read_text(encoding="utf-8")) if cache_path.exists() else {}
    if missing := sorted(set(titles) - cache.keys()):
        found = fetch(missing, lang)
        cache.update({t: found.get(t) for t in missing})
        cache_path.write_text(json.dumps(cache, ensure_ascii=False, sort_keys=True), encoding="utf-8")
    return {t: tuple(cache[t]) for t in titles if cache.get(t)}


def wp_en_sections(pages, en_title, cat, keywords):
    """(titolo finale, [(cat, paragrafo)]) dell'articolo di Wikipedia EN sul tema [cat] del paese [en_title], il primo di
    wp_candidate_titles che c'e' in [pages] ({titolo: (titolo finale, testo)} di wiki_dump.load_titles), oppure
    (None, []). Come per Wikipedia IT (fetch_wp_it), un titolo che rinvia a un articolo senza la parola del tema
    ("Vatican City cuisine" -> "Vatican City") non vale."""
    for title in it.wp_candidate_titles(en_title, cat):
        final, text = pages.get(wiki_dump.norm_title(title), (None, None))
        if text and it.topic_word(title, en_title) in final.lower():
            return final, it.parse_wp(text, cat, keywords, it.WP_SKIP_SECTIONS_EN)
    return None, []


def wp_fallback(secs):
    """I paragrafi di Wikipedia IT ([(cat, corpo, titolo)] in secs["wp"]) dei temi senza un articolo di Wikipedia EN in
    secs["wp_en"]: nel dataset inglese si traducono."""
    have = {c for c, _, _ in secs["wp_en"]}
    return [s for s in secs["wp"] if s[0] not in have]


# Fonti dei riassunti delle vaccinazioni (--vaccinations), con le dichiarazioni richieste dalle licenze (come nella
# schermata delle licenze dell'app)
VACC_ATTRIBUTION = [
    ("-", "vaccinazioni: Travel.gc.ca (Governo del Canada)", "https://travel.gc.ca/travelling/advisories",
     "Open Government Licence - Canada 2.0: contains information licensed under the Open Government Licence - Canada"),
    ("-", "vaccinazioni: TravelHealthPro (UKHSA / NaTHNaC)", "https://travelhealthpro.org.uk/countries",
     "Open Government Licence v3.0: contains public sector information published by UKHSA and NaTHNaC, licensed under the "
     "Open Government Licence v3.0"),
]


def drop_shared_wp(raw, test_regions):
    """Toglie da [raw] ({regionId: (nome, secs, ...)}) i paragrafi di Wikipedia (secs["wp"], secs["wp_en"]: [(cat, corpo,
    titolo)]) di un articolo gia' usato da un'altra regione, e le regioni rimaste senza testo; [(regionId, fonte, cat,
    motivo)] di quelli tolti. Prima le regioni di test: un articolo che hanno anche loro resta nel test e non entra nel
    training. Come drop_shared_pages, ma per gli articoli: due regioni con lo stesso titolo inglese (kiribati-gilbert e
    kiribati-line, "Kiribati") o titoli diversi che rinviano allo stesso articolo ("Cuisine of X" -> "Caribbean cuisine")."""
    owner, dropped = {"wp": {}, "wp_en": {}}, []
    for rid in sorted(raw, key=lambda r: r not in test_regions):  # sorted e' stabile: le altre restano in ordine
        secs = raw[rid][1]
        for src in ("wp", "wp_en"):
            kept = []
            for cat, body, title in secs[src]:
                first = owner[src].setdefault(title, rid)
                if first == rid:
                    kept.append((cat, body, title))
                else:
                    dropped.append((rid, src, cat, "regione-di-test" if first in test_regions else "pagina-condivisa"))
            secs[src] = kept
        if not any(secs.values()):
            del raw[rid]
    return dropped


def drop_shared_pages(regions):
    """([(regionId, nome, titolo IT, titolo EN)] con None al posto di una pagina gia' usata da una regione precedente,
    [(regionId, lingua)] delle pagine tolte). In sft-sources.tsv alcune regioni hanno la pagina di un'altra ("Saint
    Martin" per saint-martin e sint-maarten, "Regione del Volga" per due regioni russe): le stesse sezioni entrerebbero
    due volte con nomi diversi. Senza la pagina in una lingua, la traduzione incrociata parte da quella dell'altra."""
    used, kept, dropped = {"it": set(), "en": set()}, [], []
    for rid, name, t_it, t_en in regions:
        titles = {"it": t_it, "en": t_en}
        for lang, t in titles.items():
            if t and t in used[lang]:
                titles[lang] = None
                dropped.append((rid, lang))
            elif t:
                used[lang].add(t)
        kept.append((rid, name, titles["it"], titles["en"]))
    return kept, dropped


def all_keywords(L):
    """Le radici di una lingua con quelle di Storia e Clima, che stanno a parte (WIKI_KEYWORDS)."""
    return {**L["keywords"], **L["wiki_keywords"]}


def make_answer_for(L):
    """answer_for di una lingua: frasi della sezione piu' vicine alla domanda; "" se nessuna ha una parola chiave della
    categoria o della domanda."""
    keywords, split, stop = all_keywords(L), L["split"], L["stopwords"]

    def answer_for(context, body, q, cat, name):
        words = [w for w in re.findall(r"\w+", q) if w.lower() not in stop]
        answer = it.pick_answer(context, body, " ".join(words), cat, name, keywords, split)
        stems = {w.lower()[:5] for w in words if len(w) >= 4} - {w[:5] for w in re.findall(r"\w{4,}", name.lower())}
        low = answer.lower()
        return answer if it.covers(cat, low, keywords) or any(s in low for s in stems) else ""
    return answer_for


# Storia e Clima delle citta' (--cities-db): sezioni di Wikipedia di cities.db, nel contesto come le mostra l'app. Positivo:
# la sezione giusta e 0-2 sezioni della citta'; rifiuto: la categoria manca alla citta' e il contesto ha solo sezioni che
# non la trattano; distrattore: domanda pratica con la Storia o il Clima dopo la sezione giusta (rankSections li tiene in
# coda, ma buildOnDeviceContext li include se nel contesto c'e' spazio).
WIKI_CATS = ("STORIA", "CLIMA")
WIKI_LICENSE = "CC BY-SA 4.0 (Wikipedia)"
# Categorie di cities.db -> categorie di CITY_QUESTIONS (Come arrivare e Come spostarsi sono entrambe TRASPORTI)
DB_QUESTION_CATS = {"TRASPORTI": ("ARRIVARE", "TRASPORTI"), "COSA_VEDERE": ("COSA_VEDERE",), "CIBO_BEVANDE": ("CIBO_BEVANDE",),
                    "ALLOGGIO": ("ALLOGGIO",), "SICUREZZA": ("SICUREZZA",), "CONNETTIVITA": ("CONNETTIVITA",),
                    "ACQUISTI": ("SHOPPING",)}
# Copia di historyClimateWords in TravelAssistant.kt (da cambiare insieme): con una di queste parole nella domanda le
# sezioni Storia e Clima pesano come le altre nella classifica dell'app
HISTORY_CLIMATE_WORDS = re.compile(
    r"\b(stori|fondat|fondò|secol|guerr|antic|roman[oaie]?\b|mediev|clima|temperatur|piov|piogg|neve|nevic|cald|fredd|estat|"
    r"invern|meteo|stagion|histor|found|centur|wars?\b|ancient|medieval|weather|rain|snow|hot\b|cold\b|summer|winter|season)",
    re.I)


def city_db_region(spec):
    """(regionId, file) di un argomento di --cities-db: <regionId>=<file>, oppure il solo file, la cui regione e' il nome
    fino al primo "--" (<regionId>--<versione>--cities.db, come gli asset delle release)."""
    region, sep, path = spec.partition("=")
    if sep and re.fullmatch(r"[a-z0-9-]+", region):
        return region, Path(path)
    path = Path(spec)
    if "--" not in path.name:
        raise ValueError(f"{spec}: la regione non si ricava dal nome del file, scrivere <regionId>=<file>")
    return path.name.split("--")[0], path


def load_city_sections(specs):
    """{regionId: {citta': [(categoria, corpo, sourceUrl, tradotta)]}} dai cities.db di [specs] (city_sections). I cities.db
    pubblicati prima della colonna translated hanno solo sezioni non tradotte."""
    out = {}
    for spec in specs:
        region, path = city_db_region(spec)
        with closing(sqlite3.connect(path)) as db:
            has_translated = any(col[1] == "translated" for col in db.execute("PRAGMA table_info(city_sections)"))
            for city, cat, body, url, translated in db.execute(
                    f"SELECT city, category, body, sourceUrl, {'translated' if has_translated else '0'} "
                    "FROM city_sections ORDER BY city, rowid"):
                out.setdefault(region, {}).setdefault(city, []).append((cat, body, url, bool(translated)))
    return out


def wikipedia_positive(rng, lang, name, sec, secs, ask, answer_for):
    """(categoria, contesto, domanda, risposta, sezioni usate) di una domanda di storia o clima sulla sezione [sec] di una
    citta' ([secs]: [(categoria, corpo, sourceUrl, tradotta)]), con 0-2 sezioni della citta' che non la trattano; la
    risposta sono frasi della sezione. [ask] sceglie la domanda. None se la risposta e' troppo corta."""
    cat, body = sec[0], sec[1]
    keywords = all_keywords(LANGS[lang])
    others = [s for s in secs if s is not sec and not it.covers(cat, s[1], keywords)]
    q = ask(cat, name, wiki=True)
    chosen = rng.sample(others, min(len(others), rng.choice([0, 0, 1, 1, 2])))
    context = it.make_context(rng, [body] + [s[1] for s in chosen], q, name)
    answer = answer_for(context, body, q, cat, name)
    if len(answer) < 40:  # la sezione giusta e' stata troncata: riprova da sola
        chosen = []
        context = it.make_context(rng, [body], q, name)
        answer = answer_for(context, body, q, cat, name)
    return (cat, context, q, answer, [sec, *chosen]) if len(answer) >= 40 else None


def wikipedia_distractor(rng, lang, name, secs, ask, answer_for):
    """Come wikipedia_positive, ma per una domanda pratica sulla citta' (CITY_QUESTIONS): nel contesto la sezione giusta e
    dopo la Storia o il Clima, e la risposta viene dalla prima. None senza una sezione pratica che tratta una categoria
    (di almeno CITY_MIN_SECTION caratteri), senza Storia ne' Clima, o se la domanda nomina storia o clima."""
    keywords = all_keywords(LANGS[lang])
    wiki = [s for s in secs if s[0] in WIKI_CATS]
    options = [(s, c) for s in secs for c in DB_QUESTION_CATS.get(s[0], ())
               if len(s[1]) >= it.CITY_MIN_SECTION and it.covers(c, s[1], keywords)]
    if not wiki or not options:
        return None
    right, cat = rng.choice(options)
    q = ask(cat, name, city=True)
    if HISTORY_CLIMATE_WORDS.search(q):
        return None
    tail = rng.choice(wiki)
    context = it.make_context(rng, [right[1], tail[1]], q, name, ordered=True)
    answer = answer_for(context, right[1], q, cat, name)
    if len(answer) < 40 or not any(p in context for p in tail[1].split("\n") if p.strip()):  # niente coda: non e' un distrattore
        return None
    return cat, context, q, answer, [right, tail]


def wikipedia_refusal(rng, lang, name, secs, ask, refuse):
    """Come wikipedia_positive, ma per Storia o Clima che la citta' non ha: contesto con 1-3 sezioni della citta' che non la
    trattano (in nessuna delle due lingue, la domanda puo' essere nell'altra), risposta [refuse](argomento). None se la
    citta' ha entrambe o non ha sezioni adatte."""
    L = LANGS[lang]
    keywords, other_keywords = all_keywords(L), all_keywords(LANGS[OTHER[lang]])
    missing = [c for c in WIKI_CATS if c not in {s[0] for s in secs}]
    if not missing:
        return None
    cat = rng.choice(missing)
    bodies = [s for s in secs if not it.covers(cat, s[1], keywords) and not it.covers(cat, s[1], other_keywords)]
    if not bodies:
        return None
    q = ask(cat, name, wiki=True)
    chosen = rng.sample(bodies, min(len(bodies), rng.randint(1, 3)))
    return cat, it.make_context(rng, [s[1] for s in chosen], q, name), q, refuse(L["topic"][cat]), chosen


# Numeri di emergenza (--emergency): per le domande che attivano isEmergencyQuestion in TravelAssistant.kt l'app mette in
# testa al contesto la riga dei Fatti rapidi (emergencyNumbersContext, uguale a emergencyNumbersLine della pipeline dei
# contenuti: da cambiare insieme), poi le sezioni della guida. Positivo: la risposta e' la riga; rifiuto: la regione non ha
# numeri (assente da emergency-numbers.tsv o senza numero centralizzato), nessun blocco e sezioni che non ne parlano.
EMERGENCY_TSV = it.HERE.parent / "content" / "src" / "main" / "resources" / "emergency-numbers.tsv"
# Copia di emergencyWords in TravelAssistant.kt
EMERGENCY_WORDS = re.compile(
    r"emergenz|ambulanz|polizia|pompier|vigili del fuoco|soccors|emergency|ambulance|police|fire brigade|fire department", re.I)
EMERGENCY_LABELS = {"it": ("Numeri di emergenza", "Generale", "Polizia", "Ambulanza", "Vigili del fuoco"),
                    "en": ("Emergency numbers", "General", "Police", "Ambulance", "Fire")}
EMERGENCY_FIELD = {"it": "Numeri di emergenza", "en": "Emergency numbers"}  # campo di QUICK_FACT_TOPIC e QUICK_FACT_KEYWORDS
EMERGENCY_QUESTIONS = {
    "it": {"general": ["Qual e' il numero di emergenza in {r}?", "Chi chiamo in caso di emergenza in {r}?",
                       "Mi servono i numeri di emergenza per {r}.", "Se ho un'emergenza in {r}, che numero compongo?"],
           "ambulance": ["Come chiamo un'ambulanza in {r}?", "A che numero risponde l'ambulanza in {r}?",
                         "Mi sento male: che numero chiamo per il soccorso sanitario in {r}?"],
           "police": ["Come chiamo la polizia in {r}?", "Devo denunciare un furto: che numero ha la polizia in {r}?",
                      "Se mi rubano il portafoglio in {r}, a che numero chiamo la polizia?"],
           "fire": ["Qual e' il numero dei vigili del fuoco in {r}?", "Come chiamo i pompieri in {r}?",
                    "Che numero hanno i pompieri in {r}?"]},
    "en": {"general": ["What is the emergency number in {r}?", "Who do I call in an emergency in {r}?",
                       "What number do I dial if there is an emergency in {r}?"],
          "ambulance": ["How do I call an ambulance in {r}?", "What number reaches an ambulance in {r}?",
                        "I need an ambulance: which number do I call in {r}?"],
          "police": ["How do I call the police in {r}?", "What number do I dial for the police in {r}?",
                     "I want to report a theft: which number reaches the police in {r}?"],
          "fire": ["What is the number of the fire department in {r}?", "How do I call the fire brigade in {r}?",
                   "Which number do I dial for the fire department in {r}?"]},
}


def load_emergency_numbers(path=EMERGENCY_TSV):
    """{regionId: (generale, polizia, ambulanza, vigili del fuoco)} da emergency-numbers.tsv, per le regioni con un numero
    centralizzato (polizia non vuota, come emergencyNumbersByRegion della pipeline dei contenuti); "" se manca il generale."""
    rows = [l.split("\t") for l in path.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]
    return {r[0]: tuple(r[1:5]) for r in rows if r[2]}


def emergency_line(numbers, lang):
    """La riga dei numeri di emergenza come emergencyNumbersContext in TravelAssistant.kt: "Generale" solo se c'e' un
    numero unico."""
    title, general, police, ambulance, fire = EMERGENCY_LABELS[lang]
    g, p, a, f = numbers
    parts = ([f"{general} {g}"] if g else []) + [f"{police} {p}", f"{ambulance} {a}", f"{fire} {f}"]
    return f"{title}: " + ", ".join(parts)


def emergency_example(rng, lang, line, name, bodies, L, refusal, other_lang=0.2, empty=0.1):
    """(contesto, domanda, risposta, tipo) di una domanda sui numeri di emergenza di una regione con [bodies] come sezioni.
    Con la riga [line]: in testa al contesto (come nearby_context) e la risposta e' la riga; senza (regione senza numeri):
    1-3 sezioni che non parlano di emergenze, o il contesto di fallback, e il rifiuto. Una quota [other_lang] delle
    domande e' nell'altra lingua."""
    kind = rng.choice(list(EMERGENCY_QUESTIONS[lang]))
    questions = EMERGENCY_QUESTIONS[OTHER[lang] if rng.random() < other_lang else lang][kind]
    q = rng.choice(questions).format(r=name)
    if line:
        return nearby_context(rng, line, q, name, bodies, L), q, line, "pos"
    field = EMERGENCY_FIELD[lang]
    unrelated = [b for b in bodies if not EMERGENCY_WORDS.search(b) and not any(k in b.lower() for k in L["quick_keywords"][field])]
    if not unrelated or rng.random() < empty:
        context = L["fallback"]
    else:
        context = it.make_context(rng, rng.sample(unrelated, min(len(unrelated), rng.randint(1, 3))), q, name)
    return context, q, refusal(L["quick_topic"][field]), "neg"


# Categorie con domande in stile utente (travel_questions.py) abbastanza numerose e classificate bene: nelle altre le parole chiave
# sbagliano spesso ("water" mette gli sport acquatici in SALUTE, "dress" un matrimonio in ACQUISTI) o le domande sono
# poche decine; una domanda nella categoria sbagliata insegnerebbe a rispondere con una sezione che non c'entra.
USER_STYLE_QUESTION_CATS = ("COSA_VEDERE", "ALLOGGIO", "SICUREZZA", "TRASPORTI", "USI_COSTUMI")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", choices=("it", "en"), required=True)
    ap.add_argument("--dump-dir", type=Path, required=True)
    ap.add_argument("--dump-date", help="data dei dump (MM-AAAA, AAAA-MM o AAAA-MM-GG), di default il nome della cartella")
    ap.add_argument("--negatives", type=float, default=0.33, help="negativi per positivo (0.33 = ~25%% del totale)")
    ap.add_argument("--off-topic", type=float, default=0.25, help="quota di negativi con domanda fuori tema")
    ap.add_argument("--empty", type=float, default=0.1, help="quota di negativi con il contesto di fallback")
    ap.add_argument("--other-lang", type=float, default=0.2,
                    help="quota di domande nell'altra lingua (risposta nella lingua del dataset)")
    ap.add_argument("--other-context", type=float, default=0.2, help="quota di negativi con il contesto nell'altra lingua")
    ap.add_argument("--per-section", type=int, default=3, help="domande per sezione di un paese")
    ap.add_argument("--cities", type=int, default=0, help="quante citta' usare (0 = nessuna)")
    ap.add_argument("--city-questions", type=int, default=4, help="sezioni (una domanda ciascuna) per citta'")
    ap.add_argument("--no-translate", action="store_true", help="senza traduzione incrociata (solo per confronto)")
    ap.add_argument("--guides-db", help="guides.db (it) o guides-en.db (en) pubblicato: fatti rapidi e note personali")
    ap.add_argument("--vaccinations", type=Path,
                    help="riassunti di VaccinationSummaryExport (JSONL): domande sui vaccini col riassunto nel contesto")
    ap.add_argument("--nearby", type=float, default=0,
                    help="quota del dataset finale (es. 0.03) con domande su cosa c'e' qui vicino e sulle prossime partenze")
    ap.add_argument("--distances", type=float, default=0,
                    help="quota del dataset finale (es. 0.02) con domande sulla distanza tra due citta' (richiede --cities)")
    ap.add_argument("--cities-db", nargs="+", metavar="DB",
                    help="cities.db (it) o cities-en.db (en) pubblicati, uno per regione: Storia e Clima delle citta' da Wikipedia; "
                         "la regione e' il nome del file fino al primo '--' (<regionId>--<versione>--cities.db) oppure <regionId>=<file>")
    ap.add_argument("--wikipedia-cities", type=int, default=1500, help="con --cities-db: al massimo quante citta' usare")
    ap.add_argument("--emergency", type=float, default=0,
                    help="quota del dataset finale (es. 0.01) con domande sui numeri di emergenza, la riga dell'app in testa al "
                         "contesto (da emergency-numbers.tsv) o il rifiuto per le regioni senza numeri")
    ap.add_argument("--user-style-questions", type=float, default=0.2,
                    help="quota delle domande di USER_STYLE_QUESTION_CATS presa dalle domande in stile utente di "
                         "travel_questions.py (0 = solo i modelli, come il v9)")
    ap.add_argument("--city-daily-life", action=argparse.BooleanOptionalAction, default=True,
                    help="domande VITA_QUOTIDIANA sulle sezioni Informazioni utili/Cope delle citta', anche di citta' oltre "
                         "--cities che hanno solo quella (--no-city-daily-life: come il v9)")
    ap.add_argument("--balanced-negatives", action=argparse.BooleanOptionalAction, default=True,
                    help="categoria dei rifiuti in proporzione ai positivi (paesi) o alle sezioni (citta') della categoria "
                         "(--no-balanced-negatives: stessa probabilita' per tutte, come il v9)")
    ap.add_argument("--clear-questions", action=argparse.BooleanOptionalAction, default=True,
                    help="senza le domande ambigue (AMBIGUOUS_QUESTIONS) e senza domande sui vaccini in SALUTE "
                         "(--no-clear-questions: come il v9)")
    ap.add_argument("--version", help="versione nel nome dei file di output (default: v10 con --nearby, --distances, "
                                      "--cities-db, --emergency, --user-style-questions diverso da 0, --city-daily-life, "
                                      "--balanced-negatives o --clear-questions, altrimenti v9); serve per sovrascrivere "
                                      "un v9 che esiste gia'")
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    if a.distances and not a.cities:
        ap.error("--distances richiede --cities")
    if not all(0 <= q < 1 for q in (a.distances, a.nearby, a.emergency)):  # quote del dataset finale: 1 darebbe /0
        ap.error("--distances, --nearby ed --emergency sono quote: tra 0 e 1 escluso")
    if missing := [p for _, p in map(city_db_region, a.cities_db or []) if not Path(p).is_file()]:
        ap.error(f"--cities-db: file inesistenti: {', '.join(map(str, missing))}")
    version = a.version or ("v10" if a.nearby or a.distances or a.cities_db or a.emergency or a.user_style_questions
                            or a.city_daily_life or a.balanced_negatives or a.clear_questions else "v9")
    if version == "v9" and not a.version and (it.OUT / f"pocket_travel_sft.v9.{a.lang}.jsonl").exists():
        ap.error(f"pocket_travel_sft.v9.{a.lang}.jsonl esiste gia' (lo usano i training): per sovrascriverlo dai --version v9")
    if a.city_daily_life:  # tutte e due le lingue: i rifiuti controllano anche le radici dell'altra lingua
        LANGS.update({k: with_city_daily_life(v, k) for k, v in LANGS.items()})
    if a.clear_questions:
        LANGS.update({k: with_clear_questions(v) for k, v in LANGS.items()})
    L, lang, other = LANGS[a.lang], a.lang, OTHER[a.lang]
    rng = random.Random(a.seed)
    (it.OUT / "raw").mkdir(parents=True, exist_ok=True)

    sources = it.load_sources()
    date = wiki_dump.normalize_date(a.dump_date or a.dump_dir.name)
    for src in ("it", "en", "wp") + (("wp_en",) if lang == "en" else ()):  # subito, non dopo minuti di lettura dei dump
        try:
            wiki_dump.dump_files(a.dump_dir, it.DUMP_WIKIS[src], date)
        except FileNotFoundError as e:
            sys.exit(f"dump mancante o incompleto: {e} (download-wikimedia-dumps.sh)")
    phase("dump", f"Wikivoyage IT/EN e Wikipedia IT{'/EN' if lang == 'en' else ''} del {date}")
    texts_it, redirects_it = {}, {}  # i redirect a parte: texts_it da' anche le citta', che non vanno contate due volte
    for t, x, redirect in wiki_dump.iter_pages(wiki_dump.dump_files(a.dump_dir, it.DUMP_WIKIS["it"], date)):
        if redirect:
            redirects_it[t] = redirect
        else:
            texts_it[t] = x
    pages_en, parent_en, cities_en = en.load_en_dump(wiki_dump.dump_files(a.dump_dir, it.DUMP_WIKIS["en"], date))
    wp_titles = {t for (_, src), t in sources.items() if src.startswith("wp_") and t != "-"}
    texts_wp = wiki_dump.load_titles(a.dump_dir, it.DUMP_WIKIS["wp"], date, wp_titles)  # {titolo: (titolo finale, testo)}

    def title_of(rid, src):
        if src == "en":
            return en.page_title(rid, sources)
        t = sources.get((rid, src), "-")
        return wiki_dump.norm_title(t) if t != "-" else None

    # Regioni di test e regioni che ne condividono la pagina (in una delle due lingue) o vi appartengono
    test_it = {t for r in TEST_REGIONS if (t := title_of(r, "it"))}
    test_en = {t for r in TEST_REGIONS if (t := title_of(r, "en"))}
    in_test_en = lambda t: t in test_en or bool(en.cities_en.regions_of(t, parent_en, {x: x for x in test_en}))
    regions, skipped, excluded, wiki_title_of = [], [], [], {}
    for rid, name_it, wiki_title in it.load_regions():
        wiki_title_of[rid] = wiki_title
        t_it, t_en = title_of(rid, "it"), title_of(rid, "en")
        if rid not in TEST_REGIONS and (any(rid.startswith(f"{t}-") for t in TEST_REGIONS) or t_it in test_it
                                        or (t_en and in_test_en(t_en))):
            skipped.append(rid)
            excluded.append((rid, "-", "-", "regione-di-test"))
            continue
        name_en = en.with_article(en.display_name(t_en or wiki_title))
        # "Stati Uniti - Hawaii" -> "Hawaii": nelle domande il nome che scriverebbe un utente, come in inglese
        regions.append((rid, name_it.rsplit(" - ", 1)[-1] if lang == "it" else name_en, t_it, t_en))
    print(f"regioni escluse perche' dentro una regione di test: {', '.join(skipped)}")
    regions, shared = drop_shared_pages(regions)
    excluded.extend((rid, src, "-", "pagina-condivisa") for rid, src in shared)

    # Dataset inglese: gli articoli tematici di Wikipedia EN originali (wp_en_sections), da enwiki
    texts_wp_en = {}
    if lang == "en":
        phase("wikipedia en", f"articoli tematici di {len(regions)} regioni da {it.DUMP_WIKIS['wp_en']} del {date}")
        wp_en_titles = {t for rid, *_ in regions for cat in it.WP_LANG_SUFFIX
                        for t in it.wp_candidate_titles(wiki_title_of[rid], cat)}
        texts_wp_en = wiki_dump.load_titles(a.dump_dir, it.DUMP_WIKIS["wp_en"], date, wp_en_titles)

    # Sezioni per regione: {regionId: {"it": [(cat, corpo)], "en": [...], "wp": [(cat, corpo, titolo)], "wp_en": [...]}}
    phase("fonti", f"{len(regions)} regioni")
    raw, attribution = {}, []
    progress = Progress("fonti", len(regions), "regione", every=20)
    for n, (rid, name, t_it, t_en) in enumerate(regions, 1):
        progress.update(n - 1, rid)
        secs = {"it": [], "en": [], "wp": [], "wp_en": []}
        pages = {"it": it.dump_text(texts_it, redirects_it, t_it) if t_it else None, "en": pages_en.get(t_en)}
        for src, headings in (("it", it.HEADING_TO_CATEGORY), ("en", it.EN_HEADING_TO_CATEGORY)):
            dropped = []
            if pages[src]:
                secs[src] = it.parse_sections(pages[src], headings, dropped)
            if not secs[src]:
                excluded.append((rid, src, "-", "nessuna-sezione" if pages[src] else "pagina-assente"))
            excluded.extend((rid, src, cat, "markup-residuo") for cat in dropped)
        for cat, suffix in it.WP_LANG_SUFFIX.items():
            t_wp = title_of(rid, suffix)
            # il titolo finale dopo il redirect: drop_shared_wp riconosce cosi' due titoli che portano allo stesso articolo
            final_wp, page = texts_wp.get(t_wp, (None, None)) if t_wp else (None, None)
            found = [(c, b, final_wp) for c, b in it.parse_wp(page, cat)] if page else []
            if not found:
                excluded.append((rid, suffix, cat, "nessuna-sezione" if page else "pagina-assente"))
            secs["wp"] += found
            if lang == "en":
                t_wp_en, found = wp_en_sections(texts_wp_en, wiki_title_of[rid], cat, keywords=en.WP_KEYWORDS_EN)
                if not found:
                    excluded.append((rid, "wp_en", cat, "nessuna-sezione" if t_wp_en else "pagina-assente"))
                secs["wp_en"] += [(c, b, t_wp_en) for c, b in found]
        if any(secs.values()):
            raw[rid] = (name, secs, t_it, t_en)
    excluded.extend(drop_shared_wp(raw, TEST_REGIONS))
    progress.update(len(regions), f"{len(raw)} regioni con testo")

    # Traduzione incrociata: per categoria, la sezione dell'altra lingua se quella del dataset manca o e' povera
    def by_cat(pairs):
        out = defaultdict(list)
        for cat, body in pairs:
            out[cat].append(body)
        return out
    plan = []  # (regionId, categoria, [corpi da tradurre])
    for rid, (name, secs, _, _) in raw.items():
        own, oth = by_cat(secs[lang]), by_cat(secs[other])
        for cat in set(own) | set(oth):
            if needs_translation("\n\n".join(own.get(cat, [])), "\n\n".join(oth.get(cat, []))):
                plan.append((rid, cat, oth[cat]))
        if lang == "en" and (fallback := wp_fallback(secs)):
            plan.append((rid, "wp", [b for _, b, _ in fallback]))
    translated = {}
    if plan and not a.no_translate:
        phase("traduzione", f"{len(plan)} gruppi di sezioni {other}->{lang}")
        translator = Translator(other, lang, it.OUT / "raw")
        flat = [b for _, _, bodies in plan for b in bodies]
        out = iter(translator.translate_many(flat))
        for rid, cat, bodies in plan:
            translated[(rid, cat)] = [next(out) for _ in bodies]

    # data: {regionId: (nome, [(cat, corpo, tradotto)], [(cat, corpo) nell'altra lingua])}
    data, n_tr = {}, Counter()
    # per la fonte nell'elenco delle esclusioni
    wp_bodies = {src: {b for _, secs, _, _ in raw.values() for _, b, _ in secs[src]} for src in ("wp", "wp_en")}
    source_of = lambda body, tr: ("tradotta" if tr else "wp" if body in wp_bodies["wp"]
                                  else "wp_en" if body in wp_bodies["wp_en"] else lang)
    for rid, (name, secs, t_it, t_en) in raw.items():
        own = by_cat(secs[lang])
        final, wv_own, wv_tr = [], False, False  # usate sezioni di Wikivoyage nella lingua del dataset / tradotte
        for cat in set(own) | {c for (r, c) in translated if r == rid and c != "wp"}:
            tr = translated.get((rid, cat))
            if tr and all(t is not None for t in tr):
                final += [(cat, t, True) for t in tr]; n_tr["sezioni"] += len(tr); wv_tr = True
            else:
                final += [(cat, b, False) for b in own.get(cat, [])]; wv_own = wv_own or bool(own.get(cat))
        wp_used = []  # [(titolo, tradotto)] degli articoli di Wikipedia usati, per l'attribuzione
        if lang == "it":
            final += [(c, b, False) for c, b, _ in secs["wp"]]
            wp_used += [(t_wp, False) for _, _, t_wp in secs["wp"]]
        else:  # gli articoli inglesi originali; per i temi senza, la traduzione di quelli italiani
            final += [(c, b, False) for c, b, _ in secs["wp_en"]]; n_tr["wikipedia_en"] += len(secs["wp_en"])
            wp_used += [(t_wp, False) for _, _, t_wp in secs["wp_en"]]
            for (c, _, t_wp), t in zip(wp_fallback(secs), translated.get((rid, "wp"), [])):
                if t is not None:
                    final.append((c, t, True)); n_tr["wikipedia"] += 1
                    wp_used.append((t_wp, True))
        final = [(c, b, t) for c, b, t in final if b]
        if not final:
            continue
        data[rid] = (name, sorted(final, key=lambda s: (s[0], s[1])), secs[other])
        page_lang = {"it": (t_it, "it"), "en": (t_en, "en")}
        for src in ("it", "en"):
            title, url_lang = page_lang[src]
            # la pagina dell'altra lingua anche se non tradotta: le sue sezioni fanno da contesto nei rifiuti (--other-context)
            used = wv_own if src == lang else bool(secs[other]) or wv_tr
            if title and used:
                lic = "CC BY-SA 4.0" + (f" ({L['translated']}, {MT_MODELS[(other, lang)]}, {MT_LICENSE})" if src == other and wv_tr else "")
                attribution.append((rid, name, it.SOURCE_URL[url_lang] + urllib.parse.quote(title.replace(" ", "_")), lic))
        for t_wp, tr in dict.fromkeys(wp_used):  # una riga per articolo, non per paragrafo
            lic = "CC BY-SA 4.0 (Wikipedia)" + (f" ({L['translated']}, {MT_MODELS[('it', 'en')]}, {MT_LICENSE})" if tr else "")
            url = it.SOURCE_URL["wp" if lang == "it" or tr else "wp_en"]
            attribution.append((rid, name, url + urllib.parse.quote(t_wp.replace(" ", "_")), lic))

    keywords = L["keywords"]
    covers = lambda cat, text: it.covers(cat, text, keywords)
    answer_for = make_answer_for(L)

    # Domande in stile utente (--user-style-questions): per le categorie di USER_STYLE_QUESTION_CATS una quota delle domande,
    # positive e rifiuti, viene da travel_questions.py invece che dai modelli; senza rete o traduttore restano i modelli.
    user_style, user_style_uses = {}, Counter()
    if a.user_style_questions:
        phase("domande in stile utente", travel_questions.DATASET)
        try:
            # tradotte in italiano con MarianMT; con --no-translate le originali inglesi, come le domande nell'altra lingua
            user_style = travel_questions.load(it.OUT / "raw", "en" if a.no_translate else lang, en.KEYWORDS)
        except Exception as e:  # senza, lo stesso seme darebbe un altro file con lo stesso nome
            sys.exit(f"domande in stile utente non disponibili ({e}): riprova con la rete, o usa --user-style-questions 0")
        user_style = {c: qs for c, qs in user_style.items() if c in USER_STYLE_QUESTION_CATS and qs}
        if not user_style:
            sys.exit(f"nessuna domanda in stile utente da {travel_questions.DATASET}: controlla la cache in raw/, "
                     "o usa --user-style-questions 0")
        if user_style:
            attribution.extend(travel_questions.ATTRIBUTION)
        print("domande in stile utente per categoria:", {c: len(qs) for c, qs in user_style.items()})

    def question(cat, name, city=False, wiki=False):
        if not wiki and cat in user_style and rng.random() < a.user_style_questions:
            user_style_uses[cat] += 1
            return rng.choice(user_style[cat])
        own, oth =((L["wiki_questions"], L["other_wiki_questions"]) if wiki
                    else (L["city_questions"], L["other_city_questions"]) if city else (L["questions"], L["other_questions"]))
        pool = oth.get(cat) if rng.random() < a.other_lang else None
        return rng.choice(pool or own[cat]).format(r=name)

    off_topic_pool = L["off_topic_train"] + [q for qs in it.fetch_off_topic(L["off_topic_sources"], keywords, L["travel_stems"]).values()
                                             for q in qs]
    off_topic_uses = Counter()

    def off_topic_question():
        free = [q for q in off_topic_pool if off_topic_uses[q] < it.OFF_TOPIC_MAX_USES]
        q = rng.choice(free or off_topic_pool)
        off_topic_uses[q] += 1
        return q

    def row(kind, rid, cat, context, q, ans, tr=False):
        return {"messages": [{"role": "user", "content": L["prompt"](context, q)}, {"role": "assistant", "content": ans}],
                "kind": kind, "region": rid, "category": cat, "translated": tr}

    refusal = lambda topic: L["refusal"](topic, rng.choice(L["tails"]))
    phase("righe", "positivi, poi negativi")
    rows, pos, seen = [], 0, set()
    for rid, (name, secs, _) in data.items():
        for cat, body, tr in secs:
            if cat not in L["questions"] or not covers(cat, body):
                excluded.append((rid, source_of(body, tr), cat, "fuori-categoria"))
                continue
            pos_before = pos
            others = [b for c, b, _ in secs if c != cat and not covers(cat, b)]
            for _ in range(a.per_section):
                q = question(cat, name)
                context = it.make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 0, 1, 1, 2]))), q, name)
                answer = answer_for(context, body, q, cat, name)
                if len(answer) < 40:  # la sezione giusta e' stata troncata: riprova da sola
                    context = it.make_context(rng, [body], q, name)
                    answer = answer_for(context, body, q, cat, name)
                if len(answer) < 40 or (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, cat, context, q, answer, tr)); pos += 1
            if pos == pos_before:
                excluded.append((rid, source_of(body, tr), cat, "nessuna-risposta"))
    n_neg, ids, tries, neg = int(pos * a.negatives), list(data), 0, 0
    country_pos = Counter(r["category"] for r in rows)  # qui solo i positivi dei paesi
    while neg < n_neg and tries < n_neg * 20:
        tries += 1
        rid = rng.choice(ids)
        name, secs, other_secs = data[rid]
        bodies_all = [(c, b) for c, b, _ in secs]
        if other_secs and rng.random() < a.other_context:
            bodies_all = other_secs  # contesto nell'altra lingua, risposta nella lingua del dataset
        x = rng.random()
        if x < a.off_topic:
            q, cat, ans = off_topic_question(), "OFF", refusal(L["off_topic_topic"])
            context = it.make_context(rng, rng.sample([b for _, b in bodies_all], min(len(bodies_all), rng.randint(1, 3))), q, name)
        else:
            cats = list(L["questions"])
            cat = weighted_category(rng, cats, country_pos) if a.balanced_negatives else rng.choice(cats)
            q, ans = question(cat, name), refusal(L["topic"][cat])
            if x < a.off_topic + a.empty:
                context = L["fallback"]
            else:
                bodies = [b for c, b in bodies_all if c != cat and not covers(cat, b) and not it.covers(cat, b, LANGS[other]["keywords"])]
                if not bodies:
                    continue
                context = it.make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))), q, name)
        if (context, q) in seen:
            continue
        seen.add((context, q))
        rows.append(row("neg", rid, cat, context, q, ans)); neg += 1

    # Citta' della lingua del dataset, in ordine casuale fino a --cities con sezioni utili
    city_pos = city_neg = 0
    if a.cities:
        phase("citta'", f"al massimo {a.cities} citta'")
        candidates = []
        if lang == "it":
            region_titles = {t for _, _, t, _ in regions} | test_it
            for title, text in sorted(texts_it.items()):
                parents = it.city_parents(text)
                if parents is not None and title not in region_titles and not parents & test_it:
                    candidates.append((title, text))
        else:
            region_titles = {t for *_, t in regions} | test_en
            candidates = [(t, pages_en[t]) for t in sorted(cities_en) if t not in region_titles and not in_test_en(t)]
        rng.shuffle(candidates)
        cities, daily_life_only = [], []  # oltre --cities, le citta' con VITA_QUOTIDIANA: solo quella domanda
        for title, text in candidates:
            full = len(cities) >= a.cities
            if full and not a.city_daily_life:
                break
            secs = [(c, b) for c, b in it.parse_sections(text, L["city_headings"]) if covers(c, b) and len(b) >= it.CITY_MIN_SECTION]
            if secs and not full:
                cities.append((title, secs))
            elif secs and (daily := [s for s in secs if s[0] == DAILY_LIFE]):
                daily_life_only.append((title, secs, daily))
        print(f"citta' {len(cities)}, piu' {len(daily_life_only)} solo per {DAILY_LIFE}")
        city_cats, city_secs = Counter(), Counter(c for _, secs in cities for c, _ in secs)
        for title, secs, *daily in cities + daily_life_only:
            chosen = daily[0] if daily else sorted(secs, key=lambda s: (city_cats[s[0]], rng.random()))[:a.city_questions]
            city_cats.update(c for c, _ in chosen)
            rid, name = f"citta:{title}", en.display_name(title)
            attribution.append((rid, name, it.SOURCE_URL[lang] + urllib.parse.quote(title.replace(" ", "_")), "CC BY-SA 4.0"))
            for cat, body in chosen:
                q = question(cat, name, city=True)
                others = [b for c, b in secs if c != cat and not covers(cat, b)]
                context = it.make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
                answer = answer_for(context, body, q, cat, name)
                if len(answer) < 40 or (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, cat, context, q, answer)); city_pos += 1
            missing = [c for c in L["city_questions"] if c not in {c for c, _ in secs}]
            if missing and not daily and rng.random() < a.negatives * 2:  # ~1 rifiuto ogni 2-3 domande della citta'
                cat = weighted_category(rng, missing, city_secs) if a.balanced_negatives else rng.choice(missing)
                q = question(cat, name, city=True)
                bodies = [b for _, b in secs]
                if a.city_daily_life:  # come nei paesi: niente sezioni che trattano la categoria (le radici di VITA_QUOTIDIANA
                    # delle citta', "ospedal", "uffici postal", stanno anche in Salute o Come restare in contatto)
                    bodies = [b for b in bodies if not covers(cat, b) and not it.covers(cat, b, LANGS[other]["keywords"])]
                context = bodies and it.make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))), q, name)
                if context and (context, q) not in seen:
                    seen.add((context, q))
                    rows.append(row("neg", rid, cat, context, q, refusal(L["topic"][cat]))); city_neg += 1

    # Fatti rapidi e note personali (--guides-db), fuori le regioni di test
    quick_pos = quick_neg = note_pos = 0
    if a.guides_db:
        phase("fatti rapidi", a.guides_db)
        qq, qk = L["quick_questions"], L["quick_keywords"]
        for rid, (qf_body, fields) in sorted(it.load_quick_facts(a.guides_db, qq).items()):
            if rid not in data or rid in TEST_REGIONS:
                continue
            name, secs, _ = data[rid]
            others = [b for _, b, _ in secs]
            for field, line in fields.items():
                for q in rng.sample(qq[field], 2):
                    q = q.format(r=name)
                    context = it.make_context(rng, [qf_body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
                    if line not in context or (context, q) in seen:
                        continue
                    seen.add((context, q))
                    rows.append(row("pos", rid, "FATTI_RAPIDI", context, q, line)); quick_pos += 1
                unrelated = [b for b in others if not any(k in b.lower() for k in qk[field])]
                if unrelated and rng.random() < a.negatives * 2:
                    q = rng.choice(qq[field]).format(r=name)
                    context = it.make_context(rng, rng.sample(unrelated, min(len(unrelated), rng.randint(1, 3))), q, name)
                    if (context, q) not in seen:
                        seen.add((context, q))
                        rows.append(row("neg", rid, "FATTI_RAPIDI", context, q, refusal(L["quick_topic"][field]))); quick_neg += 1
        note_regions = [rid for rid in data if rid not in TEST_REGIONS]
        for title, body, questions, answer in L["notes"]:
            for rid in rng.sample(note_regions, min(len(note_regions), 8)):
                secs = [b for _, b, _ in data[rid][1]]
                q = rng.choice(questions)
                note = f"{L['note_label']}: {title}\n{body}"  # etichetta di buildOnDeviceContext
                sections = it.make_context(rng, rng.sample(secs, min(len(secs), rng.choice([0, 1, 2]))), q, data[rid][0],
                                           max_chars=it.MAX_CONTEXT - len(note) - 2)
                context = "\n\n".join(x for x in (sections, note) if x)
                if (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, "NOTE", context, q, answer)); note_pos += 1
    # Vaccinazioni: riassunto del percorso (dalla nazionalita' al paese della regione, come nell'app) piu' 0-2 sezioni
    # della guida; rifiuto per la stessa domanda quando il contesto ha solo sezioni che non parlano di vaccini.
    vacc_pos = vacc_neg = 0
    if a.vaccinations:
        phase("vaccinazioni", str(a.vaccinations))
        by_flag = flag_regions()
        summaries = [json.loads(l) for l in a.vaccinations.read_text(encoding="utf-8").splitlines() if l.strip()]
        if any("Consigliate per la destinazione:" in s["text"] for s in summaries):
            sys.exit(f"{a.vaccinations} e' esportato prima di \"Raccomandate per la destinazione:\": riesportalo con VaccinationSummaryExport")
        vq = VACC_QUESTIONS[lang]
        for s in (s for s in summaries if s["language"] == lang):
            rids = [r for r in by_flag.get(s["destination"], []) if r in data and r not in TEST_REGIONS]
            if not rids:
                continue
            rid = rng.choice(rids)
            name, secs, _ = data[rid]
            others = [b for _, b, _ in secs]
            for kind, answer in vaccination_answers(s["text"], lang).items():
                q = rng.choice(vq[kind]).format(r=name)
                context = it.make_context(rng, [s["text"]] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
                if s["text"] not in context or (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row("pos", rid, "VACCINAZIONI", context, q, answer)); vacc_pos += 1
            unrelated = [b for b in others if not VACC_WORDS.search(b)]
            if unrelated and rng.random() < a.negatives * 2:
                q = rng.choice(vq["any"]).format(r=name)
                context = it.make_context(rng, rng.sample(unrelated, min(len(unrelated), rng.randint(1, 3))), q, name)
                if (context, q) not in seen:
                    seen.add((context, q))
                    rows.append(row("neg", rid, "VACCINAZIONI", context, q, refusal(VACC_TOPIC[lang]))); vacc_neg += 1
        if vacc_pos + vacc_neg:
            attribution.extend(VACC_ATTRIBUTION)
    # Storia e Clima delle citta' da Wikipedia (--cities-db), fuori le regioni di test e quelle che ne condividono la pagina:
    # una domanda per sezione, un distrattore per citta' e, come per le altre citta', un rifiuto ogni 2-3 citta'
    wiki = Counter()
    if a.cities_db:
        phase("storia e clima", f"al massimo {a.wikipedia_cities} citta'")
        wiki_cities = []
        for region, cities_of in sorted(load_city_sections(a.cities_db).items()):
            if region in TEST_REGIONS or region in skipped or any(region.startswith(f"{t}-") for t in TEST_REGIONS):
                excluded.append((region, "wp-citta", "-", "regione-di-test"))
                continue
            wiki_cities += [(city, secs) for city, secs in sorted(cities_of.items()) if any(s[0] in WIKI_CATS for s in secs)]
        rng.shuffle(wiki_cities)
        wiki_cities = wiki_cities[:a.wikipedia_cities]
        for city, secs in wiki_cities:
            rid, name = f"citta:{city}", en.display_name(city)
            examples = []
            for sec in (s for s in secs if s[0] in WIKI_CATS):
                if not it.covers(sec[0], sec[1], all_keywords(L)):
                    excluded.append((rid, "wp-citta", sec[0], "fuori-categoria"))
                elif example := wikipedia_positive(rng, lang, name, sec, secs, question, answer_for):
                    examples.append(("pos", "pos", example))
                else:
                    excluded.append((rid, "wp-citta", sec[0], "nessuna-risposta"))
            if example := wikipedia_distractor(rng, lang, name, secs, question, answer_for):
                examples.append(("pos", "distrattori", example))
            if rng.random() < a.negatives * 2 and (example := wikipedia_refusal(rng, lang, name, secs, question, refusal)):
                examples.append(("neg", "neg", example))
            used = {}
            for kind, label, (cat, context, q, answer, sections) in examples:
                if (context, q) in seen:
                    continue
                seen.add((context, q))
                rows.append(row(kind, rid, cat, context, q, answer, any(s[3] for s in sections))); wiki[label] += 1
                used.update({s[2]: s[3] for s in sections})
            for url, tr in used.items():
                lic = (WIKI_LICENSE if "wikipedia.org" in url else "CC BY-SA 4.0") + (
                    f" ({L['translated']}, {MT_MODELS[(other, lang)]}, {MT_LICENSE})" if tr else "")
                attribution.append((rid, name, url, lic))
        print(f"storia e clima: {len(wiki_cities)} citta', positivi {wiki['pos']}, con distrattore {wiki['distrattori']}, "
              f"rifiuti {wiki['neg']}")
    # Distanze tra citta' (--distances): meta' coppie che una guida collega con km o tempi, meta' coppie che si nominano
    # senza; con le coordinate di Wikidata parte degli esempi ha davanti la distanza calcolata come nell'app (il tipo
    # finale lo decide il contesto, vedi distance_example)
    dist = Counter()
    if a.distances:
        target = round(len(rows) * a.distances / (1 - a.distances))
        phase("distanze", f"{target} righe")
        by_name = {en.display_name(t): (t, secs) for t, secs in cities}
        secs_by_name = {n: secs for n, (_, secs) in by_name.items()}
        pairs = distance_pairs(secs_by_name, L["split"])
        named = {n for pair in pairs for n in pair}
        try:
            found = cached_coordinates({by_name[n][0] for n in named}, lang, it.OUT / "raw" / f"coordinates.{lang}.json")
        except Exception as e:  # senza, lo stesso seme darebbe un altro file con lo stesso nome
            sys.exit(f"coordinate da Wikidata non disponibili ({e}): riprova con la rete")
        coords = {n: found[by_name[n][0]] for n in named if by_name[n][0] in found}
        pools = [[p for p, figure in sorted(pairs.items()) if figure], [p for p, figure in sorted(pairs.items()) if not figure]]
        for pool in pools:
            rng.shuffle(pool)
        for city, other_city in (p for pair in zip(*pools) for p in pair):  # non `other`: e' la lingua, stampata alla fine
            if sum(dist[k] for k in ("pos", "neg")) >= target:
                break
            example = distance_example(rng, city, other_city, secs_by_name, lang, refusal, L["split"], coords)
            if example is None or (example[0], example[1]) in seen:
                continue
            context, q, ans, kind, computed = example
            seen.add((context, q))
            rows.append(row(kind, f"citta:{by_name[city][0]}", "DISTANZE", context, q, ans)); dist[kind] += 1
            dist["blocco"] += computed
        print(f"distanze: coordinate per {len(coords)}/{len(named)} citta', {dist['blocco']} esempi con la distanza calcolata")
        if (made := dist["pos"] + dist["neg"]) < target:  # meta' e meta': il gruppo piu' piccolo limita il totale
            print(f"ATTENZIONE: distanze {made} righe invece di {target}: coppie con km o tempi {len(pools[0])}, "
                  f"senza {len(pools[1])}; servono piu' citta' (--cities) o un --distances piu' basso", file=sys.stderr)
    # Qui vicino e prossime partenze (--nearby): meta' e meta', con le sezioni di una regione qualunque dopo il blocco
    near = Counter()
    if a.nearby:
        target = round(len(rows) * a.nearby / (1 - a.nearby))
        phase("qui vicino", f"{target} righe")
        rids, tries = [r for r in data if r not in TEST_REGIONS], 0
        while sum(near.values()) < target and tries < target * 5:
            tries += 1
            cat = ("VICINO", "PARTENZE")[tries % 2]
            make = sft_nearby.poi_example if cat == "VICINO" else sft_nearby.transit_example
            block, q, ans, kind = make(rng, lang, refusal)
            rid = rng.choice(rids)
            name, secs, _ = data[rid]
            context = nearby_context(rng, block, q, name, [b for _, b, _ in secs], L, cat == "PARTENZE", a.empty)
            if (context, q) in seen:
                continue
            seen.add((context, q))
            rows.append(row(kind, rid, cat, context, q, ans)); near[cat, kind] += 1
    # Numeri di emergenza (--emergency): le regioni con numeri danno positivi, le altre rifiuti nella stessa proporzione dei
    # negativi del resto del dataset
    emergency = Counter()
    if a.emergency:
        numbers = load_emergency_numbers()
        target = round(len(rows) * a.emergency / (1 - a.emergency))
        phase("emergenze", f"{target} righe")
        rids = [r for r in data if r not in TEST_REGIONS]
        pools = {"pos": [r for r in rids if r in numbers], "neg": [r for r in rids if r not in numbers]}
        neg_share = a.negatives / (1 + a.negatives) if pools["neg"] else 0
        tries = 0
        while sum(emergency.values()) < target and tries < target * 5 and pools["pos"]:
            tries += 1
            rid = rng.choice(pools["neg" if rng.random() < neg_share else "pos"])
            name, secs, _ = data[rid]
            line = emergency_line(numbers[rid], lang) if rid in numbers else None
            context, q, ans, kind = emergency_example(rng, lang, line, name, [b for _, b, _ in secs], L, refusal, a.other_lang, a.empty)
            if (context, q) in seen:
                continue
            seen.add((context, q))
            rows.append(row(kind, rid, "EMERGENZE", context, q, ans)); emergency[kind] += 1
        if emergency:
            attribution.append(("-", "numeri di emergenza", "https://github.com/miracle091/pocket-travel/blob/main/tools/data-pipeline/"
                                "content/src/main/resources/emergency-numbers.tsv",
                                "fonti per riga nel file: Travel.gc.ca e gov.uk (Open Government Licence), Wikipedia e Wikivoyage (CC BY-SA 4.0), Wikidata (CC0)"))
        print(f"emergenze: {len(pools['pos'])} regioni con numeri, {len(pools['neg'])} senza; positivi {emergency['pos']}, rifiuti {emergency['neg']}")
    capped = cap_repeats(rows, L["prompt"])
    print(f"stessa domanda e risposta oltre {SAME_ANSWER_MAX} volte: tolte {len(rows) - len(capped)} righe")
    rows = capped
    rng.shuffle(rows)

    for name, (dataset, *_, lic, _) in L["off_topic_sources"].items():  # solo domande, con rifiuto come risposta
        attribution.append(("-", f"off-topic ({name})", f"https://huggingface.co/datasets/{dataset}/tree/{it.OFF_TOPIC_REVISIONS[dataset]}", lic))
    for src in ("it", "en", "wp") + (("wp_en",) if lang == "en" else ()):  # la data del dump resta accanto al dataset
        wiki = it.DUMP_WIKIS[src]
        attribution.append(("-", f"dump {wiki} del {date}",wiki_dump.export_url(wiki, date),
                            "CC BY-SA 4.0 (testo delle pagine elencate sopra)"))
    data_out, attr_out = it.OUT / f"pocket_travel_sft.{version}.{lang}.jsonl", it.OUT / f"ATTRIBUTION.{version}.{lang}.tsv"
    with open(data_out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(attr_out, "w", encoding="utf-8") as f:
        f.write("regionId\tdisplayName\tsourceUrl\tlicense\n")
        for rid, name, url, lic in attribution:
            f.write(f"{rid}\t{name}\t{url}\t{lic}\n")
    print(f"regioni con testo: {len(data)}; sezioni tradotte {other}->{lang}: {n_tr['sezioni']}, "
          f"paragrafi Wikipedia tradotti: {n_tr['wikipedia']}, originali di Wikipedia EN: {n_tr['wikipedia_en']}")
    print(f"positivi={pos} negativi={neg} citta' positivi={city_pos} negativi={city_neg} "
          f"fatti rapidi {quick_pos}/{quick_neg} note {note_pos} vaccinazioni {vacc_pos}/{vacc_neg} "
          f"distanze {dist['pos']}/{dist['neg']} vicino {near['VICINO', 'pos']}/{near['VICINO', 'neg']} partenze {near['PARTENZE', 'pos']}/{near['PARTENZE', 'neg']} totale={len(rows)} "
          f"(rifiuti {sum(r['kind'] == 'neg' for r in rows) / max(len(rows), 1):.1%}, tradotte {sum(r['translated'] for r in rows)})")
    cats, refs = Counter(r["category"] for r in rows), Counter(r["category"] for r in rows if r["kind"] == "neg")
    print("per categoria (righe/rifiuti):", {c: f"{n}/{refs[c]}" for c, n in cats.most_common()})
    if user_style:
        print("domande in stile utente usate (tra positivi, rifiuti e righe scartate):", dict(user_style_uses))
    print(f"scritto {data_out}")
    it.write_excluded(it.OUT / f"EXCLUDED.{version}.{lang}.tsv", excluded)


if __name__ == "__main__":
    main()
