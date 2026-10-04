#!/usr/bin/env python3
"""Genera il dataset SFT v9 (v10 con --nearby e --distances) in italiano o in inglese con lo stesso metodo, cosi' i due dataset restano equivalenti:
stesse fonti (Wikivoyage IT ed EN dello stesso dump, Wikipedia IT per le categorie deboli, fatti rapidi e note), stessa
composizione per categoria, stessi tipi di domanda, stesso rapporto di rifiuti. Cambiano solo le tabelle della lingua
(domande, parole chiave, rifiuto, prompt dell'app), prese da generate_sft_dataset.py (italiano) e
generate_sft_dataset_en.py (inglese), che restano per rifare i dataset v8.

Traduzione incrociata (translate_dataset.py): per ogni regione e categoria, se la sezione nella lingua del dataset
manca o e' molto piu' povera di quella nell'altra lingua (needs_translation), si usa la sezione dell'altra lingua
tradotta con MarianMT; lo stesso per i paragrafi di Wikipedia IT nel dataset inglese. Le righe con una sezione tradotta
hanno "translated": true e ATTRIBUTION indica la traduzione automatica (CC BY-SA 4.0, opera derivata).

- positivi: sezione giusta + 0-2 sezioni distraenti, risposta = frasi della sezione (estrattivo); una risposta senza
  parole chiave della categoria o della domanda si scarta;
- negativi: categoria qualsiasi con le sole sezioni di altre categorie che non la trattano (bilanciati tra le
  categorie), domanda fuori tema, contesto di fallback dell'app; una quota con il contesto nell'altra lingua;
- citta': --cities pagine della lingua del dataset, fino a --city-questions sezioni per citta';
- fatti rapidi e note personali con --guides-db (guides.db per l'italiano, guides-en.db per l'inglese);
- con --nearby, domande su cosa c'e' qui vicino e sulle prossime partenze con i blocchi di contesto dell'app
  (sft_nearby.py, dati sintetici), per una quota del dataset finale;
- con --distances (e --cities), domande sulla distanza tra due citta' con le sezioni di entrambe nel contesto: la
  frase della guida con i km o il tempo di viaggio, o il rifiuto quando le guide non li riportano.
Fuori dal training le regioni di test e quelle la cui pagina (IT o EN) e' la pagina di una regione di test o vi
appartiene (es. figi-occidentali ha la pagina "Figi"/"Fiji" di figi-lau): restano nel file solo le regioni di test.

Uso: python generate_sft.py --lang it|en --dump-dir <cartella dei dump> [--cities 4500] [--guides-db <db>] [--nearby 0.03]
     [--distances 0.02] [--version v10] [--seed 42]
Output in data/sft/: pocket_travel_sft.<versione>.<lang>.jsonl e ATTRIBUTION.<versione>.<lang>.tsv; traduzioni in cache in
raw/translations.<src>-<tgt>.jsonl. La versione di default e' v9 senza --nearby e --distances (l'output del v9), altrimenti v10,
cosi' un dataset con gli esempi nuovi non sovrascrive mai il v9.
"""
import argparse
import json
import math
import random
import re
import urllib.parse
from collections import Counter, defaultdict
from pathlib import Path

import city_population
import generate_sft_dataset as it
import generate_sft_dataset_en as en
import sft_nearby
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
        stopwords=IT_STOPWORDS, translated="tradotta automaticamente dall'inglese"),
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
        stopwords=en.STOPWORDS, translated="machine-translated from Italian"),
}
OTHER = {"it": "en", "en": "it"}

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
VACC_HEADERS = {"it": ("Certificati richiesti:", ("Consigliate per la destinazione:", "Da valutare con il medico"), "Verifica sempre"),
                "en": ("Required certificates:", ("Recommended for the destination:", "To discuss with a doctor"), "Always check")}
VACC_YF = {"it": "Febbre gialla", "en": "Yellow fever"}
# Stesse parole di isVaccinationQuestion in TravelAssistant.kt: una sezione che le contiene parla di vaccini
VACC_WORDS = re.compile(r"vaccin|febbre gialla|yellow fever|polio|meningococc|meningitis|profilassi|certificat|hajj|umrah", re.I)


def vaccination_answers(text, lang):
    """{tipo di domanda: risposta} estratti dal riassunto (righe intere, come le risposte estrattive delle guide):
    certificati o la riga "nessun certificato nei nostri dati", poi i vaccini consigliati (o da valutare col medico),
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
    out = {"any": " ".join(x for x in (required, rec, check) if x)}
    if rec:
        out["rec"] = f"{rec} {check}"
    yf = next((sentence(l) for l in lines if l.startswith("- " + VACC_YF[lang])), None)
    if yf or required_head not in lines:
        # febbre gialla non richiesta ma consigliata (paese a rischio): anche la riga dei consigliati
        rec_yf = rec if not yf and rec and VACC_YF[lang] in rec else None
        out["yf"] = " ".join(x for x in (yf or required, rec_yf, check) if x)
    return out


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", choices=("it", "en"), required=True)
    ap.add_argument("--dump-dir", type=Path, required=True)
    ap.add_argument("--dump-date", help="data dei dump (AAAAMMGG), di default il nome della cartella")
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
    ap.add_argument("--version", help="versione nel nome dei file di output (default: v10 con --nearby o --distances, "
                                      "altrimenti v9)")
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    if a.distances and not a.cities:
        ap.error("--distances richiede --cities")
    version = a.version or ("v10" if a.nearby or a.distances else "v9")
    L, lang, other = LANGS[a.lang], a.lang, OTHER[a.lang]
    rng = random.Random(a.seed)
    (it.OUT / "raw").mkdir(parents=True, exist_ok=True)

    sources = it.load_sources()
    date = a.dump_date or a.dump_dir.name
    phase("dump", f"Wikivoyage IT/EN e Wikipedia IT del {date}")
    texts_it = {t: x for t, x, redirect in wiki_dump.iter_pages(a.dump_dir / it.DUMP_FILES["it"].format(d=date)) if not redirect}
    pages_en, parent_en, cities_en = en.load_en_dump(a.dump_dir / it.DUMP_FILES["en"].format(d=date))
    wp_titles = {t for (_, src), t in sources.items() if src.startswith("wp_") and t != "-"}
    texts_wp = wiki_dump.load_multistream(a.dump_dir / it.DUMP_FILES["wp"].format(d=date),
                                          a.dump_dir / it.DUMP_FILES["wp_index"].format(d=date), wp_titles)

    def title_of(rid, src):
        if src == "en":
            return en.page_title(rid, sources)
        t = sources.get((rid, src), "-")
        return wiki_dump.norm_title(t) if t != "-" else None

    # Regioni di test e regioni che ne condividono la pagina (in una delle due lingue) o vi appartengono
    test_it = {t for r in TEST_REGIONS if (t := title_of(r, "it"))}
    test_en = {t for r in TEST_REGIONS if (t := title_of(r, "en"))}
    in_test_en = lambda t: t in test_en or bool(en.cities_en.regions_of(t, parent_en, {x: x for x in test_en}))
    regions, skipped = [], []
    for rid, name_it, wiki_title in it.load_regions():
        t_it, t_en = title_of(rid, "it"), title_of(rid, "en")
        if rid not in TEST_REGIONS and (any(rid.startswith(f"{t}-") for t in TEST_REGIONS) or t_it in test_it
                                        or (t_en and in_test_en(t_en))):
            skipped.append(rid)
            continue
        name_en = en.with_article(en.display_name(t_en or wiki_title))
        regions.append((rid, name_it if lang == "it" else name_en, t_it, t_en))
    print(f"regioni escluse perche' dentro una regione di test: {', '.join(skipped)}")

    # Sezioni per regione nelle due lingue: {regionId: {"it": [(cat, corpo)], "en": [...], "wp": [...]}}
    phase("fonti", f"{len(regions)} regioni")
    raw, attribution = {}, []
    progress = Progress("fonti", len(regions), "regione", every=20)
    for n, (rid, name, t_it, t_en) in enumerate(regions, 1):
        progress.update(n - 1, rid)
        secs = {"it": [], "en": [], "wp": []}
        if t_it and t_it in texts_it:
            secs["it"] = it.parse_sections(texts_it[t_it], it.HEADING_TO_CATEGORY)
        if t_en and t_en in pages_en:
            secs["en"] = it.parse_sections(pages_en[t_en], it.EN_HEADING_TO_CATEGORY)
        for cat, suffix in it.WP_LANG_SUFFIX.items():
            t_wp = title_of(rid, suffix)
            if t_wp and (page := texts_wp.get(t_wp)):
                secs["wp"] += [(c, b, t_wp) for c, b in it.parse_wp_it(page, cat)]
        if any(secs.values()):
            raw[rid] = (name, secs, t_it, t_en)
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
        if lang == "en" and secs["wp"]:
            plan.append((rid, "wp", [b for _, b, _ in secs["wp"]]))
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
    for rid, (name, secs, t_it, t_en) in raw.items():
        own = by_cat(secs[lang])
        final = []
        for cat in set(own) | {c for (r, c) in translated if r == rid and c != "wp"}:
            tr = translated.get((rid, cat))
            if tr and all(t is not None for t in tr):
                final += [(cat, t, True) for t in tr]; n_tr["sezioni"] += len(tr)
            else:
                final += [(cat, b, False) for b in own.get(cat, [])]
        if lang == "it":
            final += [(c, b, False) for c, b, _ in secs["wp"]]
        else:
            for (c, _, _), t in zip(secs["wp"], translated.get((rid, "wp"), [])):
                if t is not None:
                    final.append((c, t, True)); n_tr["wikipedia"] += 1
        final = [(c, b, t) for c, b, t in final if b]
        if not final:
            continue
        data[rid] = (name, sorted(final, key=lambda s: (s[0], s[1])), secs[other])
        page_lang = {"it": (t_it, "it"), "en": (t_en, "en")}
        for src in ("it", "en"):
            title, url_lang = page_lang[src]
            used = (src == lang and any(not t for c, b, t in final)) or (src == other and any(t for c, b, t in final))
            if title and used:
                lic = "CC BY-SA 4.0" + ("" if src == lang else f" ({L['translated']}, {MT_MODELS[(other, lang)]}, {MT_LICENSE})")
                attribution.append((rid, name, it.SOURCE_URL[url_lang] + urllib.parse.quote(title.replace(" ", "_")), lic))
        for _, _, t_wp in secs["wp"]:
            lic = "CC BY-SA 4.0 (Wikipedia)" + ("" if lang == "it" else f" ({L['translated']}, {MT_MODELS[('it', 'en')]}, {MT_LICENSE})")
            attribution.append((rid, name, it.SOURCE_URL["wp"] + urllib.parse.quote(t_wp.replace(" ", "_")), lic))

    keywords, split, stop = L["keywords"], L["split"], L["stopwords"]
    covers = lambda cat, text: it.covers(cat, text, keywords)

    def answer_for(context, body, q, cat, name):
        """Frasi della sezione piu' vicine alla domanda; "" se nessuna ha una parola chiave della categoria o della domanda."""
        words = [w for w in re.findall(r"\w+", q) if w.lower() not in stop]
        answer = it.pick_answer(context, body, " ".join(words), cat, name, keywords, split)
        stems = {w.lower()[:5] for w in words if len(w) >= 4} - {w[:5] for w in re.findall(r"\w{4,}", name.lower())}
        low = answer.lower()
        return answer if any(k in low for k in keywords[cat]) or any(s in low for s in stems) else ""

    def question(cat, name, city=False):
        own, oth = (L["city_questions"], L["other_city_questions"]) if city else (L["questions"], L["other_questions"])
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
                continue
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
    n_neg, ids, tries, neg = int(pos * a.negatives), list(data), 0, 0
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
            cat = rng.choice(list(L["questions"]))
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
        cities = []
        for title, text in candidates:
            if len(cities) >= a.cities:
                break
            secs = [(c, b) for c, b in it.parse_sections(text, L["city_headings"]) if covers(c, b) and len(b) >= it.CITY_MIN_SECTION]
            if secs:
                cities.append((title, secs))
        city_cats = Counter()
        for title, secs in cities:
            chosen = sorted(secs, key=lambda s: (city_cats[s[0]], rng.random()))[:a.city_questions]
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
            if missing and rng.random() < a.negatives * 2:  # ~1 rifiuto ogni 2-3 domande della citta'
                cat = rng.choice(missing)
                q = question(cat, name, city=True)
                context = it.make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))), q, name)
                if (context, q) not in seen:
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
            found = city_population.wikidata_coordinates({by_name[n][0] for n in named}, lang)
        except Exception as e:  # senza rete restano gli esempi senza blocco: guide o rifiuto
            print(f"-- coordinate da Wikidata non disponibili ({e})")
            found = {}
        coords = {n: found[by_name[n][0]] for n in named if by_name[n][0] in found}
        pools = [[p for p, figure in sorted(pairs.items()) if figure], [p for p, figure in sorted(pairs.items()) if not figure]]
        for pool in pools:
            rng.shuffle(pool)
        for city, other in (p for pair in zip(*pools) for p in pair):
            if sum(dist[k] for k in ("pos", "neg")) >= target:
                break
            example = distance_example(rng, city, other, secs_by_name, lang, refusal, L["split"], coords)
            if example is None or (example[0], example[1]) in seen:
                continue
            context, q, ans, kind, computed = example
            seen.add((context, q))
            rows.append(row(kind, f"citta:{by_name[city][0]}", "DISTANZE", context, q, ans)); dist[kind] += 1
            dist["blocco"] += computed
        print(f"distanze: coordinate per {len(coords)}/{len(named)} citta', {dist['blocco']} esempi con la distanza calcolata")
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
    rng.shuffle(rows)

    for name, (dataset, *_, lic, _) in L["off_topic_sources"].items():  # solo domande, con rifiuto come risposta
        attribution.append(("-", f"off-topic ({name})", f"https://huggingface.co/datasets/{dataset}", lic))
    data_out, attr_out = it.OUT / f"pocket_travel_sft.{version}.{lang}.jsonl", it.OUT / f"ATTRIBUTION.{version}.{lang}.tsv"
    with open(data_out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(attr_out, "w", encoding="utf-8") as f:
        f.write("regionId\tdisplayName\tsourceUrl\tlicense\n")
        for rid, name, url, lic in attribution:
            f.write(f"{rid}\t{name}\t{url}\t{lic}\n")
    print(f"regioni con testo: {len(data)}; sezioni tradotte {other}->{lang}: {n_tr['sezioni']}, paragrafi Wikipedia: {n_tr['wikipedia']}")
    print(f"positivi={pos} negativi={neg} citta' positivi={city_pos} negativi={city_neg} "
          f"fatti rapidi {quick_pos}/{quick_neg} note {note_pos} vaccinazioni {vacc_pos}/{vacc_neg} "
          f"distanze {dist['pos']}/{dist['neg']} vicino {near['VICINO', 'pos']}/{near['VICINO', 'neg']} partenze {near['PARTENZE', 'pos']}/{near['PARTENZE', 'neg']} totale={len(rows)} "
          f"(rifiuti {sum(r['kind'] == 'neg' for r in rows) / max(len(rows), 1):.1%}, tradotte {sum(r['translated'] for r in rows)})")
    cats, refs = Counter(r["category"] for r in rows), Counter(r["category"] for r in rows if r["kind"] == "neg")
    print("per categoria (righe/rifiuti):", {c: f"{n}/{refs[c]}" for c, n in cats.most_common()})
    print(f"scritto {data_out}")


if __name__ == "__main__":
    main()
