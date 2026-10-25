#!/usr/bin/env python3
"""Test di cap_repeats (generate_sft.py): al massimo SAME_ANSWER_MAX righe positive con la stessa domanda e la stessa
risposta (cambia solo il contesto); i rifiuti non si toccano. Test di drop_audit_errors: fuori le righe che audit_sft.py
segna come errori. Test di drop_measured_questions: fuori le righe con una domanda delle serie di eval_retrieval.py.

Uso: python test_sft_repeats.py
"""
import unittest
from pathlib import Path
from unittest import mock

import generate_sft


def make(lang, context, q, ans, kind="pos"):
    L = generate_sft.LANGS[lang]
    return {"messages": [{"role": "user", "content": L["prompt"](context, q)}, {"role": "assistant", "content": ans}],
            "kind": kind, "region": "francia", "category": "CIBO_BEVANDE", "translated": False}


class CapRepeatsTest(unittest.TestCase):
    def test_al_massimo_due_per_domanda_e_risposta(self):
        for lang in ("it", "en"):
            rows = [make(lang, f"Contesto {i}.", "Cosa si mangia?", "Pasta.") for i in range(5)]
            rows += [make(lang, "Contesto 9.", "Cosa si beve?", "Vino."), make(lang, "Contesto 8.", "Cosa si mangia?", "Pizza.")]
            kept = generate_sft.cap_repeats(rows, generate_sft.LANGS[lang]["prompt"])
            self.assertEqual(kept, rows[:2] + rows[5:])

    def test_rifiuti_invariati(self):
        rows = [make("it", f"Contesto {i}.", "Cosa si mangia?", "Il contesto non lo dice.", "neg") for i in range(4)]
        self.assertEqual(generate_sft.cap_repeats(rows, generate_sft.LANGS["it"]["prompt"]), rows)

    def test_righe_con_errori_dell_audit_tolte(self):
        good = make("it", "La pasta è il piatto tipico della città.", "Cosa si mangia?", "La pasta è il piatto tipico della città.")
        markup = make("it", "Clima: zona D, 1694 [[Gradi giorno", "Che clima ha?", "Clima: zona D, 1694 [[Gradi giorno")
        invented = make("it", "Bordeaux: 2 ore. Parigi: 4 ore.", "Quanto dista Lione?", "Lione dista 3 ore di treno.")
        kept, dropped = generate_sft.drop_audit_errors([good, markup, invented], "it")
        self.assertEqual(kept, [good])
        self.assertEqual([d[3] for d in dropped], ["audit-markup-residuo", "audit-non-estrattiva"])

    def test_risposte_con_un_rimando_alla_frase_rimasta_fuori_tolte(self):
        context = "Orvieto viene sommersa dai turisti ogni giorno. Quindi conviene arrivare presto."
        dangling = make("it", context, "Quando visitare Orvieto?", "Quindi conviene arrivare presto.")
        whole = make("it", context, "Quando visitare Orvieto?", context)
        refusal = make("it", context, "Dove dormire?", "Quindi il contesto non basta.", kind="neg")
        kept, dropped = generate_sft.drop_dangling_answers([dangling, whole, refusal], "it")
        self.assertEqual(kept, [whole, refusal])
        self.assertEqual([d[3] for d in dropped], ["risposta-con-rimando"])

    def test_righe_con_una_domanda_delle_serie_di_misura_tolte(self):
        same = {**make("it", "Alberghi in centro.", "Dove dormire a Maratona?", "Alberghi in centro."), "region": "citta:Maratona"}
        other = {**make("it", "Alberghi in centro.", "Dove dormire a Corinto?", "Alberghi in centro."), "region": "citta:Corinto"}
        measured = {"citta:Maratona": ["Dove dormire a Maratona?"], "grecia": ["Serve il visto?"]}
        with mock.patch("eval_retrieval.fetch_data") as fetch, mock.patch.object(generate_sft.audit_sft, "measured_questions",
                                                                                return_value=measured):
            kept, dropped = generate_sft.drop_measured_questions([same, other], Path("cache"), "it")
        fetch.assert_called_once()
        self.assertEqual(kept, [other])
        self.assertEqual(dropped, [("citta:Maratona", "riga", same["category"], "serie-di-misura")])

    def test_domanda_dal_prompt(self):
        prompt = generate_sft.LANGS["it"]["prompt"]
        self.assertEqual(generate_sft.question_of(prompt("Riga uno.\n\nRiga due.", "Dove dormo?"), prompt), "Dove dormo?")


if __name__ == "__main__":
    unittest.main()
