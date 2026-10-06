#!/usr/bin/env python3
"""Test di pick_answer (generate_sft_dataset.py): la risposta estrattiva tiene le righe della guida, cosi' voci di elenco
diverse non si fondono in una frase sola ("Aeroporto di Pescara Aeroporto di Roma Ciampino").

Uso: python test_pick_answer.py
"""
import unittest

from generate_sft_dataset import pick_answer

ARRIVARE = ("▸ In aereo\n• Aeroporto di Pescara\n• Aeroporto di Roma Ciampino\n▸ In auto\n"
            "• Dall'aeroporto si prende l'autostrada A24. Poi si esce a Magliano.")


class PickAnswerTest(unittest.TestCase):
    def test_voci_di_elenco_diverse_su_righe_diverse(self):
        answer = pick_answer(ARRIVARE, ARRIVARE, "aeroporto vicino", "ARRIVARE", "Magliano")
        self.assertEqual(answer, "Aeroporto di Pescara\nAeroporto di Roma Ciampino\nDall'aeroporto si prende l'autostrada A24.")

    def test_frasi_della_stessa_riga_unite_da_uno_spazio(self):
        body = "Il museo apre alle 9. Il museo costa 10 euro."
        self.assertEqual(pick_answer(body, body, "museo", "COSA_VEDERE", "Roma"), body)


if __name__ == "__main__":
    unittest.main()
