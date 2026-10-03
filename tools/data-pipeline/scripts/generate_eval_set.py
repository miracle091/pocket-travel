#!/usr/bin/env python3
"""Genera il test esteso (eval_extended.jsonl) sulle regioni di test di train_lora.py (TEST_REGIONS in eval_common.py).

Il test base usa le stesse domande e la stessa forma di negativo del training: misura se il modello ha
imparato quel criterio, non se generalizza. Qui le domande sono scritte a mano e NON sono nei template
di training (QUESTIONS* in generate_sft_dataset.py), e il contesto e' composto come nell'app
(make_context: fino a 3 sezioni unite entro 2000 caratteri, come l'app; oppure il testo di fallback). Tipi:
  pos_para    positivo, domanda riformulata (IT o EN), sezione giusta + sezioni distraenti;
  neg_para    negativo (categoria assente dal contesto), domanda riformulata (IT o EN);
  neg_empty   negativo con il contesto di fallback (la ricerca dell'app non trova nulla);
  neg_off     domanda che non c'entra con la guida (cultura generale, cucina, sport; IT e EN).
Un positivo/negativo si tiene solo se il contesto tratta (o non tratta) davvero la categoria (KEYWORDS):
le sezioni Wikivoyage a volte coprono altro (es. 'restare in contatto' = posta).
Con --dump-dir (i dump di generate_sft_dataset.py) aggiunge in coda righe sulle citta' delle regioni di test,
che il training esclude: pos_city (domande riformulate, PARA_CITY) e neg_city (categoria assente dalla pagina).
Legge solo la cache di generate_sft_dataset.py (data/sft/raw) e i dump: niente rete.
Uso: python generate_eval_set.py [--dump-dir <cartella dei dump> [--dump-date AAAAMMGG]]
"""
import argparse
import json
import random
import sys
from collections import Counter
from pathlib import Path

from eval_common import TEST_REGIONS

import wiki_dump
from generate_sft_dataset import (CITY_HEADING_TO_CATEGORY, CITY_MIN_SECTION, DUMP_FILES, EN_HEADING_TO_CATEGORY,
                                  FALLBACK_CONTEXT, HEADING_TO_CATEGORY, OUT, QUESTIONS, TOPIC, city_parents, covers,
                                  load_regions, load_sources, make_context, on_device_prompt, parse_sections, pick_answer)

PARA = {  # riformulazioni generiche: coprono tutta la categoria, cosi' il positivo ha davvero la risposta nel contesto
    "USI_COSTUMI": ["Che galateo bisogna seguire in {r}?", "Consigli di buona educazione per {r}?", "Come evito gaffe con la popolazione di {r}?"],
    "DOGANE": ["Qual e' il modo migliore per arrivare in {r}?", "Con che mezzi si raggiunge {r}?", "Come faccio a entrare in {r}?"],
    "SALUTE": ["Cosa devo sapere per stare in salute in {r}?", "Ci sono malattie da tenere d'occhio in {r}?", "Com'e' la situazione sanitaria in {r}?"],
    "SICUREZZA": ["Si puo' girare tranquilli in {r}?", "Che precauzioni prendo in {r}?", "Quanto e' rischioso viaggiare in {r}?"],
    "TRASPORTI": ["Come funzionano gli spostamenti in {r}?", "Come ci si muove tra le citta' in {r}?", "Posso muovermi senza auto in {r}?"],
    "CIBO_BEVANDE": ["Quali piatti tipici provo in {r}?", "Dove si mangia bene in {r}?", "Com'e' la tavola in {r}?"],
    "ACQUISTI": ["Che moneta serve in {r}?", "Come mi regolo con i prezzi in {r}?", "Come funziona il denaro in {r}?"],
    "CONNETTIVITA": ["Come resto connesso in {r}?", "Com'e' la copertura telefonica in {r}?", "Riesco a stare online in {r}?"],
    "VITA_QUOTIDIANA": ["Come resto aggiornato durante il soggiorno in {r}?", "Che fonti uso per informarmi in {r}?", "Dove trovo info pratiche per {r}?"],
}
PARA_EN = {  # come PARA, in inglese (2 per categoria)
    "USI_COSTUMI": ["Which manners should I keep in mind in {r}?", "How do I avoid offending people in {r}?"],
    "DOGANE": ["What is the best way to arrive in {r}?", "Which entry requirements apply to {r}?"],
    "SALUTE": ["Is it healthy to travel in {r}?", "Which medical issues exist in {r}?"],
    "SICUREZZA": ["Can I walk around {r} without worries?", "Which precautions should I take in {r}?"],
    "TRASPORTI": ["How does getting around work in {r}?", "Can I travel around {r} without a car?"],
    "CIBO_BEVANDE": ["Which local dishes should I try in {r}?", "Where is the best place to eat in {r}?"],
    "ACQUISTI": ["Which money do I need in {r}?", "How do prices and payments work in {r}?"],
    "CONNETTIVITA": ["How do I stay connected in {r}?", "How is mobile coverage in {r}?"],
    "VITA_QUOTIDIANA": ["How do I keep up with news in {r}?", "Where do I find practical info for {r}?"],
}
PARA_CITY = {  # domande sulle citta' riformulate: non sono in CITY_QUESTIONS/CITY_QUESTIONS_EN del training
    "ARRIVARE": ["Con che mezzi arrivo fino a {r}?", "How can I reach {r}?"],
    "TRASPORTI": ["Come giro per {r} senza auto?", "What is the easiest way to move around {r}?"],
    "COSA_VEDERE": ["Che posti meritano una visita a {r}?", "Which places are worth visiting in {r}?"],
    "CIBO_BEVANDE": ["Qualche posto dove cenare a {r}?", "Where do locals eat in {r}?"],
    "ALLOGGIO": ["Che sistemazioni ci sono a {r}?", "Which kinds of accommodation exist in {r}?"],
    "SICUREZZA": ["Devo stare attento a qualcosa a {r}?", "How safe is it to walk around {r}?"],
    "CONNETTIVITA": ["Come mi collego a internet a {r}?", "Where can I find wifi in {r}?"],
    "SHOPPING": ["Dove compro souvenir a {r}?", "Where are the markets in {r}?"],
}
CITY_POS, CITY_NEG = 50, 20  # righe di citta' nel test (con --dump-dir)
OFF_TOPIC = ["Qual e' la capitale della Francia?", "Come si prepara la carbonara?", "Chi ha vinto i mondiali di calcio nel 2006?",
             "Quanto fa 17 per 23?", "Scrivimi una poesia sul mare.", "Chi ha scritto la Divina Commedia?",
             "Come si installa Python su Windows?", "Qual e' il senso della vita?",
             "What is the capital of Spain?", "How do I cook pasta?"]


def city_rows(rng, dump_dir, dump_date, row, refusal):
    """Righe sulle citta' delle regioni di test (Stato/Regione/Territorio del QuickbarCity = pagina di una
    regione di test), dallo stesso dump IT del training: al massimo 2 domande per citta', CITY_POS positivi
    e CITY_NEG negativi in tutto, a rotazione tra le citta' per non pescarle tutte da un solo paese."""
    sources = load_sources()
    test_titles = {wiki_dump.norm_title(sources[(r, "it")]) for r in TEST_REGIONS if sources.get((r, "it"), "-") != "-"}
    dump = dump_dir / DUMP_FILES["it"].format(d=dump_date)
    cities = []
    for title, text, redirect in wiki_dump.iter_pages(dump):
        parents = None if redirect else city_parents(text)
        if not parents or not parents & test_titles:
            continue
        secs = [(c, b) for c, b in parse_sections(text, CITY_HEADING_TO_CATEGORY) if covers(c, b) and len(b) >= CITY_MIN_SECTION]
        if secs:
            cities.append((title, secs))
    cities.sort()
    rng.shuffle(cities)
    pos, neg = [], []
    for title, secs in cities:
        name = title.split(" (")[0]  # "Salem (Oregon)" -> "Salem"
        rid = f"citta:{title}"
        for cat, body in rng.sample(secs, min(2, len(secs))):
            if len(pos) >= CITY_POS:
                break
            others = [b for c, b in secs if c != cat and not covers(cat, b)]
            q = rng.choice(PARA_CITY[cat]).format(r=name)
            context = make_context(rng, [body] + rng.sample(others, min(len(others), rng.choice([0, 1, 2]))), q, name)
            answer = pick_answer(context, body, q, cat, name)
            if len(answer) >= 40:
                pos.append(row("pos_city", rid, cat, context, q, answer))
        missing = [c for c in PARA_CITY if c not in {c for c, _ in secs}]
        if missing and len(neg) < CITY_NEG:
            cat = rng.choice(missing)
            q = rng.choice(PARA_CITY[cat]).format(r=name)
            context = make_context(rng, rng.sample([b for _, b in secs], min(len(secs), rng.randint(1, 3))), q, name)
            neg.append(row("neg_city", rid, cat, context, q, refusal(cat)))
        if len(pos) >= CITY_POS and len(neg) >= CITY_NEG:
            break
    print(f"citta' delle regioni di test: {len(cities)} con sezioni utili, righe pos {len(pos)} neg {len(neg)}")
    return pos + neg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump-dir", type=Path, help="cartella dei dump (come generate_sft_dataset.py): aggiunge le citta'")
    ap.add_argument("--dump-date", help="data dei dump (AAAAMMGG), di default il nome della cartella")
    args = ap.parse_args()
    rng = random.Random(42)
    held_out = sorted(TEST_REGIONS)  # come train_lora.py
    # le regioni sostituite da sottoregioni (es. canada) non sono in pilot-regions.sh: nome dall'id
    names = {rid: rid.replace("-", " ").title() for rid in held_out} | {rid: name for rid, name, _ in load_regions()}
    langs = {}  # rid -> {"it": [(cat, corpo)], "en": [...]}
    for rid in held_out:
        langs[rid] = {}
        for lang, headings, suffix in (("it", HEADING_TO_CATEGORY, ""), ("en", EN_HEADING_TO_CATEGORY, ".en")):
            f = OUT / "raw" / f"{rid}{suffix}.txt"
            secs = parse_sections(f.read_text(encoding="utf-8"), headings) if f.exists() else []
            if secs:
                langs[rid][lang] = secs

    def refusal(cat):
        return f"Il contesto non contiene informazioni {TOPIC[cat]}: per dettagli affidabili consulta una fonte ufficiale."

    def question(cat, name, i):  # a rotazione: 2 IT e 1 EN ogni 3, cosi' il test e' stabile
        pool = PARA_EN[cat] if i % 3 == 2 else PARA[cat]
        return pool[i % len(pool)].format(r=name)

    def row(kind, rid, cat, context, question_, answer):
        return {"messages": [{"role": "user", "content": on_device_prompt(context, question_)},
                             {"role": "assistant", "content": answer}],
                "kind": kind, "region": rid, "category": cat}

    out = []
    for rid in held_out:
        if not langs[rid]:
            print(f"{rid}: nessuna sezione in {OUT / 'raw'} (ne' IT ne' EN), regione saltata", file=sys.stderr)
            continue
        name = names[rid]
        secs_it = langs[rid].get("it", [])
        for cat, body in secs_it:
            if not covers(cat, body):
                continue
            others = [b for c, b in secs_it if c != cat and not covers(cat, b)]
            for i in range(3):
                q = question(cat, name, i)
                context = make_context(rng, [body] + rng.sample(others, min(len(others), i)), q, name)  # 0, 1 o 2 distrattori
                answer = pick_answer(context, body, q, cat, name)
                if len(answer) >= 40:
                    out.append(row("pos_para", rid, cat, context, q, answer))
        for lang, secs in langs[rid].items():
            present = {c for c, _ in secs}
            for mc in (c for c in QUESTIONS if c not in present):
                bodies = [b for _, b in secs if not covers(mc, b)]
                if not bodies:
                    continue
                q = question(mc, name, rng.randint(0, 2))
                context = make_context(rng, rng.sample(bodies, min(len(bodies), rng.randint(1, 3))), q, name)
                out.append(row("neg_para", rid, mc, context, q, refusal(mc)))
                out.append(row("neg_empty", rid, mc, FALLBACK_CONTEXT, question(mc, name, rng.randint(0, 2)), refusal(mc)))
        pool = [b for _, b in (secs_it or next(iter(langs[rid].values())))]
        for q in OFF_TOPIC:
            out.append(row("neg_off", rid, "OFF", make_context(rng, rng.sample(pool, min(len(pool), rng.randint(1, 3))), q, name),
                           q, refusal("VITA_QUOTIDIANA")))
    rng.shuffle(out)
    if args.dump_dir:  # in coda, dopo il mescolamento: le righe dei paesi restano quelle di prima
        out += city_rows(rng, args.dump_dir, args.dump_date or args.dump_dir.name, row, refusal)
    with open(OUT / "eval_extended.jsonl", "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"regioni di test: {len(held_out)}; righe: {len(out)}", dict(Counter(r["kind"] for r in out)))


if __name__ == "__main__":
    main()
