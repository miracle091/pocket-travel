#!/usr/bin/env python3
"""Utilita' condivise da eval_behavior.py, eval_gguf.py e convert_gguf.py: stesse regioni di test di
train_lora.py (TEST_REGIONS), stesso formato di report, sia base che esteso (generate_eval_set.py), e
ricerca dei binari di llama.cpp.
"""
import json
import os
import shutil
import sys
from pathlib import Path


EMPTY_THINK = "<think>\n\n</think>\n\n"


def uses_empty_think(chat_template):
    """Regola del blocco <think> vuoto, la stessa di ai_chat.cpp: solo i chat template con la variabile
    enable_thinking (i Qwen3.5 del v10, addestrati con enable_thinking=False). Non bastano i blocchi <think> nel
    template: Qwen3-4B-Instruct-2507 li gestisce ma non ha la variabile."""
    return chat_template is not None and "enable_thinking" in chat_template


def chatml_prompt(user, empty_think=False):
    """Prompt a turno singolo come lo manda l'app: il formatter CHATML di llama.cpp (src/llama-chat.cpp, chiamato
    da ai_chat.cpp con use_jinja=false) per il turno utente e l'apertura del turno assistente, piu' il blocco <think>
    vuoto che ai_chat.cpp aggiunge quando uses_empty_think() vale per il template del modello."""
    return f"<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n" + (EMPTY_THINK if empty_think else "")


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
    """La domanda della riga (dopo "DOMANDA: " o "QUESTION: ")."""
    content = row["messages"][0]["content"]
    return content.rsplit("QUESTION: " if content.startswith(EN_PROMPT) else "DOMANDA: ", 1)[1]


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


def print_report(stats, test, held_out, extended):
    if extended:
        print(f"test esteso: {len(test)} righe (domande fuori dai template di training)")
        for kind, rs in sorted(stats.items()):
            print(f"{kind}: {len(rs)} righe | rifiuta {pct(rs, 0):.1f}%" + (" (atteso 0%)" if kind.startswith("pos") else " (atteso 100%)"))
        return
    pos, neg = stats.get("pos", []), stats.get("neg", [])
    print(f"test: {len(test)} righe, {len(held_out)} regioni | pos {len(pos)} neg {len(neg)}")
    print(f"POS: rifiuto sbagliato {pct(pos, 0):.1f}% | inizio uguale all'atteso {pct(pos, 1):.1f}%")
    print(f"NEG: rifiuta {pct(neg, 0):.1f}% | tema del rifiuto giusto {pct(neg, 1):.1f}%")
