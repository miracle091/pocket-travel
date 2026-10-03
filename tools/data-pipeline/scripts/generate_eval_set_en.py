#!/usr/bin/env python3
"""Come generate_eval_set.py, ma per il dataset inglese (generate_sft_dataset_en.py): test esteso
eval_extended.en.jsonl sulle regioni di test (TEST_REGIONS), con le pagine di Wikivoyage EN, il prompt inglese e
domande inglesi scritte a mano che NON sono nei template di training (controllato all'avvio). Stessi tipi di riga:
  pos_para    positivo, domanda riformulata, sezione giusta + 0-2 sezioni distraenti;
  neg_para    negativo: contesto con sole sezioni di altre categorie che non trattano quella chiesta;
  neg_empty   negativo con il contesto di fallback inglese dell'app;
  neg_off     domanda che non c'entra con la guida;
  pos_city / neg_city (con --dump-dir) sulle citta' delle regioni di test, che il training esclude.
Le pagine EN dei paesi vengono dalla cache di generate_sft_dataset.py (data/sft/raw/<regionId>.en.txt), come per il
test italiano; le citta' dal dump EN. Nei negativi le categorie sono 4 a caso per regione (le pagine EN hanno quasi
tutte le sezioni: con le sole categorie assenti, come nel test italiano, i negativi sarebbero pochissimi).
Uso: python generate_eval_set_en.py [--dump-dir <cartella dei dump> [--dump-date AAAAMMGG]]
"""
import argparse
import json
import random
import sys
from collections import Counter
from pathlib import Path

from eval_common import TEST_REGIONS
from generate_eval_set import CITY_NEG, CITY_POS, PARA_CITY, PARA_EN
from generate_sft_dataset import CITY_MIN_SECTION, DUMP_FILES, EN_HEADING_TO_CATEGORY, OUT, covers, load_sources, make_context, parse_sections
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
    "CONNETTIVITA": "How can I get a connection in {r}?",
    "SHOPPING": "Where do people go shopping in {r}?",
}.items()}
OFF_TOPIC = ["What is the capital of Spain?", "How do I cook pasta?", "Who won the 2006 football World Cup?", "What is 17 times 23?",
             "Write me a poem about the sea.", "Who wrote Hamlet?", "How do I install Python on Windows?",
             "What is the meaning of life?", "How do I make a carbonara?", "What is the speed of light?"]
TAIL = "for reliable details, check an official source."
NEG_CATEGORIES = 4  # categorie per regione nei negativi (neg_para e neg_empty)

train = {q for qs in (*QUESTIONS.values(), *CITY_QUESTIONS.values()) for q in qs} | set(OFF_TOPIC_TRAIN)
assert not train & ({q for qs in (*PARA.values(), *PARA_CITY_EN.values()) for q in qs} | set(OFF_TOPIC)), "domanda di test nel training"


def city_rows(rng, dump_dir, dump_date, row, test_titles):
    """Come city_rows di generate_eval_set.py, sulle citta' EN la cui catena {{IsPartOf}} arriva a una regione di test."""
    pages, parent, city_titles = load_en_dump(dump_dir / DUMP_FILES["en"].format(d=dump_date))
    by_title = {t: t for t in test_titles}
    cities = []
    for title in sorted(city_titles):
        if title in test_titles or not cities_en.regions_of(title, parent, by_title):
            continue
        secs = [(c, b) for c, b in parse_sections(pages[title], CITY_HEADING_TO_CATEGORY)
                if covers(c, b, KEYWORDS) and len(b) >= CITY_MIN_SECTION]
        if secs:
            cities.append((title, secs))
    rng.shuffle(cities)
    pos, neg = [], []
    for title, secs in cities:
        name, rid = display_name(title), f"citta:{title}"
        for cat, body in rng.sample(secs, min(2, len(secs))):
            if len(pos) >= CITY_POS:
                break
            others = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
            q = rng.choice(PARA_CITY_EN[cat]).format(r=name)
            context = make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
            answer = answer_for(context, body, q, cat, name)
            if len(answer) >= 40:
                pos.append(row("pos_city", rid, cat, context, q, answer))
        missing = [c for c in PARA_CITY_EN if c not in {c for c, _ in secs}]
        if missing and len(neg) < CITY_NEG:
            cat = rng.choice(missing)
            q = rng.choice(PARA_CITY_EN[cat]).format(r=name)
            context = make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))), q, name)
            neg.append(row("neg_city", rid, cat, context, q, refusal(TOPIC[cat], TAIL)))
        if len(pos) >= CITY_POS and len(neg) >= CITY_NEG:
            break
    print(f"citta' delle regioni di test: {len(cities)} con sezioni utili, righe pos {len(pos)} neg {len(neg)}")
    return pos + neg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump-dir", type=Path, help="cartella dei dump (come generate_sft_dataset_en.py): aggiunge le citta'")
    ap.add_argument("--dump-date", help="data dei dump (AAAAMMGG), di default il nome della cartella")
    args = ap.parse_args()
    rng = random.Random(42)
    sources = load_sources()
    titles = {rid: t for rid in sorted(TEST_REGIONS) if (t := page_title(rid, sources))}

    def row(kind, rid, cat, context, question, answer):
        return {"messages": [{"role": "user", "content": on_device_prompt(context, question)},
                             {"role": "assistant", "content": answer}],
                "kind": kind, "region": rid, "category": cat}

    out = []
    for rid in sorted(TEST_REGIONS):
        f = OUT / "raw" / f"{rid}.en.txt"
        secs = parse_sections(f.read_text(encoding="utf-8"), EN_HEADING_TO_CATEGORY) if f.exists() and rid in titles else []
        if not secs:
            print(f"{rid}: nessuna sezione EN in {OUT / 'raw'}, regione saltata", file=sys.stderr)
            continue
        name = with_article(display_name(titles[rid]))
        for cat, body in secs:
            if not covers(cat, body, KEYWORDS):
                continue
            others = [b for c, b in secs if c != cat and not covers(cat, b, KEYWORDS)]
            for i in range(3):
                q = PARA[cat][i].format(r=name)
                context = make_context(rng, [body] + rng.sample(others, min(len(others), i)), q, name)  # 0, 1 o 2 distrattori
                answer = answer_for(context, body, q, cat, name)
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
        for q in OFF_TOPIC:
            out.append(row("neg_off", rid, "OFF", make_context(rng, rng.sample(pool, min(len(pool), rng.randint(1, 3))), q, name),
                           q, refusal("useful to answer the question", TAIL)))
    rng.shuffle(out)
    if args.dump_dir:  # in coda, dopo il mescolamento: le righe dei paesi restano quelle di prima
        out += city_rows(rng, args.dump_dir, args.dump_date or args.dump_dir.name, row, set(titles.values()))
    with open(OUT / "eval_extended.en.jsonl", "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"regioni di test: {len(TEST_REGIONS)}; righe: {len(out)}", dict(Counter(r["kind"] for r in out)))


if __name__ == "__main__":
    main()
