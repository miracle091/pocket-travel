#!/usr/bin/env python3
"""Righe SFT del v12 sul testo selezionato in un'altra app ("Chiedi all'assistente"): il contesto e' quello che l'app
manda al modello sul dispositivo, cioe' il solo testo selezionato (contextWithSelectedText di SelectedText.kt con il
contesto vuoto: «Testo selezionato dall'utente: «…»»), e la domanda e' una delle tre domande pronte del foglio
(stringhe ai_selected_text_*_question dell'app) o una domanda libera.

Nessuna risposta scritta da un modello:
- «Traduci» e «Cosa significa?»: coppie frase/traduzione dei frasari di Wikivoyage nella lingua del dataset (righe
  `; frase : traduzione (pronuncia)` dei frasari inglesi, modello {{Traduzione}} di quelli italiani, CC BY-SA come le
  guide): il testo selezionato e' la frase nell'altra lingua, la risposta la frase del frasario nella lingua del dataset
  (per «Cosa significa?» dentro una cornice fissa, MEANING_ANSWER);
- «Spiegami» e le domande libere: un paragrafo delle guide come testo selezionato, risposta estrattiva come le righe
  della chat (answer_for di generate_sft.py);
- rifiuti, con la frase di rifiuto del dataset, sulle domande di una categoria che il testo non tratta.
Fuori i casi della prova sull'emulatore del 2026-11-12 (MEASURED_WORDS).
"""
import re

import generate_sft_dataset as it

MIN_ANSWER = 40  # come le righe della chat (generate_sft.py, sft_retrieval.py)
# contextWithSelectedText (SelectedText.kt)
SELECTED_LABEL = {"it": "Testo selezionato dall'utente", "en": "Text selected by the user"}
# ai_selected_text_*_question in res/values/strings.xml e res/values-en/strings.xml di feature/ai
READY_QUESTIONS = {
    "it": {"translate": "Traduci in italiano il testo selezionato", "meaning": "Cosa significa il testo selezionato?",
           "explain": "Spiegami il testo selezionato e cosa devo fare"},
    "en": {"translate": "Translate the selected text into English", "meaning": "What does the selected text mean?",
           "explain": "Explain the selected text and what I should do"},
}
MEANING_ANSWER = {"it": "Significa «{t}».", "en": "It means “{t}”."}
# Parole dei quattro casi della prova del 2026-11-12 (Occitania: «pour», «cassoulet», un cartello in francese, un messaggio
# di un host): nessuna riga con queste parole nel testo selezionato
MEASURED_WORDS = {"pour", "cassoulet"}


def selected_context(text, lang):
    """contextWithSelectedText con il contesto vuoto: l'etichetta e il testo tra «», senza «» dentro."""
    return f"{SELECTED_LABEL[lang]}: «{text.replace('«', '').replace('»', '')}»"


def measured(text):
    """True se [text] ha una parola dei casi della prova del 2026-11-12."""
    return bool(MEASURED_WORDS & set(re.findall(r"\w+", text.lower())))


CATEGORY = "TRADUZIONE"  # righe di «Traduci» e «Cosa significa?»: risposta dal frasario, non copiata dal contesto
MIN_PARAGRAPH, MAX_PARAGRAPH = 60, 500  # caratteri di un paragrafo delle guide usato come testo selezionato
LINK = re.compile(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]")
FILE = re.compile(r"\[\[(?:File|Image|Media):[^\]]*\]\]")  # audio della pronuncia: via prima di LINK
REVERSED_MIN_BOOKS = 20  # una frase in questa colonna di almeno tanti frasari e' nella lingua del dataset (drop_reversed)
IT_PAIR = re.compile(r"\{\{Traduzione\|([^|}]*)\|([^|}]*)")
EN_PAIR = re.compile(r"^;\s*(.+?)\s+:\s+(.+)$")


def unlink(text):
    """[text] senza i link ai file audio e con il solo testo degli altri link."""
    return LINK.sub(r"\1", FILE.sub("", text))


def clean(text):
    """Una frase del frasario senza link, corsivi, grassetti, commenti HTML e asterischi delle note."""
    text = re.sub(r"</?[a-z]+[^>]*>", "", re.sub(r"<!--.*?-->", "", unlink(text)))
    return " ".join(text.replace("'''", "").replace("''", "").split()).rstrip("*").rstrip()


def en_phrasebooks(pages):
    """{titolo: testo} dei frasari "… phrasebook" in [pages] di load_en_dump, col titolo della pagina e non dei redirect:
    load_en_dump mette prima le pagine e poi i redirect, con lo stesso oggetto testo della pagina di arrivo."""
    seen, books = set(), {}
    for title, text in pages.items():
        if id(text) not in seen:
            seen.add(id(text))
            if title.endswith(" phrasebook"):
                books[title] = text
    return books


def phrasebook_pairs(text, lang):
    """[(frase nella lingua del frasario, traduzione)] di un frasario di Wikivoyage: {{Traduzione|italiano|straniero|...}}
    nei frasari italiani, `; english : straniero (''pronuncia'')` dopo il titolo "Phrase list" in quelli inglesi. Fuori
    le frasi incomplete ("...", "_____"), con markup o entita' HTML rimasti, con parentesi spaiate (righe di pronuncia
    lasciate a meta'), le traduzioni mancanti, senza lettere, uguali alla frase o che cominciano con un segno di
    punteggiatura."""
    out, listing = [], lang == "it"
    for line in text.splitlines():
        if lang == "en" and line.startswith("=="):
            listing = listing or "phrase list" in line.lower()
        if lang == "it":
            m = IT_PAIR.search(unlink(line))
            pair = (clean(m.group(1)), clean(m.group(2))) if m else None
        else:
            m = EN_PAIR.match(line) if listing else None
            pair = (clean(m.group(1)), clean(re.split(r"\(''|''", m.group(2))[0])) if m else None
        if (pair and all(pair) and pair[0].lower() != pair[1].lower() and re.search(r"[^\W\d_]", pair[1])
                and not re.match(r"[^\w¿¡(«\"'“„]", pair[1]) and all(p.count("(") == p.count(")") for p in pair)
                and not any(m in p for p in pair for m in ("...", "…", "__", "{", "}", "[", "]", "<", ">", "|", "&", "File:"))):
            out.append(pair)
    return out


def drop_reversed(pairs, min_books=REVERSED_MIN_BOOKS):
    """[pairs] ((regione, url, frase nella lingua del dataset, frase nell'altra)) senza le coppie con le colonne invertite:
    alcune sezioni dei frasari inglesi (cartelli, giorni, contrazioni) sono scritte `; straniero : inglese`, e la frase
    "straniera" e' allora una frase che sta nella colonna della lingua del dataset di almeno [min_books] frasari."""
    books = {}
    for region, _, mine, _ in pairs:
        books.setdefault(mine.lower(), set()).add(region)
    common = {phrase for phrase, regions in books.items() if len(regions) >= min_books}
    return [p for p in pairs if p[3].lower() not in common]


def selected_text_rows(lang, L, pairs, data, counts, rng, answer_for, keywords, test_regions=(), category_counts=None):
    """Righe sul testo selezionato nel formato di row() di generate_sft.py, in [lang] con le tabelle [L]:
    - counts["translate"] e counts["meaning"] righe dalle coppie [pairs] ((regione, url, frase nella lingua del dataset,
      frase nell'altra lingua)), ogni risposta al piu' una volta per domanda pronta;
    - counts["explain"] e counts["free"] righe sui paragrafi delle sezioni di [data] ({regionId: (nome, [(categoria,
      corpo, tradotta)], ...)}, fuori [test_regions]) che trattano la loro categoria, con risposta estrattiva;
    - counts["refusal"] rifiuti, meta' sui paragrafi e meta' sulle frasi, con una domanda di una categoria che il testo
      non tratta ([keywords]: radici delle due lingue), scelta in proporzione a [category_counts] (positivi per
      categoria, come weighted_category di generate_sft.py), perche' le categorie piccole non si riempiano di rifiuti;
      le domande sulle frasi nominano regioni fuori da [test_regions].
    Niente testi con le parole dei casi misurati (measured)."""
    ready, rows, seen = READY_QUESTIONS[lang], [], set()

    def add(region, cat, text, q, answer, kind="pos", translated=False):
        context = selected_context(text, lang)
        if (context, q) in seen:
            return False
        seen.add((context, q))
        rows.append({"messages": [{"role": "user", "content": L["prompt"](context, q)}, {"role": "assistant", "content": answer}],
                     "kind": kind, "region": region, "category": cat, "translated": bool(translated)})
        return True

    phrases = [p for p in pairs if not measured(p[3])]
    rng.shuffle(phrases)
    for kind, answer_of in (("translate", lambda t: t), ("meaning", lambda t: MEANING_ANSWER[lang].format(t=t))):
        used, n = set(), 0
        for region, _, mine, theirs in phrases:
            if n < counts[kind] and mine not in used and add(region, CATEGORY, theirs, ready[kind], answer_of(mine)):
                used.add(mine)
                n += 1
    paragraphs = [(rid, name, cat, line.strip(), tr) for rid, (name, secs, *_) in sorted(data.items()) if rid not in test_regions
                  for cat, body, tr in secs if cat in L["questions"] and cat in L["topic"]
                  for line in body.split("\n")
                  if MIN_PARAGRAPH <= len(line.strip()) <= MAX_PARAGRAPH and not measured(line)
                  and it.covers(cat, line, keywords[0])]
    rng.shuffle(paragraphs)
    for kind in ("explain", "free"):
        n = 0
        for rid, name, cat, text, tr in paragraphs:
            if n >= counts[kind]:
                break
            q = ready["explain"] if kind == "explain" else rng.choice(L["questions"][cat]).format(r=name)
            answer = answer_for(selected_context(text, lang), text, q, cat, name)
            if len(answer) >= MIN_ANSWER and add(rid, cat, text, q, answer, translated=tr):
                n += 1
    names = sorted({name for rid, (name, *_) in data.items() if rid not in test_regions})
    sources = [(rid, name, cat, text, tr) for rid, name, cat, text, tr in paragraphs] + \
              [(region, rng.choice(names) if names else "", None, theirs, False) for region, _, _, theirs in phrases]
    rng.shuffle(sources)
    n_par = n_phr = 0
    for region, name, cat, text, tr in sources:
        if n_par + n_phr >= counts["refusal"]:
            break
        if (cat is None and n_phr >= counts["refusal"] // 2) or (cat is not None and n_par >= counts["refusal"] - counts["refusal"] // 2):
            continue
        others = [c for c in L["questions"] if c != cat and c in L["topic"] and not any(it.covers(c, text, kw) for kw in keywords)]
        if not others:
            continue
        other = rng.choices(others, weights=[max((category_counts or {}).get(c, 0), 1) for c in others])[0]
        q = rng.choice(L["questions"][other]).format(r=name)
        if add(region, other, text, q, L["refusal"](L["topic"][other], rng.choice(L["tails"])), kind="neg", translated=tr):
            if cat is None:
                n_phr += 1
            else:
                n_par += 1
    return rows
