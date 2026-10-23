#!/usr/bin/env python3
"""Come generate_eval_set.py, ma per il dataset inglese (generate_sft_dataset_en.py): test esteso
eval_extended.en.jsonl sulle regioni di test (TEST_REGIONS), con le pagine di Wikivoyage EN, il prompt inglese e
domande inglesi scritte a mano che NON sono nei template di training (controllato all'avvio). Stessi tipi di riga:
  pos_para    positivo, domanda riformulata, sezione giusta + 0-2 sezioni distraenti;
  neg_para    negativo: contesto con sole sezioni di altre categorie che non trattano quella chiesta;
  neg_empty   negativo con il contesto di fallback inglese dell'app;
  neg_off     domanda che non c'entra con la guida;
  pos_city / neg_city (con --dump-dir) sulle citta' delle regioni di test, che il training esclude;
  pos_near / neg_near / pos_dep / neg_dep in fondo, con il seme e le domande inglesi di nearby_rows (generate_eval_set.py);
  pos_wiki / neg_wiki (con --cities-db, cities-en.db delle regioni di test) ancora dopo, come wikipedia_rows di generate_eval_set.py;
  in coda, con "set": "v11" e semi propri, le righe in piu' di neg_city e pos_near/neg_near come nel test italiano, e
  righe pos_para/neg_para/pos_city/neg_city con la domanda in italiano ("question_lang": "it", italian_rows).
Le pagine EN dei paesi vengono dalla cache di generate_sft_dataset.py (data/sft/raw/<regionId>.en.txt), come per il
test italiano; le citta' dal dump EN. Nei negativi le categorie sono 4 a caso per regione (le pagine EN hanno quasi
tutte le sezioni: con le sole categorie assenti, come nel test italiano, i negativi sarebbero pochissimi).
Uso: python generate_eval_set_en.py [--dump-dir <cartella dei dump> [--dump-date MM-AAAA]] [--cities-db <cities-en.db> ...]
"""
import argparse
import json
import random
import sys
from collections import Counter
from pathlib import Path

from eval_common import TEST_REGIONS
from generate_eval_set import (CITY_FIX_SEED, CITY_NEG, CITY_POS, NEAR_EXTRA_QUOTAS, NEAR_EXTRA_SEED, NEAR_QUESTIONS_EXTRA, PARA as PARA_IT,
                               PARA_CITY, PARA_CITY_EXTRA, PARA_EN, city_extra_refusals, nearby_rows, refusal_context,
                               treats, wikipedia_rows)
import wiki_dump
from generate_sft_dataset import CITY_MIN_SECTION, DUMP_WIKIS, EN_HEADING_TO_CATEGORY, OUT, covers, load_sources, make_context, parse_sections
from generate_sft_dataset_en import (CITY_HEADING_TO_CATEGORY, CITY_QUESTIONS, FALLBACK_CONTEXT, KEYWORDS, OFF_TOPIC_TRAIN, QUESTIONS,
                                     TOPIC, answer_for, cities_en, display_name, load_en_dump, on_device_prompt, page_title,
                                     refusal, with_article)

# PARA_EN del test italiano piu' una formulazione nuova per categoria
PARA = {c: PARA_EN[c] + [extra] for c, extra in {
    "USI_COSTUMI": "What etiquette do visitors need to follow in {r}?",
    "DOGANE": "How do travellers usually get into {r}?",
    "SALUTE": "What should I know about staying healthy in {r}?",
    "SICUREZZA": "How risky is a trip to {r}?",
    "TRASPORTI": "Which transport options exist for moving around {r}?",
    "CIBO_BEVANDE": "What is eating out like in {r}?",
    "ACQUISTI": "Is cash or card better in {r}?",
    "CONNETTIVITA": "Can I get mobile data in {r}?",
    "VITA_QUOTIDIANA": "Which media can I follow while in {r}?",
}.items()}
# Le domande inglesi di PARA_CITY piu' una nuova per categoria
PARA_CITY_EN = {c: [PARA_CITY[c][1], extra] for c, extra in {
    "ARRIVARE": "By what means can I arrive in {r}?",
    "TRASPORTI": "How do visitors move about {r}?",
    "COSA_VEDERE": "Which sights stand out in {r}?",
    "CIBO_BEVANDE": "Any recommended places for dinner in {r}?",
    "ALLOGGIO": "What lodging options does {r} offer?",
    "SICUREZZA": "Do I need to be careful in {r}?",
    # niente "connection": vale anche come coincidenza di treni e aerei
    "CONNETTIVITA": "Where can I use the internet in {r}?",
    "SHOPPING": "Where do people go shopping in {r}?",
}.items()}
OFF_TOPIC = ["What is the capital of Spain?", "How do I cook pasta?", "Who won the 2006 football World Cup?", "What is 17 times 23?",
             "Write me a poem about the sea.", "Who wrote Hamlet?", "How do I install Python on Windows?",
             "What is the meaning of life?", "How do I make a carbonara?", "What is the speed of light?"]
TAIL = "for reliable details, check an official source."
NEG_CATEGORIES = 4  # categorie per regione nei negativi (neg_para e neg_empty)

train = {q for qs in (*QUESTIONS.values(), *CITY_QUESTIONS.values()) for q in qs} | set(OFF_TOPIC_TRAIN)
assert not train & ({q for qs in (*PARA.values(), *PARA_CITY_EN.values(), *PARA_CITY_EXTRA["en"].values()) for q in qs}
                    | set(OFF_TOPIC)), "domanda di test nel training"


def load_test_cities(dump_dir, dump_date, test_titles):
    """[(titolo, [(categoria, corpo)])] delle citta' EN la cui catena {{IsPartOf}} arriva a una regione di test."""
    pages, parent, city_titles = load_en_dump(wiki_dump.dump_files(dump_dir, DUMP_WIKIS["en"], dump_date))
    by_title = {t: t for t in test_titles}
    cities = []
    for title in sorted(city_titles):
        if title in test_titles or not cities_en.regions_of(title, parent, by_title):
            continue
        secs = [(c, b) for c, b in parse_sections(pages[title], CITY_HEADING_TO_CATEGORY)
                if covers(c, b, KEYWORDS) and len(b) >= CITY_MIN_SECTION]
        if secs:
            cities.append((title, secs))
    return cities


def city_rows(rng, cities, row, questions=PARA_CITY_EN, n_pos=CITY_POS, n_neg=CITY_NEG, fix_seed=CITY_FIX_SEED):
    """Come city_rows di generate_eval_set.py, sulle citta' EN di load_test_cities."""
    cities = list(cities)
    rng.shuffle(cities)
    fix_rng, pos, neg, slots, lost = random.Random(fix_seed), [], [], 0, 0
    for title, secs in cities:
        name, rid = display_name(title), f"citta:{title}"
        for cat, body in rng.sample(secs, min(2, len(secs))):
            if len(pos) >= n_pos:
                break
            others = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
            q = rng.choice(questions[cat]).format(r=name)
            context = make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
            answer = answer_for(context, body, q, cat, name, legacy=True)
            if len(answer) >= 40:
                pos.append(row("pos_city", rid, cat, context, q, answer))
        missing = [c for c in questions if c not in {c for c, _ in secs}]
        if missing and slots < n_neg:
            slots += 1  # conta i rifiuti tentati, non quelli tenuti: rng resta allineato alle righe di prima
            cat = rng.choice(missing)
            q = rng.choice(questions[cat]).format(r=name)
            if context := refusal_context(rng, fix_rng, secs, cat, q, name):
                neg.append(row("neg_city", rid, cat, context, q, refusal(TOPIC[cat], TAIL)))
            else:
                lost += 1
        if len(pos) >= n_pos and slots >= n_neg:
            break
    print(f"citta' delle regioni di test: {len(cities)} con sezioni utili, righe pos {len(pos)} neg {len(neg)} "
          f"(rifiuti senza un contesto che non tratta la categoria: {lost})")
    return pos + neg


IT_SEED = 2615  # domande italiane in coda (italian_rows)
IT_PARA_POS, IT_CITY_POS, IT_CITY_NEG = 40, 20, 10


def italian_rows(countries, cities, row, date):
    """Righe con la domanda in italiano sulle guide inglesi (chi scrive in italiano con l'app in inglese: la risposta resta
    in inglese, come nel training con --other-lang): pos_para e neg_para sui paesi ([(regionId, nome, sezioni)]), con le
    domande italiane di PARA di generate_eval_set.py, e pos_city/neg_city sulle citta' di load_test_cities ([cities]), con
    quelle di PARA_CITY."""
    rng, pos, neg = random.Random(IT_SEED), [], []
    for rid, name, secs in countries:
        for cat, body in secs:
            if cat not in PARA_IT or not covers(cat, body, KEYWORDS):
                continue
            others = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
            q = rng.choice(PARA_IT[cat]).format(r=name)
            context = make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
            answer = answer_for(context, body, q, cat, name)
            if len(answer) >= 40:
                pos.append(row("pos_para", rid, cat, context, q, answer))
        cat = rng.choice(sorted(PARA_IT))
        bodies = [b for c, b in secs if c != cat and not treats(cat, b)]
        if bodies:
            q = rng.choice(PARA_IT[cat]).format(r=name)
            context = make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))), q, name)
            neg.append(row("neg_para", rid, cat, context, q, refusal(TOPIC[cat], TAIL)))
    rng.shuffle(pos)
    out = pos[:IT_PARA_POS] + neg
    if cities:
        it_city = {c: [qs[0]] for c, qs in PARA_CITY.items()}  # la prima domanda di ogni categoria e' quella italiana
        out += [{**r, "dump": date} for r in city_rows(rng, cities, row, it_city, IT_CITY_POS, IT_CITY_NEG, IT_SEED + 1)]
    print(f"righe con la domanda in italiano: {dict(Counter(r['kind'] for r in out))}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump-dir", type=Path, help="cartella dei dump (come generate_sft_dataset_en.py): aggiunge le citta'")
    ap.add_argument("--dump-date", help="data dei dump (MM-AAAA, AAAA-MM o AAAA-MM-GG), di default il nome della cartella")
    ap.add_argument("--cities-db", nargs="+", metavar="DB",
                    help="cities-en.db pubblicati (<regionId>--<versione>--cities-en.db o <regionId>=<file>): aggiunge storia e "
                         "clima delle citta' delle regioni di test")
    args = ap.parse_args()
    rng = random.Random(42)
    sources = load_sources()
    titles = {rid: t for rid in sorted(TEST_REGIONS) if (t := page_title(rid, sources))}

    def row(kind, rid, cat, context, question, answer):
        return {"messages": [{"role": "user", "content": on_device_prompt(context, question)},
                             {"role": "assistant", "content": answer}],
                "kind": kind, "region": rid, "category": cat}

    out, guides, countries = [], [], []
    for rid in sorted(TEST_REGIONS):
        f = OUT / "raw" / f"{rid}.en.txt"
        secs = parse_sections(f.read_text(encoding="utf-8"), EN_HEADING_TO_CATEGORY) if f.exists() and rid in titles else []
        if not secs:  # un test senza una regione non e' confrontabile con i precedenti: meglio fermarsi
            sys.exit(f"{rid}: nessuna sezione EN in {OUT / 'raw'}: riempi la cache con generate_sft_dataset.py")
        name = with_article(display_name(titles[rid]))
        for cat, body in secs:
            if not covers(cat, body, KEYWORDS):
                continue
            others = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
            for i in range(3):
                q = PARA[cat][i].format(r=name)
                context = make_context(rng, [body] + rng.sample(others, min(len(others), i)), q, name)  # 0, 1 o 2 distrattori
                answer = answer_for(context, body, q, cat, name, legacy=True)
                if len(answer) >= 40:
                    out.append(row("pos_para", rid, cat, context, q, answer))
        for cat in rng.sample(list(QUESTIONS), NEG_CATEGORIES):
            bodies = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
            if bodies:
                q = rng.choice(PARA[cat]).format(r=name)
                context = make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))), q, name)
                out.append(row("neg_para", rid, cat, context, q, refusal(TOPIC[cat], TAIL)))
        for cat in rng.sample(list(QUESTIONS), NEG_CATEGORIES):
            out.append(row("neg_empty", rid, cat, FALLBACK_CONTEXT, rng.choice(PARA[cat]).format(r=name), refusal(TOPIC[cat], TAIL)))
        pool = [b for _, b in secs]
        guides.append((rid, name, pool))
        countries.append((rid, display_name(titles[rid]), secs))
        for q in OFF_TOPIC:
            out.append(row("neg_off", rid, "OFF", make_context(rng, rng.sample(pool, min(len(pool), rng.randint(1, 3))), q, name),
                           q, refusal("useful to answer the question", TAIL)))
    rng.shuffle(out)
    cities = []
    if args.dump_dir:  # in coda, dopo il mescolamento: le righe dei paesi restano quelle di prima
        date = wiki_dump.normalize_date(args.dump_date or args.dump_dir.name)
        cities = load_test_cities(args.dump_dir, date, set(titles.values()))
        # le righe delle citta' dicono da quale dump vengono: un test e un training di mesi diversi si vedono
        out += [{**r, "dump": date} for r in city_rows(rng, cities, row)]
    out += nearby_rows("en", guides, row, lambda topic: refusal(topic, TAIL))
    if args.cities_db:
        out += wikipedia_rows("en", args.cities_db, row, lambda topic: refusal(topic, TAIL))
    # Righe in piu' del v11, ciascuna con un seme proprio: le precedenti restano quelle di prima
    city_refusal = lambda cat: refusal(TOPIC[cat], TAIL)
    if cities:
        out += [{**r, "dump": date} for r in city_extra_refusals(cities, [("en", PARA_CITY_EXTRA["en"])], display_name, row, city_refusal)]
    out += [r | {"set": "v11"} for r in nearby_rows("en", guides, row, lambda topic: refusal(topic, TAIL), NEAR_EXTRA_QUOTAS,
                                                     NEAR_QUESTIONS_EXTRA, NEAR_EXTRA_SEED)]
    out += [r | {"set": "v11", "question_lang": "it"} for r in italian_rows(countries, cities, row, date if cities else None)]
    with open(OUT / "eval_extended.en.jsonl", "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"regioni di test: {len(TEST_REGIONS)}; righe: {len(out)}", dict(Counter(r["kind"] for r in out)))


if __name__ == "__main__":
    main()
