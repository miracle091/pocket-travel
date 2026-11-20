#!/usr/bin/env python3
"""Righe a piu' turni del v12 (--multi-turn-rows di generate_sft.py), nel formato che l'app manda ai modelli v12 con la
cronologia vera nel contesto: i turni prima sono compattati (ogni turno utente e' solo la domanda come l'ha scritta
l'utente, ogni turno assistente la sua risposta), il contesto della guida e le istruzioni stanno solo nell'ultimo turno
utente, che e' il prompt completo dell'app come nelle righe a un turno. Nessun turno system. Loss solo sull'ultima
risposta (chat_prompt_and_answer di eval_common.py).

Tutto il testo viene da righe a un turno gia' controllate dello stesso file: domande, risposte e contesti, piu' le
domande di seguito di FOLLOW_UPS, scritte a mano. Tre tipi di riga ("turns"):
- "seguito": l'ultima domanda ha senso solo con i turni prima ("E per dormire?"); il contesto e la risposta sono quelli
  di un positivo dello stesso luogo per la categoria della domanda, cioe' il contesto che l'app cerca per la domanda
  risolta;
- "cambio": l'ultima domanda nomina il luogo e sta in piedi da sola (un altro luogo o un'altra categoria dello stesso
  luogo): la risposta viene solo dal suo contesto, non dai turni prima;
- "rifiuto": l'ultimo contesto non risponde e la risposta e' il rifiuto della riga d'origine, anche quando un turno prima
  ha gia' dato fatti sullo stesso tema (meta' delle righe con la domanda di seguito della categoria, poi la domanda della
  riga d'origine dopo un turno sulla stessa categoria, e qualche domanda fuori tema).
Il primo turno nomina sempre il luogo (i seguiti dicono "li'"). Le righe delle regioni di test (TEST_ROWS, divise per
tipo) restano nel file per eval_gguf.py --multi-turn, come le altre righe di test; luoghi e righe d'origine non si
mescolano tra training e test. Ogni riga sta entro MAX_TOKENS token del tokenizer di Qwen3.5 (token_counter), sotto il
--max-len 1024 di train_lora.py che scarterebbe le righe piu' lunghe: se serve, si tolgono i turni di mezzo.
"""
import random

import audit_sft
import sft_selected_text
from eval_common import TEST_REGIONS, chatml_answer, chatml_prompt, text_lang

CATEGORY = "PIU_TURNI"
SHARES = {"seguito": 0.4, "cambio": 0.3, "rifiuto": 0.3}
TEST_ROWS = 60  # righe delle regioni di test, un terzo per tipo
TURNS_BEFORE = ((1, 0.5), (2, 0.3), (3, 0.2))  # turni prima dell'ultimo: righe da 2, 3 e 4 turni
MAX_TOKENS = 1000  # margine sotto --max-len 1024 di train_lora.py
TOKENIZER = ("Qwen/Qwen3.5-0.8B", "2fc06364715b967f1860aea9cf38778875588b17")  # stesso tokenizer per 0.8B, 2B e 4B
# Categorie con risposte non copiate dal contesto (audit_sft) o legate a un dato che non e' del luogo (note personali,
# distanze tra due citta')
SKIP = audit_sft.NUMBERS_ONLY | audit_sft.LOOSE | audit_sft.FROM_PHRASEBOOK | {"NOTE", "DISTANZE"}
OFF = "OFF"
GENERAL = "_general"  # righe della guida generale: non sono di un luogo
# Domande di seguito per categoria, senza il nome del luogo. Diverse dalle domande delle serie di misura e dal seguito di
# MultiTurnDeviceTest ("E per quanto tempo posso restare?"): test_sft_multi_turn.py lo controlla.
FOLLOW_UPS = {
    "it": {
        "USI_COSTUMI": ["E come mi devo comportare con la gente del posto?", "Ci sono usanze da rispettare lì?", "E le buone maniere?"],
        "DOGANE": ["E come ci arrivo?", "Cosa serve per entrare?", "E per arrivarci?"],
        "ARRIVARE": ["E lì come ci arrivo?", "Come ci si arriva?", "Qual è il modo migliore per arrivarci?"],
        "SALUTE": ["E per la salute, cosa devo sapere?", "Servono precauzioni sanitarie lì?", "E dal punto di vista sanitario?"],
        "SICUREZZA": ["Ed è sicuro?", "A cosa devo stare attento lì?", "Ci sono pericoli?"],
        "TRASPORTI": ["E come mi sposto lì?", "E per girare?", "Come ci si muove una volta lì?"],
        "CIBO_BEVANDE": ["E cosa si mangia lì?", "E per mangiare?", "Che piatti mi consigli di provare lì?"],
        "ACQUISTI": ["E con i soldi come funziona?", "E per pagare?", "Che moneta usano lì?"],
        "CONNETTIVITA": ["E per telefono e internet?", "Come resto connesso lì?", "E internet?"],
        "COSA_VEDERE": ["E cosa c'è da vedere?", "Cosa merita una visita lì?", "E le attrazioni principali?"],
        "ALLOGGIO": ["E dove posso dormire?", "E per dormire?", "Ci sono alberghi lì?"],
        "SHOPPING": ["E per fare shopping?", "Cosa posso comprare lì?", "E per gli acquisti?"],
        "STORIA": ["E la sua storia?", "Qual è la storia del posto?", "Mi racconti un po' di storia?"],
        "CLIMA": ["E il clima com'è?", "Che tempo fa di solito lì?", "Com'è il clima durante l'anno?"],
        "FESTIVITA": ["E le feste?", "Quali feste si celebrano lì?", "Ci sono festività importanti?"],
    },
    "en": {
        "USI_COSTUMI": ["And how should I behave with the locals?", "Are there customs to respect there?", "What about etiquette?"],
        "DOGANE": ["And how do I get there?", "What do I need to enter?", "And how do I reach it?"],
        "ARRIVARE": ["How do I get there?", "What's the best way to get there?", "And getting there?"],
        "SALUTE": ["And what about health?", "Do I need any health precautions there?", "Is there anything health-related to know?"],
        "SICUREZZA": ["Is it safe?", "What should I be careful about there?", "Are there any dangers?"],
        "TRASPORTI": ["And how do I get around there?", "What about getting around?", "How do I move around once there?"],
        "CIBO_BEVANDE": ["What about the food there?", "And what do people eat?", "Which dishes should I try there?"],
        "ACQUISTI": ["And what about money?", "And paying for things?", "What currency do they use there?"],
        "CONNETTIVITA": ["What about phone and internet?", "How do I stay connected there?", "And internet?"],
        "COSA_VEDERE": ["And what is there to see?", "What's worth a visit there?", "What about the main sights?"],
        "ALLOGGIO": ["And where can I sleep?", "What about accommodation?", "Are there hotels there?"],
        "SHOPPING": ["And for shopping?", "What can I buy there?", "What about shops?"],
        "STORIA": ["And its history?", "What's the history of the place?", "Can you tell me some history?"],
        "CLIMA": ["And what's the climate like?", "What's the weather usually like there?", "How is the climate through the year?"],
        "FESTIVITA": ["What about holidays?", "Which festivals are celebrated there?", "Are there important holidays?"],
    },
}


def token_counter():
    """messages -> token dell'esempio come li conta train_lora.py (prompt e risposta tokenizzati a parte, nel ChatML del
    training), con il tokenizer di Qwen3.5 a una revisione fissa (dalla cache di Hugging Face o scaricato)."""
    from transformers import AutoTokenizer
    name, revision = TOKENIZER
    tok = AutoTokenizer.from_pretrained(name, revision=revision)
    size = lambda text: len(tok(text, add_special_tokens=False)["input_ids"])
    return lambda msgs: size(chatml_prompt(msgs[-2]["content"], history=msgs[:-2])) + size(chatml_answer(msgs[-1]["content"]))


def source_rows(rows, lang):
    """{regione: {"pos"|"neg": {categoria: [(riga, contesto, domanda, risposta)]}}} delle righe della chat utili:
    senza compiti, copie con istruzioni nascoste, domande senza risposta, testo selezionato, guida generale, categorie di
    SKIP e domande nell'altra lingua (i seguiti sono nella lingua del dataset)."""
    selected = tuple(f"{label}: «" for label in sft_selected_text.SELECTED_LABEL.values())
    out = {}
    for r in rows:
        if ("task" in r or r.get("injected") or r.get("unanswerable") or r.get("turns") or r["region"] == GENERAL
                or r["category"] in SKIP or (r["category"] == OFF and r["kind"] == "pos")):
            continue
        context, q, answer = audit_sft.parse_row(r)
        if context.startswith(selected) or text_lang(q) not in (None, lang):
            continue
        out.setdefault(r["region"], {"pos": {}, "neg": {}})[r["kind"]].setdefault(r["category"], []).append((r, context, q, answer))
    return out


def multi_turn_rows(lang, rows, n, rng, prompt, name_of, count_tokens, max_tokens=MAX_TOKENS, test_rows=TEST_ROWS):
    """Circa [n] righe a piu' turni dalle regioni di training e [test_rows] dalle regioni di test, divise per tipo
    secondo SHARES (il test un terzo per tipo). [prompt] e' on_device_prompt della lingua (quello delle righe d'origine),
    [name_of] da' il nome del luogo di una regione (None se non lo sa), [count_tokens] i token di una riga."""
    pools = source_rows(rows, lang)
    out, seen = [], set()
    for test, total in ((False, n), (True, test_rows)):
        regions = sorted(r for r in pools if (r in TEST_REGIONS) == test)
        targets = ({t: total // len(SHARES) for t in SHARES} if test else
                   {t: round(total * share) for t, share in SHARES.items()})
        for kind, target in targets.items():
            made, tries = 0, 0
            while made < target and tries < target * 50 and regions:
                tries += 1
                row = make_row(kind, lang, rng, pools, regions, prompt, name_of, count_tokens, max_tokens)
                if row is None:
                    continue
                key = tuple(m["content"] for m in row["messages"])
                if key in seen:
                    continue
                seen.add(key)
                out.append(row)
                made += 1
    return out


def turns_before(rng):
    x, acc = rng.random(), 0.0
    for k, w in TURNS_BEFORE:
        acc += w
        if x < acc:
            return k
    return TURNS_BEFORE[-1][0]


def named(rows, name):
    """Le righe con il nome del luogo nella domanda."""
    return [x for x in rows if name and name.lower() in x[2].lower()]


def history(lang, rng, pool, name, n_before, avoid, first=None):
    """[(domanda, risposta, riga)] dei turni prima su un luogo: il primo (o [first]) e' un positivo con il nome del luogo
    nella domanda, gli altri domande di seguito con la risposta di un positivo della loro categoria; mai le categorie di
    [avoid]. None se il luogo non basta."""
    pos = pool["pos"]
    if first is None:
        firsts = [x for c, xs in pos.items() if c not in avoid for x in named(xs, name)]
        if not firsts:
            return None
        first = rng.choice(firsts)
    turns, used = [(first[2], first[3], first[0])], set(avoid) | {first[0]["category"]}
    follow = [c for c in pos if c in FOLLOW_UPS[lang] and c not in used]
    rng.shuffle(follow)
    for cat in follow[:n_before - 1]:
        x = rng.choice(pos[cat])
        turns.append((rng.choice(FOLLOW_UPS[lang][cat]), x[3], x[0]))
    return turns


def make_row(kind, lang, rng, pools, regions, prompt, name_of, count_tokens, max_tokens):
    """Una riga del tipo [kind] o None se il luogo scelto a caso non ha le righe che servono."""
    region = rng.choice(regions)
    pool, name, n_before = pools[region], name_of(region), turns_before(rng)
    follow = FOLLOW_UPS[lang]
    if kind == "seguito":
        cats = [c for c in pool["pos"] if c in follow]
        if not cats:
            return None
        cat = rng.choice(cats)
        last = rng.choice(pool["pos"][cat])
        turns = history(lang, rng, pool, name, n_before, {cat})
        question = rng.choice(follow[cat])
    elif kind == "cambio":
        turns = history(lang, rng, pool, name, n_before, set())
        if turns is None:
            return None
        used = {t[2]["category"] for t in turns}
        other = rng.choice(regions)
        if rng.random() < 0.5 and other != region:  # un altro luogo
            choices = [x for xs in pools[other]["pos"].values() for x in named(xs, name_of(other))]
        else:  # lo stesso luogo, un'altra categoria, con la domanda che lo nomina
            choices = [x for c, xs in pool["pos"].items() if c not in used for x in named(xs, name)]
        if not choices:
            return None
        last = rng.choice(choices)
        question = last[2]
    else:
        negs = {c: xs for c, xs in pool["neg"].items() if c != OFF}
        x = rng.random()
        if x < 0.5:  # domanda di seguito di una categoria che il contesto non tratta
            cats = [c for c in negs if c in follow]
            if not cats:
                return None
            cat = rng.choice(cats)
            last, question = rng.choice(negs[cat]), rng.choice(follow[cat])
            turns = history(lang, rng, pool, name, n_before, {cat})
        elif x < 0.85:  # la stessa categoria di un turno prima, che ne aveva i fatti
            cats = [c for c in negs if named(negs[c], name) and named(pool["pos"].get(c, []), name)]
            if not cats:
                return None
            cat = rng.choice(cats)
            last = rng.choice(named(negs[cat], name))
            first = rng.choice(named(pool["pos"][cat], name))
            question = last[2]
            turns = history(lang, rng, pool, name, n_before, set(), first) if first[2] != question else None
        else:  # fuori tema
            if not pool["neg"].get(OFF):
                return None
            last = rng.choice(pool["neg"][OFF])
            question = last[2]
            turns = history(lang, rng, pool, name, n_before, set())
    if turns is None or last[3] in {t[1] for t in turns} or question in {t[0] for t in turns}:
        return None
    final = [{"role": "user", "content": prompt(last[1], question)}, {"role": "assistant", "content": last[3]}]
    while True:
        msgs = [m for q, a, _ in turns for m in ({"role": "user", "content": q}, {"role": "assistant", "content": a})] + final
        if count_tokens(msgs) <= max_tokens:
            break
        if len(turns) == 1:
            return None
        del turns[1]  # il primo turno nomina il luogo: si tolgono quelli di mezzo
    return {"messages": msgs, "kind": last[0]["kind"], "region": last[0]["region"], "category": CATEGORY,
            "translated": any(t[2]["translated"] for t in turns) or last[0]["translated"], "turns": kind,
            "last_category": last[0]["category"]}
