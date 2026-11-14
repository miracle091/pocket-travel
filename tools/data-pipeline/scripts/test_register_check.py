#!/usr/bin/env python3
"""Test di register_check.py con un lessico Morph-it minimo scritto dal test (quello vero non e' nel repository).

Uso: python test_register_check.py
"""
import tempfile
import unittest
from pathlib import Path

import register_check as rc

LEXICON = """evita\tevitare\tVER:impr+pres+2+s
evita\tevitare\tVER:ind+pres+3+s
devi\tdovere\tMOD:ind+pres+2+s
informa\tinformare\tVER:impr+pres+2+s
informa\tinformare\tVER:ind+pres+3+s
zone\tzona\tNOUN-F:p
vedi\tvedere\tVER:ind+pres+2+s
vedi\tvedere\tVER:impr+pres+2+s
dota\tdotare\tVER:impr+pres+2+s
dotati\tdotare\tVER:part+past+p+m
ritarda\tritardare\tVER:impr+pres+2+s
ritardati\tritardare\tVER:part+past+p+m
fa\tfare\tVER:impr+pres+2+s
fatti\tfare\tVER:part+past+p+m
informati\tinformare\tVER:part+past+p+m
accedere\taccedere\tVER:inf+pres
impedire\timpedire\tVER:inf+pres
"""


class RegisterCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "m.utf8"
            path.write_text(LEXICON, encoding="utf-8")
            rc.load(path)

    def test_imperativo_a_inizio_frase_e_forma_solo_di_seconda_persona(self):
        self.assertEqual([("Evita", "verbo ambiguo a inizio frase")], rc.hits("Evita le zone isolate."))
        self.assertEqual([("devi", "verbo 2a")], rc.hits("Prima di partire devi vaccinarti."))

    def test_terza_persona_e_testo_tra_virgolette_non_contano(self):
        self.assertEqual([], rc.hits("La polizia evita le zone isolate."))
        self.assertEqual([], rc.hits('Il cartello dice "evita le zone".'))

    def test_imperativo_con_pronome_attaccato(self):
        self.assertEqual([("Informati", "imperativo con pronome")], rc.hits("Informati prima di partire."))
        self.assertEqual([("impedirvi", "infinito con pronome")], rc.hits("Nessuno può impedirvi di entrare."))

    def test_participio_plurale_e_vi_di_luogo_non_contano(self):
        # falsi allarmi visti il 2026-11-14 nei consigli canadesi tradotti: participi in -ti dopo una virgola o fuori
        # dal lessico (composti di "fatti"), "vi" che indica il luogo dopo un verbo di movimento
        self.assertEqual([], rc.hits("Usare solo taxi ufficiali, dotati di tassametro."))
        self.assertEqual([], rc.hits("I voli potrebbero essere cancellati, ritardati o deviati."))
        self.assertEqual([], rc.hits("Diffidare dei medicinali contraffatti."))
        self.assertEqual([], rc.hits("Evitare le aree colpite, a meno che non sia sicuro accedervi."))

    def test_vedi_tra_parentesi_e_un_rimando(self):
        # "(vedi Livelli di rischio)" e' la forma scelta per i rimandi (utente, 2026-11-14); fuori dalle parentesi conta
        self.assertEqual([], rc.hits("Evitare lo stato di Sinaloa (vedi Livelli di rischio)."))
        self.assertEqual([("vedi", "verbo 2a")], rc.hits("Se vedi un incendio, chiamare i soccorsi."))


if __name__ == "__main__":
    unittest.main()
