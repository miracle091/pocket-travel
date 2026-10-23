#!/usr/bin/env python3
"""Test di pick_answer (generate_sft_dataset.py): la risposta estrattiva tiene le righe della guida, cosi' voci di elenco
diverse non si fondono in una frase sola ("Aeroporto di Pescara Aeroporto di Roma Ciampino"); le frasi che annunciano un
elenco che non c'e' restano fuori; conta quante radici della categoria ha una frase.

Uso: python test_pick_answer.py
"""
import unittest

from generate_sft_dataset import intro_lines, pick_answer

ARRIVARE = ("▸ In aereo\n• Aeroporto di Pescara\n• Aeroporto di Roma Ciampino\n▸ In auto\n"
            "• Dall'aeroporto si prende l'autostrada A24. Poi si esce a Magliano.")


class PickAnswerTest(unittest.TestCase):
    def test_voci_di_elenco_diverse_su_righe_diverse(self):
        answer = pick_answer(ARRIVARE, ARRIVARE, "aeroporto vicino", "ARRIVARE", "Magliano")
        self.assertEqual(answer, "Aeroporto di Pescara\nAeroporto di Roma Ciampino\nDall'aeroporto si prende l'autostrada A24.")

    def test_frasi_della_stessa_riga_unite_da_uno_spazio(self):
        body = "Il museo apre alle 9. Il museo costa 10 euro."
        self.assertEqual(pick_answer(body, body, "museo", "COSA_VEDERE", "Roma"), body)

    def test_annuncio_senza_elenco_fuori_dalla_risposta(self):
        # San Marino, Valuta e acquisti: il template dei link sparisce con la pulizia e resta solo l'annuncio
        body = ("Qui di seguito i link per conoscere l'attuale cambio con le principali monete mondiali:\n\n"
                "San Marino utilizza l'Euro come valuta corrente in virtù di un accordo con l'Italia.")
        self.assertEqual(intro_lines(body), {0})
        self.assertEqual(pick_answer(body, body, "moneta", "ACQUISTI", "San Marino"),
                         "San Marino utilizza l'Euro come valuta corrente in virtù di un accordo con l'Italia.")
        self.assertTrue(pick_answer(body, body, "moneta", "ACQUISTI", "San Marino", legacy=True).startswith("Qui di seguito"))

    def test_annuncio_in_fondo_alla_riga_o_prima_di_un_sottotitolo(self):
        body = "La valuta nazionale è il Dollaro canadese (CAD). Qui di seguito i link per il cambio:\n▸ Mance\nLa mancia è del 15%."
        self.assertEqual(intro_lines(body), {0})
        answer = pick_answer(body, body, "valuta mancia", "ACQUISTI", "Canada")
        self.assertEqual(answer, "La valuta nazionale è il Dollaro canadese (CAD).\nLa mancia è del 15%.")

    def test_annuncio_con_l_elenco_resta(self):
        body = "I principali operatori sono questi:\n• BH Telecom, con la rete piu' estesa\n• HT Eronet, nel sud"
        self.assertEqual(intro_lines(body), set())
        self.assertTrue(pick_answer(body, body, "operatori", "CONNETTIVITA", "Bosnia").startswith("I principali operatori"))

    def test_conta_le_radici_della_categoria(self):
        # tre frasi con una radice di ACQUISTI (negozi) e una con quattro (valuta, euro, carta, negozi): con il conteggio
        # entra l'ultima; con legacy (una radice o niente) le prime tre, in ordine di testo
        body = ("Il negozio A apre presto. Il negozio B apre tardi. Il negozio C chiude alle 8. "
                "La valuta e' l'euro, accettato con carta in ogni negozio.")
        self.assertIn("La valuta e' l'euro", pick_answer(body, body, "info", "ACQUISTI", "Malta"))
        self.assertNotIn("valuta", pick_answer(body, body, "info", "ACQUISTI", "Malta", legacy=True))


if __name__ == "__main__":
    unittest.main()
