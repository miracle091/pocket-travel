#!/usr/bin/env python3
"""Test degli esempi --emergency di generate_sft.py: ogni domanda attiva isEmergencyQuestion di TravelAssistant.kt (di cui
EMERGENCY_WORDS e' la copia), la riga dei numeri e' quella di emergencyNumbersContext e di emergencyNumbersLine, e' in testa
al contesto e la risposta; le regioni senza numeri ricevono il rifiuto con sezioni che non parlano di emergenze.

Uso: python test_sft_emergency.py
"""
import random
import re
import tempfile
import unittest
from pathlib import Path

import generate_sft
import generate_sft_dataset as it
from generate_sft import (EMERGENCY_QUESTIONS, EMERGENCY_TSV, EMERGENCY_WORDS, emergency_example, emergency_line,
                          load_emergency_numbers)

ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "feature" / "ai" / "src" / "main" / "kotlin" / "com" / "pockettravel" / "feature" / "ai" / "TravelAssistant.kt"
PIPELINE = ROOT / "tools" / "data-pipeline" / "content" / "src" / "main" / "kotlin" / "com" / "pockettravel" / "pipeline" / "GenerateEmergencyNumbers.kt"

BODIES = ["Il centro storico si visita a piedi, con piazze e chiese a pochi passi una dall'altra e musei aperti tutti i giorni.",
          "In caso di emergenza chiamare il 112, che risponde in piu' lingue.",
          "Le spiagge sono lunghe e di sabbia fine; i bagnini sono presenti in estate dalle 9 alle 19."]


def refusal(lang):
    return lambda topic: generate_sft.LANGS[lang]["refusal"](topic, "verifica.")


class QuestionsTest(unittest.TestCase):
    def test_ogni_domanda_attiva_il_blocco(self):
        for lang, kinds in EMERGENCY_QUESTIONS.items():
            self.assertEqual(set(kinds), {"general", "ambulance", "police", "fire"})
            for kind, qs in kinds.items():
                self.assertGreaterEqual(len(qs), 3)
                for q in qs:
                    self.assertIn("{r}", q)
                    self.assertTrue(EMERGENCY_WORDS.search(q.format(r="Italia")), q)

    def test_domande_diverse_da_quelle_dei_fatti_rapidi(self):
        quick = {q for qs in (generate_sft.LANGS["it"]["quick_questions"]["Numeri di emergenza"],
                              generate_sft.LANGS["en"]["quick_questions"]["Emergency numbers"]) for q in qs}
        self.assertFalse(quick & {q for kinds in EMERGENCY_QUESTIONS.values() for qs in kinds.values() for q in qs})

    @unittest.skipUnless(KOTLIN.exists(), "TravelAssistant.kt non trovato")
    def test_regex_ed_etichette_come_quelle_dell_app(self):
        source = KOTLIN.read_text(encoding="utf-8")
        pattern = re.search(r'emergencyWords = Regex\("""(.*?)"""', source).group(1)
        self.assertEqual(pattern, EMERGENCY_WORDS.pattern)
        self.assertEqual(EMERGENCY_WORDS.flags & re.I, re.I)
        context = source.split("internal fun emergencyNumbersContext", 1)[1].split("\n}\n", 1)[0]
        for labels in generate_sft.EMERGENCY_LABELS.values():
            for label in labels:
                self.assertIn(f'"{label}"', context)

    @unittest.skipUnless(PIPELINE.exists(), "GenerateEmergencyNumbers.kt non trovato")
    def test_etichette_come_quelle_della_pipeline(self):
        source = PIPELINE.read_text(encoding="utf-8")
        for labels in generate_sft.EMERGENCY_LABELS.values():
            self.assertIn("listOf(" + ", ".join(f'"{l}"' for l in labels) + ")", source)


class NumbersTest(unittest.TestCase):
    def test_riga_come_quella_dei_fatti_rapidi(self):
        numbers = load_emergency_numbers()
        self.assertEqual(emergency_line(numbers["italia"], "it"),
                         "Numeri di emergenza: Generale 112, Polizia 113, Ambulanza 118, Vigili del fuoco 115")
        self.assertEqual(emergency_line(numbers["italia"], "en"), "Emergency numbers: General 112, Police 113, Ambulance 118, Fire 115")

    def test_senza_numero_unico_niente_generale(self):
        numbers = load_emergency_numbers()
        self.assertEqual(numbers["afghanistan"][0], "")
        self.assertEqual(emergency_line(numbers["afghanistan"], "it"), "Numeri di emergenza: Polizia 119, Ambulanza 102, Vigili del fuoco 112")

    def test_regioni_senza_numero_centralizzato_fuori(self):
        numbers = load_emergency_numbers()
        self.assertNotIn("congo", numbers)
        self.assertNotIn("corea-del-nord", numbers)
        self.assertTrue(EMERGENCY_TSV.exists())
        self.assertTrue(all(len(v) == 4 and v[1] and v[2] and v[3] for v in numbers.values()))

    def test_commenti_e_righe_vuote(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "e.tsv"
            path.write_text("# commento\n\nx\t112\t113\t118\t115\tCA\t\ny\t\t\t\t\tCA\tnessuno\n", encoding="utf-8")
            self.assertEqual(load_emergency_numbers(path), {"x": ("112", "113", "118", "115")})


class ExampleTest(unittest.TestCase):
    line = "Numeri di emergenza: Generale 112, Polizia 113, Ambulanza 118, Vigili del fuoco 115"

    def test_positivo_con_il_blocco_in_testa(self):
        L = generate_sft.LANGS["it"]
        kinds = set()
        for seed in range(40):
            context, q, answer, kind = emergency_example(random.Random(seed), "it", self.line, "Italia", BODIES, L, refusal("it"))
            self.assertEqual((kind, answer), ("pos", self.line))
            self.assertTrue(context.startswith(self.line))
            self.assertLessEqual(len(context), it.MAX_CONTEXT)
            self.assertTrue(EMERGENCY_WORDS.search(q))
            kinds.add(q)
        self.assertGreater(len(kinds), 6)

    def test_blocco_inglese(self):
        line = "Emergency numbers: General 112, Police 113, Ambulance 118, Fire 115"
        context, q, answer, kind = emergency_example(random.Random(1), "en", line, "Italy", BODIES, generate_sft.LANGS["en"], refusal("en"))
        self.assertEqual(answer, line)
        self.assertTrue(context.startswith(line))

    def test_blocco_con_sezioni_lunghe_nel_limite(self):
        long_bodies = ["Una frase lunga che parla della citta'. " * 60] * 3
        for seed in range(10):
            context, *_ = emergency_example(random.Random(seed), "it", self.line, "Italia", long_bodies, generate_sft.LANGS["it"], refusal("it"))
            self.assertLessEqual(len(context), it.MAX_CONTEXT)
            self.assertTrue(context.startswith(self.line))

    def test_rifiuto_per_la_regione_senza_numeri(self):
        L = generate_sft.LANGS["it"]
        for seed in range(40):
            context, q, answer, kind = emergency_example(random.Random(seed), "it", None, "Congo", BODIES, L, refusal("it"))
            self.assertEqual(kind, "neg")
            self.assertEqual(answer, "Il contesto non contiene informazioni sui numeri di emergenza: verifica.")
            self.assertNotIn("Numeri di emergenza", context)
            self.assertFalse(EMERGENCY_WORDS.search(context.replace(L["fallback"], "")))
            self.assertNotIn("112", context)

    def test_rifiuto_inglese(self):
        L = generate_sft.LANGS["en"]
        _, _, answer, kind = emergency_example(random.Random(1), "en", None, "Congo", ["The beaches are long and sandy."], L, refusal("en"))
        self.assertEqual((kind, answer), ("neg", "The context does not contain information about emergency numbers: verifica."))

    def test_rifiuto_senza_sezioni_adatte_ha_il_contesto_di_fallback(self):
        L = generate_sft.LANGS["it"]
        context, *_ = emergency_example(random.Random(1), "it", None, "Congo", [BODIES[1]], L, refusal("it"))
        self.assertEqual(context, L["fallback"])

    def test_domande_nell_altra_lingua(self):
        L = generate_sft.LANGS["it"]
        english = {q for qs in EMERGENCY_QUESTIONS["en"].values() for q in qs}
        for seed in range(20):
            _, q, *_ = emergency_example(random.Random(seed), "it", self.line, "Italia", BODIES, L, refusal("it"), other_lang=1)
            self.assertIn(q.replace("Italia", "{r}"), english)

    def test_ripetibile(self):
        L = generate_sft.LANGS["it"]
        run = lambda: emergency_example(random.Random(5), "it", self.line, "Italia", BODIES, L, refusal("it"))
        self.assertEqual(run(), run())


if __name__ == "__main__":
    unittest.main()
