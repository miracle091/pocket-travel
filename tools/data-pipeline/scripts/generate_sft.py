#!/usr/bin/env python3
"""Genera il dataset SFT v9 in italiano o in inglese con lo stesso metodo, cosi' i due dataset restano equivalenti:
stesse fonti (Wikivoyage IT ed EN dello stesso dump, Wikipedia IT per le categorie deboli, fatti rapidi e note), stessa
composizione per categoria, stessi tipi di domanda, stesso rapporto di rifiuti. Cambiano solo le tabelle della lingua
(domande, parole chiave, rifiuto, prompt dell'app), prese da generate_sft_dataset.py (italiano) e
generate_sft_dataset_en.py (inglese), che restano per rifare i dataset v8.

Traduzione incrociata (translate_sections.py): per ogni regione e categoria, se la sezione nella lingua del dataset
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
  (sft_nearby.py, dati sintetici), per una quota del dataset finale.
Fuori dal training le regioni di test e quelle la cui pagina (IT o EN) e' la pagina di una regione di test o vi
appartiene (es. figi-occidentali ha la pagina "Figi"/"Fiji" di figi-lau): restano nel file solo le regioni di test.

Uso: python generate_sft.py --lang it|en --dump-dir <cartella dei dump> [--cities 4500] [--guides-db <db>] [--nearby 0.03] [--seed 42]
Output in data/sft/: pocket_travel_sft.v9.<lang>.jsonl e ATTRIBUTION.v9.<lang>.tsv; traduzioni in cache in
raw/translations.<src>-<tgt>.jsonl.
"""
import argparse
import json
import random
import re
import urllib.parse
from collections import Counter, defaultdict
from pathlib import Path

import generate_sft_dataset as it
import generate_sft_dataset_en as en
import sft_nearby
import wiki_dump
from eval_common import TEST_REGIONS
from status import Progress, phase
from translate_sections import LICENSE as MT_LICENSE, MODELS as MT_MODELS, Translator, needs_translation

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


def flag_regions():
    """{codice paese: [regionId]} da pilot-regions.sh (flagCode, ottavo campo)."""
    src = (it.HERE / "pilot-regions.sh").read_text(encoding="utf-8")
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
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
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
    data_out, attr_out = it.OUT / f"pocket_travel_sft.v9.{lang}.jsonl", it.OUT / f"ATTRIBUTION.v9.{lang}.tsv"
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
          f"vicino {near['VICINO', 'pos']}/{near['VICINO', 'neg']} partenze {near['PARTENZE', 'pos']}/{near['PARTENZE', 'neg']} totale={len(rows)} "
          f"(rifiuti {sum(r['kind'] == 'neg' for r in rows) / max(len(rows), 1):.1%}, tradotte {sum(r['translated'] for r in rows)})")
    cats, refs = Counter(r["category"] for r in rows), Counter(r["category"] for r in rows if r["kind"] == "neg")
    print("per categoria (righe/rifiuti):", {c: f"{n}/{refs[c]}" for c, n in cats.most_common()})
    print(f"scritto {data_out}")


if __name__ == "__main__":
    main()
