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


if __name__ == "__main__":
    unittest.main()
