#!/usr/bin/env python3
"""Test di make_context (generate_sft_dataset.py): il contesto dei dataset SFT e dei set di valutazione si costruisce
come nell'app (selectContext in TravelAssistant.kt), quindi come la replica di eval_retrieval.py.

Uso: python test_make_context.py
"""
import random
import unittest

import eval_retrieval
from generate_sft_dataset import MAX_CONTEXT, make_context, question_stems

CLIMA = "Il clima e' temperato.\nIn inverno nevica spesso in collina.\nLe estati sono calde e afose."


class MakeContextTest(unittest.TestCase):
    def test_radici_della_domanda_senza_il_nome(self):
        self.assertEqual(question_stems("Fa caldo a Rimini d'estate?", "Rimini"), {"caldo", "estat"})

    def test_radici_come_l_app(self):
        for question, name in [("Com'è la città vecchia di Forlì dell'isola?", "Forlì"), ("Quale valuta si usa a San Marino?", "San Marino")]:
            region = name.lower().replace(" ", "-")
            app = eval_retrieval.focus_stems(eval_retrieval.fts_query(question, region), name)
            self.assertEqual(question_stems(question, name), app)

    def test_sezioni_corte_intere_unite_da_riga_vuota(self):
        context = make_context(random.Random(1), ["uno", "due"])
        self.assertEqual(sorted(context.split("\n\n")), ["due", "uno"])

    def test_sezione_troppo_lunga_lascia_i_paragrafi_della_domanda(self):
        context = make_context(random.Random(1), [CLIMA], "Fa caldo in estate?", max_chars=60)
        self.assertEqual(context, "Il clima e' temperato.\nLe estati sono calde e afose.")

    def test_senza_domanda_i_primi_paragrafi_che_ci_stanno(self):
        context = make_context(random.Random(1), [CLIMA], max_chars=60)
        self.assertEqual(context, "Il clima e' temperato.\nIn inverno nevica spesso in collina.")

    def test_mai_oltre_il_limite(self):
        bodies = ["a" * 1500, "b" * 1500, "c" * 1500]
        self.assertLessEqual(len(make_context(random.Random(3), bodies, "domanda qualunque")), MAX_CONTEXT)

    def test_come_la_replica_dell_app(self):
        # Stesso ordine delle sezioni (quello dopo il mescolamento): stesso contesto di eval_retrieval.select_context.
        bodies = ["Fondata dai Romani.\n" + "B" * 1500 + "\nNel Medioevo fu libero comune.", "A" * 900, CLIMA]
        question, name = "Chi ha fondato Rimini nel Medioevo?", "Rimini"
        order = list(bodies)
        random.Random(5).shuffle(order)
        stems = eval_retrieval.focus_stems(eval_retrieval.fts_query(question, "italia"), name)
        self.assertEqual(make_context(random.Random(5), bodies, question, name), eval_retrieval.select_context(order, stems))


if __name__ == "__main__":
    unittest.main()
