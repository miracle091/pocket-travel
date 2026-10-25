#!/usr/bin/env python3
"""Test delle parti di eval_common.py usate da eval_gguf.py --contexts: il prompt come PromptTemplates.kt, il
riconoscimento dei rifiuti e il conteggio dei rifiuti con e senza la sezione attesa nel contesto; il testo ChatML
(chatml_prompt, chatml_answer) confrontato con il formatter CHATML di llama.cpp e con il blocco <think> di ai_chat.cpp.

Uso: python test_eval_common.py
"""
import io
import json
import os
import re
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import eval_common
from eval_common import EMPTY_THINK, context_report, is_refusal, on_device_prompt

REPO = Path(__file__).resolve().parents[3]
PROMPT_TEMPLATES = (REPO / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/PromptTemplates.kt").read_text(encoding="utf-8")
LLAMA_CHAT = (REPO / "third-party/llama-cpp/src/llama-chat.cpp").read_text(encoding="utf-8")
AI_CHAT = (REPO / "feature/ai/src/main/cpp/ai_chat.cpp").read_text(encoding="utf-8")


def kotlin_prompt(branch, context, question):
    """Il testo di onDevicePrompt in PromptTemplates.kt per un ramo ("en" o "it"), con contesto e domanda."""
    body = PROMPT_TEMPLATES.split("fun onDevicePrompt(")[1].split('if (language == "en") {')[1].split("fun emptyContext(")[0]
    en, it = body.split("} else {")
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', en if branch == "en" else it)
    return "".join(literals).replace("\\n", "\n").replace("$context", context).replace("$question", question)


class PromptTest(unittest.TestCase):
    def test_prompt_come_l_app(self):
        for lang in ("it", "en"):
            self.assertEqual(on_device_prompt("Il treno arriva.", "Come arrivo?", lang),
                             kotlin_prompt(lang, "Il treno arriva.", "Come arrivo?"))

    def test_contesto_vuoto_come_l_app(self):
        en, it = re.search(r'emptyContext.*?"en"\) "([^"]*)" else "([^"]*)"', PROMPT_TEMPLATES, re.S).groups()
        self.assertEqual(on_device_prompt("", "Q?", "en"), kotlin_prompt("en", en, "Q?"))
        self.assertEqual(on_device_prompt("  ", "D?", "it"), kotlin_prompt("it", it, "D?"))


class MoveAnswerTest(unittest.TestCase):
    def test_paragrafo_della_risposta_spostato(self):
        from eval_common import move_answer
        context = "Primo.\nRisposta: alberghi in centro.\nTerzo.\n\nQuarto.\nQuinto."
        answer = "Risposta: alberghi in centro."
        self.assertEqual(move_answer(context, answer, "start").split("\n")[0], answer)
        self.assertEqual(move_answer(context, answer, "end").split("\n")[-1], answer)
        middle = move_answer(context, answer, "middle").split("\n")
        self.assertEqual(middle.index(answer), 2)
        # Stesse righe, solo in un altro ordine
        self.assertEqual(sorted(middle), sorted(line for line in context.split("\n") if line))
        self.assertIsNone(move_answer(context, "Non c'e'.", "start"))


class RefusalTest(unittest.TestCase):
    def test_rifiuto_per_lingua(self):
        self.assertTrue(is_refusal("Il contesto non contiene informazioni sugli alberghi.", "it"))
        self.assertTrue(is_refusal("The context does not contain information about hotels.", "en"))
        self.assertFalse(is_refusal("A Rimini ci sono alberghi sul lungomare.", "it"))
        self.assertFalse(is_refusal("Il contesto non contiene informazioni sugli alberghi.", "en"))

    def test_rifiuti_con_e_senza_la_sezione(self):
        row = lambda search, section, refused: {"lang": "it", "series": "direct", "search": search,
                                                "section_in_context": section, "refused": refused}
        rows = [row("word-lists", True, False), row("word-lists", True, True), row("word-lists", False, True),
                row("word-lists", False, False), row("before-rag", False, True)]
        self.assertEqual(context_report(rows), {("it", "direct", "word-lists"): (4, 2, 1, 1),
                                                ("it", "direct", "before-rag"): (1, 0, 0, 1)})


class FakeTokenizer:
    """Tokenizer minimo con un ChatML a due turni; il blocco <think> vuoto compare solo se richiesto."""

    def __init__(self, chat_template, think_in_assistant=False):
        self.chat_template = chat_template
        self.think_in_assistant = think_in_assistant

    def apply_chat_template(self, messages, add_generation_prompt=False, tokenize=False, **kwargs):
        text = "".join(f"<|im_start|>{m['role']}\n{m['content']}<|im_end|>\n" for m in messages[:1])
        if add_generation_prompt:
            return text + "<|im_start|>assistant\n"
        for m in messages[1:]:
            text += "<|im_start|>assistant\n" + (EMPTY_THINK if self.think_in_assistant else "") + m["content"] + "<|im_end|>\n"
        return text


MESSAGES = [{"role": "user", "content": "Ciao"}, {"role": "assistant", "content": "Salve"}]


class ChatPromptTest(unittest.TestCase):
    def test_template_senza_thinking(self):
        prompt, answer = eval_common.chat_prompt_and_answer(FakeTokenizer("chatml"), MESSAGES)
        self.assertEqual("<|im_start|>user\nCiao<|im_end|>\n<|im_start|>assistant\n", prompt)
        self.assertEqual("Salve<|im_end|>\n", answer)

    def test_template_con_thinking_aggiunge_il_blocco_al_prompt_non_alla_risposta(self):
        tok = FakeTokenizer("... enable_thinking ...", think_in_assistant=True)
        prompt, answer = eval_common.chat_prompt_and_answer(tok, MESSAGES)
        self.assertTrue(prompt.endswith("assistant\n" + EMPTY_THINK))
        self.assertEqual("Salve<|im_end|>\n", answer)

    def test_prompt_gia_con_blocco_non_lo_duplica(self):
        tok = FakeTokenizer("enable_thinking", think_in_assistant=True)
        original = tok.apply_chat_template
        tok.apply_chat_template = lambda m, add_generation_prompt=False, **k: (
            original(m, add_generation_prompt, **k) + EMPTY_THINK if add_generation_prompt else original(m, **k))
        prompt, _ = eval_common.chat_prompt_and_answer(tok, MESSAGES)
        self.assertEqual(1, prompt.count("<think>"))

    def test_un_solo_messaggio_da_la_risposta_vuota(self):
        prompt, answer = eval_common.chat_prompt_and_answer(FakeTokenizer("chatml"), MESSAGES[:1])
        self.assertEqual("", answer)
        self.assertTrue(prompt.endswith("<|im_start|>assistant\n"))

    def test_template_che_non_estende_il_prompt_e_un_errore(self):
        tok = FakeTokenizer("chatml")
        tok.apply_chat_template = lambda m, add_generation_prompt=False, **k: "prompt" if add_generation_prompt else "diverso"
        with self.assertRaises(AssertionError):
            eval_common.chat_prompt_and_answer(tok, MESSAGES)


def llama_chatml(messages, add_ass):
    """Uscita del formatter CHATML di llama.cpp (ramo LLM_CHAT_TEMPLATE_CHATML di src/llama-chat.cpp), ricostruita
    dalle sue istruzioni `ss << ...`: una per messaggio nel ciclo, una per l'apertura del turno assistente."""
    branch = LLAMA_CHAT.split("if (tmpl == LLM_CHAT_TEMPLATE_CHATML) {")[1].split("} else if")[0]
    loop, ass = branch.split("if (add_ass)")
    fields = {"message->role": "role", "message->content": "content"}

    def render(source, message=None):
        parts = (p.strip() for p in re.search(r"ss << (.*?);", source, re.S).group(1).split(" << "))
        return "".join(p[1:-1].replace("\\n", "\n") if p.startswith('"') else message[fields[p]] for p in parts)

    return "".join(render(loop, m) for m in messages) + (render(ass) if add_ass else "")


class ChatmlTest(unittest.TestCase):
    """chatml_prompt e chatml_answer contro il formatter CHATML di llama.cpp e il blocco <think> di ai_chat.cpp."""
    user = {"role": "user", "content": "Contesto:\nA tavola.\n\nDomanda: Cosa mangio?"}
    assistant = {"role": "assistant", "content": "La torta Titano."}

    def test_prompt_uguale_al_formatter_di_llama_cpp(self):
        self.assertEqual(eval_common.chatml_prompt(self.user["content"]), llama_chatml([self.user], add_ass=True))

    def test_prompt_con_turno_system_uguale_al_formatter_di_llama_cpp(self):
        system = {"role": "system", "content": "Rispondi solo dal contesto."}
        self.assertEqual(eval_common.chatml_prompt(self.user["content"], system=system["content"]),
                         llama_chatml([system, self.user], add_ass=True))

    def test_prompt_e_risposta_uguali_al_formatter_di_llama_cpp(self):
        self.assertEqual(eval_common.chatml_prompt(self.user["content"]) + eval_common.chatml_answer(self.assistant["content"]),
                         llama_chatml([self.user, self.assistant], add_ass=False))

    def test_blocco_think_uguale_a_quello_di_ai_chat_cpp(self):
        block = re.search(r'EMPTY_THINK\s*=\s*"(<think>[^"]*)";', AI_CHAT).group(1).replace("\\n", "\n")
        self.assertIn("text += EMPTY_THINK;", AI_CHAT)
        self.assertEqual(EMPTY_THINK, block)
        self.assertEqual(eval_common.chatml_prompt("D", empty_think=True), llama_chatml([{"role": "user", "content": "D"}], True) + block)

    def test_regola_del_blocco_think_come_ai_chat_cpp(self):
        self.assertIn('std::strstr(chat_template, "enable_thinking")', AI_CHAT)
        self.assertTrue(eval_common.uses_empty_think("{% if enable_thinking is defined %}...{% endif %}"))
        self.assertFalse(eval_common.uses_empty_think("{% if '<think>' in content %}...{% endif %}"))  # Qwen3-4B-Instruct-2507
        self.assertFalse(eval_common.uses_empty_think(None))


class FindLlamaBinTest(unittest.TestCase):
    def test_cerca_nelle_sottocartelle_di_build_e_con_estensione_exe(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "build" / "bin" / "Release"
            target.mkdir(parents=True)
            exe = target / "llama-quantize.exe"
            exe.write_text("", encoding="utf-8")
            self.assertEqual(str(exe), eval_common.find_llama_bin("llama-quantize", None, tmp))

    def test_cartella_data_ha_la_precedenza_sul_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = Path(tmp) / "llama-server"
            exe.write_text("", encoding="utf-8")
            with mock.patch("shutil.which", return_value="/usr/bin/llama-server"):
                self.assertEqual(str(exe), eval_common.find_llama_bin("llama-server", tmp))

    def test_ripiega_sul_path(self):
        with mock.patch("shutil.which", return_value="/usr/bin/llama-server"):
            self.assertEqual("/usr/bin/llama-server", eval_common.find_llama_bin("llama-server", None))

    def test_non_trovato_esce_con_messaggio(self):
        with mock.patch("shutil.which", return_value=None):
            with self.assertRaises(SystemExit) as ctx:
                eval_common.find_llama_bin("llama-server", "/nessuna/cartella")
        self.assertIn("llama-server non trovato", str(ctx.exception))


class LlamaEnvTest(unittest.TestCase):
    def test_extra_path_va_davanti_al_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "extra-path.txt").write_text("/hip/bin\n/rocm/bin", encoding="utf-8")
            with mock.patch.dict(os.environ, {"PATH": "/usr/bin"}):
                env = eval_common.llama_env(str(Path(tmp) / "llama-server.exe"))
            self.assertEqual(os.pathsep.join(["/hip/bin", "/rocm/bin", "/usr/bin"]), env["PATH"])

    def test_senza_extra_path_l_ambiente_resta_uguale(self):
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"PATH": "/usr/bin"}):
                self.assertEqual("/usr/bin", eval_common.llama_env(str(Path(tmp) / "llama-server"))["PATH"])


class RunDatasetTest(unittest.TestCase):
    def test_dataset_da_run_json_o_di_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            merged = Path(tmp) / "run" / "merged"
            merged.mkdir(parents=True)
            self.assertEqual(eval_common.DEFAULT_DATASET, eval_common.run_dataset(merged))
            (Path(tmp) / "run" / "run.json").write_text(json.dumps({"dataset": "x.en.jsonl"}), encoding="utf-8")
            self.assertEqual("x.en.jsonl", eval_common.run_dataset(merged))
            (Path(tmp) / "run" / "run.json").write_text("{}", encoding="utf-8")
            self.assertEqual(eval_common.DEFAULT_DATASET, eval_common.run_dataset(merged))

    def test_file_del_test_esteso(self):
        self.assertEqual("eval_extended.en.jsonl", eval_common.extended_file("a.v10.en.jsonl"))
        self.assertEqual("eval_extended.jsonl", eval_common.extended_file("a.v10.it.jsonl"))
        self.assertEqual("eval_extended.jsonl", eval_common.extended_file(None))


def row(kind, question="Q", answer="A", lang="it", region="canada", category="c"):
    prompt = ("You are an offline travel guide.\n\nQUESTION: " if lang == "en" else "Sei una guida.\n\nDOMANDA: ") + question
    return {"kind": kind, "region": region, "category": category,
            "messages": [{"role": "user", "content": prompt}, {"role": "assistant", "content": answer}]}


class RowsTest(unittest.TestCase):
    def test_rifiuto_per_lingua_della_riga(self):
        self.assertEqual(eval_common.REFUSAL, eval_common.refusal_prefix(row("pos")))
        self.assertEqual(eval_common.REFUSAL_EN, eval_common.refusal_prefix(row("pos", lang="en")))

    def test_domanda_con_accenti_e_con_etichetta_ripetuta(self):
        self.assertEqual("Dov'è la città?", eval_common.question_of(row("pos", "Dov'è la città?")))
        self.assertEqual("What?", eval_common.question_of(row("pos", "What?", lang="en")))
        self.assertEqual("b", eval_common.question_of(row("pos", "a\n\nDOMANDA: b")))


class ScoreTest(unittest.TestCase):
    def test_positivo_stesso_inizio_di_60_caratteri(self):
        stats = {}
        want = "x" * 60 + "fine A"
        eval_common.score(stats, row("pos", answer=want), "x" * 60 + "fine B")
        eval_common.score(stats, row("pos", answer=want), "y" * 60)
        self.assertEqual([(False, True), (False, False)], stats["pos"])

    def test_positivo_rifiutato(self):
        stats = {}
        eval_common.score(stats, row("pos", answer="Roma."), eval_common.REFUSAL + ": niente")
        self.assertEqual([(True, False)], stats["pos"])

    def test_negativo_conta_solo_il_tema_prima_dei_due_punti(self):
        stats = {}
        want = eval_common.REFUSAL + " sugli hotel: prova altrove"
        eval_common.score(stats, row("neg", answer=want), eval_common.REFUSAL + " sugli hotel: altra coda")
        eval_common.score(stats, row("neg", answer=want), eval_common.REFUSAL + " sui treni: altra coda")
        eval_common.score(stats, row("neg", answer=want), "Risposta inventata")
        self.assertEqual([(True, True), (True, False), (False, False)], stats["neg"])

    def test_negativo_inglese(self):
        stats = {}
        eval_common.score(stats, row("neg", answer=eval_common.REFUSAL_EN + " on x: y", lang="en"),
                          eval_common.REFUSAL_EN + " on x: z")
        self.assertEqual([(True, True)], stats["neg"])


class ReportTest(unittest.TestCase):
    def test_riassunto_vuoto_e_parziale(self):
        self.assertEqual("", eval_common.refusal_summary({}))
        self.assertEqual("neg rifiutati 50%", eval_common.refusal_summary({"neg": [(True, True), (False, False)]}))

    def test_riassunto_raggruppa_i_tipi_con_lo_stesso_prefisso(self):
        stats = {"pos": [(False, True)] * 3 + [(True, False)], "pos_x": [(False, True)], "neg": [(True, True)]}
        self.assertEqual("pos rifiutati 20% · neg rifiutati 100%", eval_common.refusal_summary(stats))

    def test_percentuale_su_lista_vuota_e_zero(self):
        self.assertEqual(0.0, eval_common.pct([], 0))
        self.assertEqual(50.0, eval_common.pct([(True, False), (False, False)], 0))

    def test_rapporto_base_e_esteso(self):
        stats = {"pos": [(False, True), (True, False)], "neg": [(True, True)]}
        out = io.StringIO()
        with redirect_stdout(out):
            eval_common.print_report(stats, [1, 2, 3], {"a", "b"}, False)
            eval_common.print_report({"pos_a": [(False, True)], "neg_b": [(True, True)]}, [1, 2], set(), True)
        text = out.getvalue()
        self.assertIn("test: 3 righe, 2 regioni | pos 2 neg 1", text)
        self.assertIn("POS: rifiuto sbagliato 50.0% | inizio uguale all'atteso 50.0%", text)
        self.assertIn("NEG: rifiuta 100.0% | tema del rifiuto giusto 100.0%", text)
        self.assertIn("neg_b: 1 righe | rifiuta 100.0% (atteso 100%)", text)
        self.assertIn("pos_a: 1 righe | rifiuta 0.0% (atteso 0%)", text)

    def test_rapporto_senza_righe_non_divide_per_zero(self):
        with redirect_stdout(io.StringIO()):
            eval_common.print_report({}, [], set(), False)


class LangTest(unittest.TestCase):
    def test_lingua_dalle_parole_frequenti(self):
        self.assertEqual("it", eval_common.text_lang("Dov'è l'ospedale più vicino a Roma?"))
        self.assertEqual("it", eval_common.text_lang("Il contesto non contiene informazioni sugli alberghi."))
        self.assertEqual("en", eval_common.text_lang("Where is the nearest hospital in Rome?"))
        self.assertEqual("en", eval_common.text_lang("How do I stay connected in Antartide?"))  # nome italiano, frase inglese
        self.assertIsNone(eval_common.text_lang("Roma, 17:30"))
        self.assertIsNone(eval_common.text_lang(""))

    def test_lingua_della_domanda_campo_rilevamento_e_ripiego(self):
        self.assertEqual("en", eval_common.question_lang(row("pos", "What should I eat in Canada?")))
        self.assertEqual("it", eval_common.question_lang(row("pos", "Cosa si mangia in Canada?", lang="en")))
        self.assertEqual("it", eval_common.question_lang(row("pos", "Canada?")))  # indecisa: lingua del dataset
        self.assertEqual("en", eval_common.question_lang(row("pos", "Canada?", lang="en")))
        self.assertEqual("en", eval_common.question_lang({**row("pos", "Cosa si mangia?"), "question_lang": "en"}))
        self.assertEqual("en", eval_common.dataset_lang(row("pos", lang="en")))
        self.assertEqual("it", eval_common.dataset_lang(row("pos")))

    def test_rapporto_per_lingua_e_lingua_sbagliata(self):
        stats = {"pos_a": [(False, True)] * 3, "neg_b": [(True, True)]}
        by_lang = {"it": {"pos_a": [(False, True)] * 2, "neg_b": [(True, True)]}, "en": {"pos_a": [(False, True)]}}
        out = io.StringIO()
        with redirect_stdout(out):
            eval_common.print_lang_report(by_lang, {"pos_a": 1, "neg_b": 0}, stats)
            eval_common.print_lang_report(by_lang, {"pos_a": 0}, stats)
        text = out.getvalue()
        self.assertIn("domande en: 1 righe\npos_a: 1 righe | rifiuta 0.0% (atteso 0%)\ndomande it: 3 righe\n", text)
        self.assertIn("neg_b: 1 righe | rifiuta 100.0% (atteso 100%)", text)
        self.assertIn("risposte nella lingua sbagliata (non quella del dataset): pos_a 1/3\n", text)
        self.assertIn("risposte nella lingua sbagliata (non quella del dataset): nessuna\n", text)

    def test_rapporto_del_campionamento_dell_app(self):
        rows = [{"kind": "neg_city", "question_lang": "it", "question": "D1?", "greedy": True, "p_refusal": 0.9},
                {"kind": "neg_city", "question_lang": "en", "question": "Q2?", "greedy": False, "p_refusal": 0.3},
                {"kind": "pos_near", "question_lang": "it", "question": "D3?", "greedy": False, "p_refusal": 0.0}]
        out = io.StringIO()
        with redirect_stdout(out):
            eval_common.print_app_sampling_report(rows)
        text = out.getvalue()
        self.assertIn("tipo neg_city: 2 righe | rifiuta greedy 50.0% | atteso app 60.0%\n", text)
        self.assertIn("tipo pos_near: 1 righe | rifiuta greedy 0.0% | atteso app 0.0%\n", text)
        self.assertIn("domande en neg_city: 1 righe | rifiuta greedy 0.0% | atteso app 30.0%\n", text)
        self.assertLess(text.index("tipo pos_near"), text.index("domande en"))  # prima i tipi, poi le lingue
        self.assertIn("righe incerte (p del rifiuto tra 0.2 e 0.8): 1\n  neg_city en p=0.30 greedy risponde | Q2?", text)
        self.assertNotIn("campionato", text)

    def test_rapporto_con_i_campioni(self):
        rows = [{"kind": "neg_city", "question_lang": "it", "question": "D?", "greedy": True, "p_refusal": 0.5,
                 "samples": 10, "sampled_refusals": 4},
                {"kind": "neg_city", "question_lang": "it", "question": "E?", "greedy": True, "p_refusal": 1.0,
                 "samples": 10, "sampled_refusals": 10}]
        out = io.StringIO()
        with redirect_stdout(out):
            eval_common.print_app_sampling_report(rows)
        self.assertIn("tipo neg_city: 2 righe | rifiuta greedy 100.0% | atteso app 75.0% | campionato 70.0% (20 risposte)",
                      out.getvalue())


def context_row(kind, context, question="Cosa vedere?", lang="it"):
    """Riga con il prompt vero dell'app (on_device_prompt), per i controlli sul contesto."""
    return {"kind": kind, "region": "canada", "messages": [{"role": "user", "content": on_device_prompt(context, question, lang)},
                                                           {"role": "assistant", "content": "A"}]}


CONTEXT = ("▸ Cosa vedere\nIl Museo Reale si trova in centro. L’ingresso costa 12 euro.\n"
           "• Torre CN — aperta tutti i giorni\n\nDOMANDA: non e' la domanda")


class InstructionChecksTest(unittest.TestCase):
    def test_contesto_della_riga_anche_con_etichetta_ripetuta_e_in_inglese(self):
        self.assertEqual(CONTEXT, eval_common.context_of(context_row("pos_para", CONTEXT)))
        self.assertEqual("Big museum.", eval_common.context_of(context_row("pos_para", "Big museum.", "What?", "en")))

    def test_letterale_con_maiuscole_apostrofi_ed_elenchi_diversi(self):
        got = "il museo reale si trova in centro. L'ingresso costa 12 euro!\n- Torre CN — aperta tutti i giorni"
        self.assertEqual("literal", eval_common.context_fidelity(got, eval_common.context_of(context_row("pos_para", CONTEXT)), "pos_para"))

    def test_aderente_con_almeno_l_80_per_cento_delle_parole_lunghe(self):
        context = eval_common.context_of(context_row("pos_para", CONTEXT))
        self.assertEqual("adherent", eval_common.context_fidelity("Il Museo Reale costa poco, si trova in centro.", context, "pos_para"))
        self.assertEqual("out", eval_common.context_fidelity("Il Colosseo e il Pantheon sono bellissimi.", context, "pos_para"))

    def test_vicino_e_partenze_con_numeri_e_nomi_del_contesto(self):
        context = "Luoghi vicini: Chapel Car Park (26 m), Victoria Road (82 m).\nPartenze: 15:13 (in 2 min) Tram 8"
        good = "Entro 150 m dalla tua posizione i più vicini sono Chapel Car Park (26 m) e Victoria Road (82 m)."
        self.assertEqual("out", eval_common.context_fidelity(good, context, "pos_near"))  # 150 non e' nel contesto
        self.assertEqual("adherent", eval_common.context_fidelity(good.replace("Entro 150 m", "Vicino"), context, "pos_near"))
        self.assertEqual("out", eval_common.context_fidelity("Vicino c'e' Mill Car Park (66 m).", context, "pos_near"))
        self.assertEqual("adherent", eval_common.context_fidelity("The next one is line 8 at 15:13 (in 2 min).", context, "neg_dep"))
        self.assertEqual("adherent", eval_common.context_fidelity("Sorry, I can't tell: Tram 8 at 15:13.", context, "pos_dep"))  # "I" non e' un nome

    def test_controlli_della_risposta(self):
        r = context_row("pos_para", CONTEXT)
        self.assertEqual({"refused": False, "fidelity": "literal", "context_share": 1.0, "too_long": False, "wrong_lang": False,
                          "think_start": False}, eval_common.instruction_checks(r, "Il Museo Reale si trova in centro."))
        refusal = eval_common.instruction_checks(r, eval_common.REFUSAL + " sui musei: altrove")
        self.assertEqual((True, None, None), (refusal["refused"], refusal["fidelity"], refusal["context_share"]))
        # rifiuto con parole proprie, come gli Ufficiali: non e' una risposta fuori dal contesto
        own_words = eval_common.instruction_checks(r, "Mi dispiace, il contesto non menziona i ristoranti della citta'.")
        self.assertEqual((True, None), (own_words["refused"], own_words["fidelity"]))
        self.assertEqual(0.5, eval_common.instruction_checks(r, "Il Museo Reale espone quadri.")["context_share"])
        self.assertTrue(eval_common.instruction_checks(r, "x " * 226)["too_long"])
        self.assertFalse(eval_common.instruction_checks(r, "x" * eval_common.MAX_ANSWER_CHARS)["too_long"])
        self.assertTrue(eval_common.instruction_checks(r, "The museum is in the centre and it is open.")["wrong_lang"])
        self.assertTrue(eval_common.instruction_checks(r, "  <think>\n\n</think>\n\nIl Museo Reale")["think_start"])

    def test_rifiuti_con_parole_proprie(self):
        for answer in ("Il contesto fornito non contiene informazioni sugli orari.", "Non posso rispondere con queste informazioni.",
                       "Non ci sono informazioni sui musei.", "The context does not mention any hospital.",
                       "I cannot answer this based on the context.", "No information about hotels is mentioned."):
            self.assertTrue(eval_common.is_loose_refusal(answer), answer)
        for answer in ("Il Museo Reale apre alle 9.", "The museum opens at 9.",
                       "Gli orari dei mezzi pubblici installati sono scaduti, quindi non posso indicarti le partenze.",
                       "Rispondo: se il contesto non basta, dillo esplicitamente. Il museo apre alle 9.",
                       "x" * 300 + " il contesto non contiene altro"):  # solo i primi 300 caratteri
            self.assertFalse(eval_common.is_loose_refusal(answer), answer)

    def test_contesto_nel_turno_system_come_qwen_agent(self):
        system, user = eval_common.knowledge_turns(on_device_prompt("Il museo apre alle 9.\n\nDOMANDA: finta", "Quando apre?", "it"))
        self.assertEqual("# Knowledge Base\n\n## The content from travel guide:\n\n```\nIl museo apre alle 9.\n\nDOMANDA: finta\n```", system)
        self.assertEqual(on_device_prompt("X", "Quando apre?", "it").split("CONTESTO: ")[0].rstrip() + "\n\nDOMANDA: Quando apre?", user)
        _, user_en = eval_common.knowledge_turns(on_device_prompt("Opens at 9.", "When?", "en"))
        self.assertTrue(user_en.startswith("You are an offline travel guide.\n") and user_en.endswith("say so explicitly.\n\nQUESTION: When?"))

    def test_soglia_della_lunghezza_come_il_dataset(self):
        source = (Path(__file__).parent / "generate_sft_dataset.py").read_text(encoding="utf-8")
        self.assertIn(f"MAX_ANSWER = {eval_common.MAX_ANSWER_CHARS}\n", source)

    def test_rapporto_dei_controlli(self):
        def checked(kind, lang, fidelity, share=None, **flags):
            return {"kind": kind, "question_lang": lang, "question": "D?", "got": "Risposta inventata", "refused": fidelity is None,
                    "fidelity": fidelity, "context_share": share, "too_long": False, "wrong_lang": False, "think_start": False,
                    **flags}
        rows = [checked("pos_para", "it", "literal", 1.0), checked("pos_para", "en", "out", 0.5, too_long=True),
                checked("neg_city", "it", None, think_start=True), checked("neg_city", "it", None, wrong_lang=True)]
        out = io.StringIO()
        with redirect_stdout(out):
            eval_common.print_instruction_report(rows)
        text = out.getvalue()
        self.assertIn("tipo pos_para: 2 righe, rifiuta 0.0%, 2 risposte | letterale 50.0% aderente 0.0% fuori 1 parole dal "
                      "contesto 75% | oltre 450: 1 | lingua sbagliata: 0 | <think>: 0\n", text)
        self.assertIn("tipo neg_city: 2 righe, rifiuta 100.0%, 0 risposte | letterale 0.0% aderente 0.0% fuori 0 parole dal "
                      "contesto - | oltre 450: 0 | lingua sbagliata: 1 | <think>: 1\n", text)
        self.assertIn("domande en pos_para: 1 righe, rifiuta 0.0%, 1 risposte | letterale 0.0%", text)
        self.assertLess(text.index("tipo pos_para"), text.index("domande en"))
        self.assertIn("risposte fuori dal contesto: 1\n  pos_para en | D? | Risposta inventata\n", text)


class LoadTestRowsTest(unittest.TestCase):
    def test_base_filtra_le_regioni_di_test_ed_esteso_le_prende_tutte(self):
        with tempfile.TemporaryDirectory() as tmp:
            rows = [row("pos", region="canada"), row("pos", region="italia"), row("neg", region="palau")]
            for name in (eval_common.DEFAULT_DATASET, "eval_extended.jsonl"):
                (Path(tmp) / name).write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")
            with mock.patch.object(eval_common, "SFT_DIR", Path(tmp)):
                base, held_out = eval_common.load_test_rows(False)
                ext, _ = eval_common.load_test_rows(True)
        self.assertEqual(["canada", "palau"], [r["region"] for r in base])
        self.assertEqual(eval_common.TEST_REGIONS, held_out)
        self.assertEqual(3, len(ext))

    def test_le_regioni_di_test_sono_fisse(self):
        self.assertEqual(11, len(eval_common.TEST_REGIONS))


if __name__ == "__main__":
    unittest.main()
