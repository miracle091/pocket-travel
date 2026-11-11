#!/usr/bin/env python3
"""Test di check_answer_numbers.py: numeri della risposta non nel contesto, con i formati normalizzati (decimali,
migliaia, orari, telefoni), il tipo di numero e il contesto preso dalla riga del test.

Uso: python test_check_answer_numbers.py
"""
import unittest

from check_answer_numbers import checked, extract, number_values, unsupported
from eval_common import on_device_prompt


def texts(answer, context):
    return [u["text"] for u in unsupported(answer, context)]


class NumberValuesTest(unittest.TestCase):
    def test_decimali_e_migliaia(self):
        self.assertEqual({"2.5"}, number_values("2,5"))
        self.assertEqual({"2.5"}, number_values("2.50"))
        self.assertEqual({"1200", "1.2"}, number_values("1.200"))
        self.assertEqual({"1200", "1.2"}, number_values("1,200"))
        self.assertEqual({"1234.5"}, number_values("1.234,5"))
        self.assertEqual({"1234.5"}, number_values("1,234.5"))
        self.assertEqual({"1200"}, number_values("1 200"))
        self.assertEqual({"7"}, number_values("07"))


class UnsupportedTest(unittest.TestCase):
    def test_numeri_presenti_in_altro_formato(self):
        self.assertEqual([], texts("Il biglietto costa 2.5 € e la vetta e' a 1.200 m.", "Biglietto: 2,50 euro. Quota 1200 m."))
        self.assertEqual([], texts("The museum opens at 3 pm.", "Apertura alle 15:00."))
        self.assertEqual([], texts("Apre alle 9:00.", "Il museo apre alle 9."))
        self.assertEqual([], texts("Ci sono 3 linee.", "Ci sono tre linee del tram."))

    def test_telefoni_senza_spazi_e_prefisso(self):
        self.assertEqual([], texts("Chiama il 06 1234 5678.", "Telefono: +39 06-12345678"))
        self.assertEqual([], texts("Call +44 20 7946 0958.", "Phone 020 7946 0958"))
        self.assertEqual(["06 9999 5678"], texts("Chiama il 06 9999 5678.", "Telefono: +39 06 1234 5678"))

    def test_numeri_inventati_con_il_tipo(self):
        got = unsupported("Costa 15 €, dista 3 km, apre alle 10:30, il 5 maggio, costruita nel 1890; ci sono 40 stanze.",
                          "Costa 12 euro e dista 2 km.")
        self.assertEqual([("15", "prezzo"), ("3", "distanza"), ("10:30", "orario"), ("5", "data"), ("1890", "anno"),
                          ("40", "altro")], [(u["text"], u["kind"]) for u in got])

    def test_due_anni_e_un_intervallo_non_sono_un_telefono(self):
        self.assertEqual(["telefono"], [k for _, k, _ in extract("Tel. 0471 123456")])
        self.assertNotIn("telefono", [k for _, k, _ in extract("Terremoti nel 1703 1704 e orari 10.00-18.00")])


class CheckedTest(unittest.TestCase):
    def test_contesto_dalla_riga_del_test_e_rifiuti_saltati(self):
        test = [{"kind": "pos_para", "region": "canada",
                 "messages": [{"role": "user", "content": on_device_prompt("Il museo apre alle 9.", "Quando apre?", "it")},
                              {"role": "assistant", "content": "A"}]}]
        answers = [{"kind": "pos_para", "region": "canada", "question": "Quando apre?", "got": "Apre alle 8, per 3 ore.",
                    "question_lang": "it"}]
        rows = checked(answers, test)
        # "3" e' nelle istruzioni del prompt ("massimo 3 frasi"), che non contano come contesto
        self.assertEqual(["8", "3"], [u["text"] for u in rows[0]["unsupported"]])
        refusal = [{"kind": "neg_city", "region": "canada", "question": "Quanto costa?", "lang": "it", "context": "",
                    "got": "Il contesto non contiene informazioni sui prezzi: 3 fonti."}]
        self.assertEqual([], checked(refusal))

    def test_numero_tagliato_in_fondo_a_una_risposta_troncata(self):
        row = {"kind": "x", "region": "r", "question": "Taxi?", "lang": "en", "context": "Taxi +385 51 335 417, open 1885."}
        self.assertEqual([], checked([{**row, "got": "Taxi +385 51 335 4", "truncated": True}])[0]["unsupported"])
        got = checked([{**row, "got": "Taxi +385 51 335 4", "truncated": False}])
        self.assertEqual(["+385 51 335 4"], [u["text"] for u in got[0]["unsupported"]])
        # senza il campo: troncata se non finisce con un segno di fine frase
        self.assertEqual([], checked([{**row, "got": "Taxi +385 51 335 4"}])[0]["unsupported"])
        self.assertEqual(["4"], [u["text"] for u in checked([{**row, "got": "Taxi 4."}])[0]["unsupported"]])

    def test_numeri_della_domanda_contano_come_presenti(self):
        rows = checked([{"kind": "x", "region": "r", "question": "Cosa fare in 2 giorni?", "lang": "it",
                         "context": "Visita il duomo.", "got": "In 2 giorni visita il duomo."}])
        self.assertEqual([], rows[0]["unsupported"])


if __name__ == "__main__":
    unittest.main()
