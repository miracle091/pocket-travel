#!/usr/bin/env python3
"""Utilita' condivise da eval_behavior.py, eval_gguf.py e convert_gguf.py: stesse regioni di test di
train_lora.py (TEST_REGIONS), stesso formato di report, sia base che esteso (generate_eval_set.py), e
ricerca dei binari di llama.cpp.
"""
import json
import os
import re
import shutil
import sys
from pathlib import Path


EMPTY_THINK = "<think>\n\n</think>\n\n"


def uses_empty_think(chat_template):
    """Regola del blocco <think> vuoto, la stessa di ai_chat.cpp: solo i chat template con la variabile
    enable_thinking (i Qwen3.5 del v10, addestrati con enable_thinking=False). Non bastano i blocchi <think> nel
    template: Qwen3-4B-Instruct-2507 li gestisce ma non ha la variabile."""
    return chat_template is not None and "enable_thinking" in chat_template


def chatml_prompt(user, empty_think=False, system=None):
    """Prompt a turno singolo come lo manda l'app: il formatter CHATML di llama.cpp (src/llama-chat.cpp, che
    ai_chat.cpp usa per i modelli con template ChatML) per il turno utente e l'apertura del turno assistente, piu' il blocco <think>
    vuoto che ai_chat.cpp aggiunge quando uses_empty_think() vale per il template del modello. Con system, prima un turno
    system nello stesso formato (l'app oggi non lo manda: serve a misurarlo)."""
    system_turn = f"<|im_start|>system\n{system}<|im_end|>\n" if system else ""
    return f"{system_turn}<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n" + (EMPTY_THINK if empty_think else "")


def chatml_answer(answer):
    """Risposta chiusa come nel formatter CHATML: <|im_end|> e a capo."""
    return f"{answer}<|im_end|>\n"


def chat_prompt_and_answer(tok, messages):
    """(prompt, risposta) come testo, nel formato che l'app usa a runtime (ai_chat.cpp): ChatML del turno
    utente, piu' il blocco <think> vuoto se il chat template supporta enable_thinking. La risposta (da
    imparare) non contiene mai quel blocco: alcuni template (il Qwen3 ibrido, anche la copia di Unsloth di
    Qwen3-4B-Instruct-2507) lo mettono nel turno assistente completo ma non nel prompt di generazione,
    e il modello imparerebbe a scriverlo da solo (con il 4B le risposte iniziavano con "<think>..." e nessun
    rifiuto veniva riconosciuto). Con un solo messaggio si ottiene solo il prompt (risposta vuota)."""
    prompt = tok.apply_chat_template(messages[:1], add_generation_prompt=True, tokenize=False, enable_thinking=False)
    if uses_empty_think(tok.chat_template) and not prompt.endswith(EMPTY_THINK):
        prompt += EMPTY_THINK
    if len(messages) < 2:
        return prompt, ""
    full = tok.apply_chat_template(messages, tokenize=False, enable_thinking=False)
    base = tok.apply_chat_template(messages[:1], add_generation_prompt=True, tokenize=False, enable_thinking=False)
    assert full.startswith(base), "il template non estende il prompt: maschera non valida"
    return prompt, full[len(base):].removeprefix(EMPTY_THINK)


def find_llama_bin(name, *dirs):
    """Eseguibile di llama.cpp: nelle cartelle date (binari di get_llama_tools.py, o clone con build/bin),
    poi nel PATH."""
    candidates = [Path(d) / sub for d in dirs if d for sub in ("", "build/bin", "build/bin/Release", "build", "bin")]
    found = next((str(c / f"{name}{ext}") for c in candidates for ext in ("", ".exe") if (c / f"{name}{ext}").is_file()),
                 None) or shutil.which(name)
    if not found:
        sys.exit(f"{name} non trovato in {', '.join(str(d) for d in dirs if d)} ne' nel PATH: "
                 "scarica i binari con get_llama_tools.py")
    return found


def llama_env(exe):
    """Ambiente per un binario di llama.cpp: la build ROCm di get_llama_tools.py trova hipblas/rocblas
    nelle cartelle di extra-path.txt, accanto all'eseguibile."""
    env = dict(os.environ)
    extra = Path(exe).parent / "extra-path.txt"
    if extra.exists():
        env["PATH"] = os.pathsep.join(extra.read_text(encoding="utf-8").splitlines() + [env.get("PATH", "")])
    return env

SFT_DIR = Path(__file__).resolve().parent.parent / "data" / "sft"
DEFAULT_DATASET = "pocket_travel_sft.v10.it.jsonl"
# Token massimi di una risposta nei test: le risposte del dataset arrivano a 450 caratteri (~120 token), quindi con
# 256 una risposta piu' lunga del training resta intera. Rifiuti e "inizio uguale" leggono solo l'inizio.
ANSWER_TOKENS = 256
REFUSAL = "Il contesto non contiene informazioni"
# Dataset inglese (generate_sft.py --lang en): stesso criterio con il rifiuto inglese, riconosciuto dal prompt
REFUSAL_EN = "The context does not contain information"
EN_PROMPT = "You are an offline travel guide."
# Regioni di test, fisse: erano il campione casuale (seed 42, 1/20) delle 251 regioni di un dataset precedente e
# restano le stesse anche quando l'elenco regioni cambia (es. paesi divisi per stato), cosi' i risultati
# restano confrontabili. generate_sft_dataset.py esclude anche le loro sottoregioni (es. canada-*).
TEST_REGIONS = frozenset({"antartide", "bosnia-erzegovina", "brasile", "canada", "emirati-arabi-uniti", "figi-lau",
                          "germania", "palau", "regno-unito", "samoa", "samoa-americane"})


def run_dataset(model_dir):
    """Dataset (file in data/sft/) con cui e' stato addestrato <run>/merged: da <run>/run.json scritto
    da train_lora.py, altrimenti quello di default."""
    run_json = Path(model_dir).parent / "run.json"
    if run_json.exists():
        return json.loads(run_json.read_text(encoding="utf-8")).get("dataset", DEFAULT_DATASET)
    return DEFAULT_DATASET


def extended_file(dataset=None):
    """Test esteso del dataset: eval_extended.en.jsonl per quello inglese (*.en.jsonl), altrimenti eval_extended.jsonl."""
    return "eval_extended.en.jsonl" if (dataset or "").endswith(".en.jsonl") else "eval_extended.jsonl"


def refusal_prefix(row):
    """Inizio del rifiuto atteso nella lingua del prompt della riga."""
    return REFUSAL_EN if row["messages"][0]["content"].startswith(EN_PROMPT) else REFUSAL


def on_device_prompt(context, question, lang):
    """PromptTemplates.onDevicePrompt, con PromptTemplates.emptyContext al posto di un contesto vuoto (come planOnDevice)."""
    if lang == "en":
        context = context if context.strip() else "No information available for this region."
        return ("You are an offline travel guide.\n"
                "Answer in at most 3 sentences, in English, using only the information in the CONTEXT.\n"
                "If the context is not enough, say so explicitly.\n\n"
                f"CONTEXT: {context}\n\nQUESTION: {question}")
    context = context if context.strip() else "Nessuna informazione disponibile per questa regione."
    return ("Sei una guida turistica offline.\n"
            "Rispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.\n"
            "Se il contesto non basta, dillo esplicitamente.\n\n"
            f"CONTESTO: {context}\n\nDOMANDA: {question}")


def is_refusal(answer, lang):
    """Il rifiuto dei modelli addestrati: la risposta comincia con REFUSAL (o REFUSAL_EN), come in score."""
    return answer.startswith(REFUSAL_EN if lang == "en" else REFUSAL)


def context_report(rows):
    """Righe di eval_gguf.py --contexts (con "refused" e "section_in_context") -> per (lingua, serie, ricerca):
    (domande, con la sezione attesa nel contesto, rifiuti con la sezione, rifiuti senza). Le domande hanno tutte una
    risposta nelle guide: ogni rifiuto e' sbagliato, della ricerca se la sezione non e' nel contesto, del modello se c'e'."""
    out = {}
    for r in rows:
        n, with_section, refused_with, refused_without = out.get((r["lang"], r["series"], r["search"]), (0, 0, 0, 0))
        out[(r["lang"], r["series"], r["search"])] = (
            n + 1, with_section + r["section_in_context"],
            refused_with + (r["refused"] and r["section_in_context"]), refused_without + (r["refused"] and not r["section_in_context"]))
    return out


def question_of(row):
    """La domanda della riga (dopo "DOMANDA: " o "QUESTION: "; senza, tutto il prompt)."""
    content = row["messages"][0]["content"]
    return content.rsplit("QUESTION: " if content.startswith(EN_PROMPT) else "DOMANDA: ", 1)[-1]


def context_of(row):
    """Il contesto della riga (tra "CONTESTO: " e l'ultimo "\\n\\nDOMANDA: ", o le etichette inglesi)."""
    content = row["messages"][0]["content"]
    start, end = ("CONTEXT: ", "\n\nQUESTION: ") if content.startswith(EN_PROMPT) else ("CONTESTO: ", "\n\nDOMANDA: ")
    return content.split(start, 1)[-1].rsplit(end, 1)[0]


def dataset_lang(row):
    """Lingua del dataset della riga ("it" o "en"), dal prompt: e' la lingua attesa della risposta."""
    return "en" if row["messages"][0]["content"].startswith(EN_PROMPT) else "it"


# Parole frequenti di una sola delle due lingue, per text_lang. Escluse quelle di entrambe ("a", "in", "no", "come",
# "i", "all"). Le elisioni italiane ("dell'", "c'", "dov'") restano parole a se' dopo la divisione sull'apostrofo.
IT_WORDS = frozenset("il lo la gli le l di del dello della dei degli delle dell da dal dalla dall nel nella nei nelle nell "
                     "al alla ai alle che e è non per un una uno sono si ci c con su sul sulla tra fra cosa dove dov quando "
                     "quale quali qual quanto quanti quanta perché posso serve servono bisogna ho ha hanno essere ma o anche "
                     "più mi ti cui questo questa devo qualcosa conviene meglio".split())
EN_WORDS = frozenset("the of and is are was be to for with what where when which who how can could should do does did "
                     "there it its you your my me we this that these those at on from by any about have has need an or "
                     "not if than".split())


def text_lang(text):
    """Lingua del testo, "it" o "en", secondo le parole frequenti di IT_WORDS ed EN_WORDS; None se si equivalgono (testo
    senza parole frequenti, solo nomi o numeri). Nessuna dipendenza: basta a distinguere italiano e inglese su una frase."""
    words = re.findall(r"[^\W\d_]+", text.lower())
    it, en = sum(w in IT_WORDS for w in words), sum(w in EN_WORDS for w in words)
    return "it" if it > en else "en" if en > it else None


def question_lang(row):
    """Lingua della domanda della riga: il campo "question_lang" se c'e', altrimenti text_lang della domanda; se il
    rilevamento non decide, la lingua del dataset."""
    return row.get("question_lang") or text_lang(question_of(row)) or dataset_lang(row)


def load_test_rows(extended, dataset=None):
    """(righe di test, regioni di test). Se extended, le righe vengono dal test esteso del dataset (extended_file),
    altrimenti dalle regioni di test del dataset di training (default DEFAULT_DATASET)."""
    held_out = set(TEST_REGIONS)
    path = SFT_DIR / (extended_file(dataset) if extended else dataset or DEFAULT_DATASET)
    with open(path, encoding="utf-8") as f:
        rows = [json.loads(l) for l in f]
    return (rows if extended else [r for r in rows if r["region"] in held_out]), held_out


def score(stats, row, got):
    """Aggiunge a stats (tipo di riga -> [(ha rifiutato, risposta ok)]) la risposta got alla riga row."""
    want = row["messages"][1]["content"]
    # positivi: stesso inizio; negativi: stesso tema del rifiuto (la parte dopo ":" e' una di 4 code
    # scelte a caso da generate_sft_dataset.py, confrontarla abbasserebbe la metrica a ~25%)
    same = got[:60] == want[:60] if row["kind"].startswith("pos") else got.split(":")[0] == want.split(":")[0]
    stats.setdefault(row["kind"], []).append((got.startswith(refusal_prefix(row)), same))


# Controlli deterministici sulle istruzioni del prompt, senza giudice LLM: il training e' estrattivo, quindi la fedelta'
# al contesto si misura confrontando le frasi della risposta con il contesto.
MAX_ANSWER_CHARS = 450  # MAX_ANSWER di generate_sft_dataset.py: nessuna risposta attesa del dataset e' piu' lunga
ADHERENT_SHARE = 0.8  # parole di 5+ lettere della risposta che devono essere nel contesto per "aderente"
_BULLETS = re.compile(r"[•▸*\-–]+\s")


def _normalized(text):
    """Minuscolo, apostrofo tipografico come quello dritto, senza segni di elenco, spazi e a capo ridotti a uno spazio."""
    return " ".join(_BULLETS.sub(" ", text.lower().replace("’", "'")).split())


def _sentences(text):
    """Frasi della risposta: divise dopo . ! ? e agli a capo (voci di elenco), senza la punteggiatura finale."""
    parts = re.split(r"(?<=[.!?])\s+|\n+", text)
    return [s for s in (_normalized(p).strip(" .!?;:") for p in parts) if s]


def context_fidelity(got, context, kind):
    """"literal" se ogni frase della risposta (normalizzata) e' nel contesto; altrimenti "adherent" se ci sono tutti i
    numeri e i nomi propri (righe _near e _dep: risposte scritte con un modello di frase, VICINO e PARTENZE, anche
    quando una riga negativa risponde invece di rifiutare) o almeno ADHERENT_SHARE delle parole di 5+ lettere (gli altri
    tipi); "out" altrimenti, da leggere a mano."""
    norm_context = _normalized(context)
    if all(s in norm_context for s in _sentences(got)):
        return "literal"
    if kind.endswith(("_near", "_dep")):
        numbers = re.findall(r"\d+(?:[.,:/]\d+)*", got)
        # nomi propri: parole maiuscole di almeno due lettere non all'inizio della frase ("I" inglese escluso)
        names = [w for s in re.split(r"(?<=[.!?:])\s+|\n+", got) for w in re.findall(r"[^\W\d_][\w'’-]*", s)[1:]
                 if w[0].isupper() and len(w) > 1]
        return "adherent" if all(x in context for x in numbers + names) else "out"
    share = context_word_share(got, context)
    return "adherent" if share is None or share >= ADHERENT_SHARE else "out"


def context_word_share(got, context):
    """Quota delle parole di 5+ lettere della risposta che sono nel contesto (None senza parole lunghe): misura continua
    della fedelta', utile per i modelli che parafrasano invece di copiare (gli Ufficiali)."""
    words = re.findall(r"[^\W\d_]{5,}", got.lower())
    context_words = set(re.findall(r"[^\W\d_]{5,}", context.lower()))
    return sum(w in context_words for w in words) / len(words) if words else None


# Rifiuti scritti con parole proprie (i modelli Ufficiali non usano la frase di rifiuto del training): qualsiasi
# "il contesto non lo dice" riconoscibile, in italiano o in inglese. E' il criterio con cui le model card del v10
# confrontano i nostri modelli con gli Ufficiali.
LOOSE_REFUSAL = re.compile("|".join([
    r"\bnon (posso|riesco a) (rispondere|fornire|dare|indicare)",
    r"\bnon (contiene|fornisce|menziona|specifica|riporta|indica|include|dice|cita|parla)",
    r"\bnon (è|e'|sono) (stat[oaie] )?(menzionat|indicat|specificat|present|riportat|disponibil|fornit|elencat)",
    r"\b(non ci sono|nessuna) informazion",
    r"\bnon ho (accesso|informazioni|dati)",
    r"\binformazioni non (disponibili|presenti)",
    r"\bnon (basta|è sufficiente)",
    r"\b(does|do) not (contain|provide|mention|specify|include|say|state|list|cover|give)",
    r"\bdoesn't (contain|provide|mention|specify|include|say|state|list|cover|give)",
    r"\bno (specific |relevant )?information\b",
    r"\b(is|are) not (mentioned|specified|provided|listed|available|included|stated)",
    r"\b(cannot|can't|can not|unable to) (answer|provide|determine|find)",
    r"\b(is|isn't|is not) (not )?enough\b",
    r"\bno .{0,40}\b(is|are) (listed|mentioned)",
    r"\bno .{0,60}\bmentioned\b",
    r"\b(unrelated|not related) to the (provided )?context",
    r"\b(cannot|can't|unable to) (calculate|help|tell)",
]), re.I)
# Da togliere prima del confronto: l'eco dell'istruzione del prompt e la risposta attesa sugli orari scaduti, che dice
# "non posso indicarti le partenze" ma e' una risposta giusta.
_NOT_REFUSAL = re.compile(r"se il contesto non basta, dillo[^.]*\.?|if the context is not enough, say so[^.]*\.?"
                          r"|[^.]*(expired|scadut)[^.]*\.?", re.I)


def is_loose_refusal(answer):
    """La risposta, nei primi 300 caratteri, dice che il contesto non basta (LOOSE_REFUSAL), con parole qualsiasi."""
    return bool(LOOSE_REFUSAL.search(_NOT_REFUSAL.sub("", answer[:300])))


def instruction_checks(row, got):
    """Controlli sulle istruzioni del prompt per la risposta got alla riga row: "refused" (la frase di rifiuto del
    training o un rifiuto con parole proprie, is_loose_refusal), "fidelity" (context_fidelity) e "context_share"
    (context_word_share), None tutti e due per i rifiuti, "too_long" (oltre MAX_ANSWER_CHARS), "wrong_lang" (text_lang decide per l'altra lingua del dataset) e "think_start"
    (la risposta comincia con un blocco <think>, che i modelli senza thinking non scrivono)."""
    answer_lang = text_lang(got)
    refused = got.startswith(refusal_prefix(row)) or is_loose_refusal(got)
    context = context_of(row)
    return {"refused": refused, "fidelity": None if refused else context_fidelity(got, context, row["kind"]),
            "context_share": None if refused else context_word_share(got, context),
            "too_long": len(got) > MAX_ANSWER_CHARS, "wrong_lang": answer_lang not in (None, dataset_lang(row)),
            "think_start": got.lstrip().startswith("<think>")}


def print_instruction_report(rows):
    """Rapporto dei controlli sulle istruzioni. rows: dict con kind, question_lang, question, got e i campi di
    instruction_checks. Per tipo di riga e per lingua della domanda: rifiuti (anche con parole proprie, lo stesso
    criterio per i nostri modelli e per gli Ufficiali), risposte non rifiutate letterali, aderenti e fuori dal contesto,
    media delle parole lunghe prese dal contesto (context_share), poi oltre MAX_ANSWER_CHARS, nella lingua sbagliata e
    con <think> iniziale; poi le risposte fuori."""
    groups = {}
    for r in rows:
        groups.setdefault(("tipo", r["kind"]), []).append(r)
        groups.setdefault(("domande " + r["question_lang"], r["kind"]), []).append(r)
    print(f"controlli sulle istruzioni: rifiuti anche con parole proprie, fedelta' al contesto delle risposte non rifiutate "
          f"(letterale / aderente / fuori, parole dal contesto), oltre {MAX_ANSWER_CHARS} caratteri, lingua sbagliata, "
          f"<think> iniziale")
    for (group, kind), rs in sorted(groups.items(), key=lambda item: (item[0][0] != "tipo", item[0])):
        answered = [r for r in rs if r["fidelity"]]
        share = {f: 100 * sum(r["fidelity"] == f for r in answered) / len(answered) if answered else 0.0 for f in ("literal", "adherent")}
        shares = [r["context_share"] for r in answered if r["context_share"] is not None]
        words = f"{100 * sum(shares) / len(shares):.0f}%" if shares else "-"
        print(f"{group} {kind}: {len(rs)} righe, rifiuta {100 * sum(r['refused'] for r in rs) / len(rs):.1f}%, "
              f"{len(answered)} risposte | letterale {share['literal']:.1f}% "
              f"aderente {share['adherent']:.1f}% fuori {sum(r['fidelity'] == 'out' for r in answered)} parole dal contesto {words} | "
              f"oltre {MAX_ANSWER_CHARS}: {sum(r['too_long'] for r in rs)} | lingua sbagliata: {sum(r['wrong_lang'] for r in rs)} | "
              f"<think>: {sum(r['think_start'] for r in rs)}")
    out = [r for r in rows if r["fidelity"] == "out"]
    print(f"risposte fuori dal contesto: {len(out)}")
    for r in out:
        print(f"  {r['kind']} {r['question_lang']} | {r['question']} | {r['got'][:200]}")


def refusal_summary(stats):
    """'pos rifiutati 3% · neg rifiutati 91%' sulle righe fatte finora (per la riga di stato)."""
    out = []
    for prefix in ("pos", "neg"):
        rows = [x for kind, rs in stats.items() if kind.startswith(prefix) for x in rs]
        if rows:
            out.append(f"{prefix} rifiutati {100 * sum(x[0] for x in rows) // len(rows)}%")
    return " · ".join(out)


def pct(rows, i):
    return 100 * sum(x[i] for x in rows) / len(rows) if rows else 0.0


def kind_line(kind, rs):
    """Riga del rapporto del test esteso per un tipo di riga."""
    return f"{kind}: {len(rs)} righe | rifiuta {pct(rs, 0):.1f}%" + (" (atteso 0%)" if kind.startswith("pos") else " (atteso 100%)")


def print_report(stats, test, held_out, extended):
    if extended:
        print(f"test esteso: {len(test)} righe (domande fuori dai template di training)")
        for kind, rs in sorted(stats.items()):
            print(kind_line(kind, rs))
        return
    pos, neg = stats.get("pos", []), stats.get("neg", [])
    print(f"test: {len(test)} righe, {len(held_out)} regioni | pos {len(pos)} neg {len(neg)}")
    print(f"POS: rifiuto sbagliato {pct(pos, 0):.1f}% | inizio uguale all'atteso {pct(pos, 1):.1f}%")
    print(f"NEG: rifiuta {pct(neg, 0):.1f}% | tema del rifiuto giusto {pct(neg, 1):.1f}%")


def print_lang_report(by_lang, wrong_lang, stats):
    """Test esteso per lingua della domanda: by_lang (lingua -> stats come in score), poi le risposte nella lingua
    sbagliata (wrong_lang: tipo di riga -> risposte non nella lingua del dataset) sul totale del tipo in stats."""
    for lang, lang_stats in sorted(by_lang.items()):
        print(f"domande {lang}: {sum(len(rs) for rs in lang_stats.values())} righe")
        for kind, rs in sorted(lang_stats.items()):
            print(kind_line(kind, rs))
    wrong = [f"{kind} {n}/{len(stats[kind])}" for kind, n in sorted(wrong_lang.items()) if n]
    print("risposte nella lingua sbagliata (non quella del dataset): " + (", ".join(wrong) if wrong else "nessuna"))


def print_app_sampling_report(rows, low=0.2, high=0.8):
    """Rapporto di eval_gguf.py --app-sampling. rows: dict con kind, question_lang, question, greedy (la risposta greedy
    e' un rifiuto), p_refusal e, con --samples, sampled_refusals e samples. Per tipo di riga e per lingua della domanda:
    rifiuti greedy, rifiuti attesi nell'app (media di p_refusal) e rifiuti osservati nei campioni; poi le righe con
    p_refusal tra low e high, quelle in cui l'app a volte risponde e a volte rifiuta."""
    groups = {}
    for r in rows:
        groups.setdefault(("tipo", r["kind"]), []).append(r)
        groups.setdefault(("domande " + r["question_lang"], r["kind"]), []).append(r)
    print("campionamento dell'app: rifiuto atteso = media sulle righe della probabilita' che la risposta cominci con il rifiuto")
    for (group, kind), rs in sorted(groups.items(), key=lambda item: (item[0][0] != "tipo", item[0])):
        line = (f"{group} {kind}: {len(rs)} righe | rifiuta greedy {100 * sum(r['greedy'] for r in rs) / len(rs):.1f}% | "
                f"atteso app {100 * sum(r['p_refusal'] for r in rs) / len(rs):.1f}%")
        if "samples" in rs[0]:
            line += (f" | campionato {100 * sum(r['sampled_refusals'] for r in rs) / sum(r['samples'] for r in rs):.1f}% "
                     f"({sum(r['samples'] for r in rs)} risposte)")
        print(line)
    uncertain = sorted((r for r in rows if low <= r["p_refusal"] <= high), key=lambda r: (r["kind"], r["p_refusal"]))
    print(f"righe incerte (p del rifiuto tra {low} e {high}): {len(uncertain)}")
    for r in uncertain:
        print(f"  {r['kind']} {r['question_lang']} p={r['p_refusal']:.2f} greedy {'rifiuta' if r['greedy'] else 'risponde'} | "
              f"{r['question']}")
