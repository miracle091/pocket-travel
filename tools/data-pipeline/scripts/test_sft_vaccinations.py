#!/usr/bin/env python3
"""Test di vaccination_answers (generate_sft.py): le risposte stanno entro MAX_ANSWER senza perdere certificati.

Uso: python test_sft_vaccinations.py
"""
import unittest

import generate_sft
import generate_sft_dataset as it

LONG_CERT = ("Febbre gialla (in uscita): richiesto all'uscita da Nigeria; da 9 mesi di età; dal 2025 il certificato serve a "
             "tutti in ingresso e in uscita, compresi i bambini sotto l'anno con un'esenzione medica scritta")
CHECK = "Verifica sempre le regole ufficiali prima di partire."


def summary(certs, rec):
    return "\n".join(["Vaccinazioni per un viaggio dall'Italia in Nigeria", "Certificati richiesti:"]
                     + [f"- {c}" for c in certs] + [rec, CHECK])


class VaccinationAnswersTest(unittest.TestCase):
    def test_troppo_lunga_senza_le_raccomandate(self):
        rec = "Raccomandate per la destinazione: Epatite A, Tifo, Rabbia, Colera, Meningite, Encefalite giapponese, Epatite B, Influenza, Morbillo, Varicella."
        answers = generate_sft.vaccination_answers(summary([LONG_CERT, "Poliomielite: richiesto a chi resta oltre 4 settimane"], rec), "it")
        for kind, answer in answers.items():
            self.assertLessEqual(len(answer), it.MAX_ANSWER, kind)
        self.assertIn("any", answers)
        self.assertIn("Poliomielite", answers["any"])  # i certificati restano tutti
        self.assertNotIn("Epatite A", answers["any"])
        self.assertTrue(answers["any"].endswith(CHECK))

    def test_certificati_oltre_il_limite_niente_domanda(self):
        rec = "Raccomandate per la destinazione: Epatite A."
        answers = generate_sft.vaccination_answers(summary([LONG_CERT, LONG_CERT.replace("Nigeria", "Angola")], rec), "it")
        self.assertNotIn("any", answers)
        self.assertLessEqual(len(answers["yf"]), it.MAX_ANSWER)
        self.assertEqual(answers["rec"], f"{rec} {CHECK}")

    def test_corta_invariata(self):
        rec = "Raccomandate per la destinazione: Epatite A."
        answers = generate_sft.vaccination_answers(summary(["Febbre gialla: richiesto"], rec), "it")
        self.assertEqual(answers["any"], f"Febbre gialla: richiesto. {rec} {CHECK}")


if __name__ == "__main__":
    unittest.main()
