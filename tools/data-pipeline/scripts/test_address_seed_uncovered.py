#!/usr/bin/env python3
"""Test di address_seed_uncovered.py: righe del seme non coperte dalle celle pubblicate e warning per quelle
del seme rigenerato. Nessuna rete.

Uso: python test_address_seed_uncovered.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import address_seed_uncovered as module  # noqa: E402


class UncoveredTest(unittest.TestCase):
    def test_pubblicata_antenata_e_discendente_sono_coperte(self):
        published = ["7/10/20", "8/40/40"]
        seed = [
            "7/10/20",  # pubblicata
            "7/20/20",  # antenata di 8/40/40 (cella divisa)
            "6/5/10",   # antenata di 7/10/20
            "9/41/81",  # discendente di 7/10/20
            "9/20/40",  # senza legami con le pubblicate
            "7/11/20",  # senza legami con le pubblicate
        ]
        self.assertEqual(["9/20/40", "7/11/20"], module.uncovered(seed, published))

    def test_senza_celle_pubblicate_restano_tutte(self):
        self.assertEqual(["7/1/1", "8/2/2"], module.uncovered(["7/1/1", "8/2/2"], []))

    def test_antenata_a_zoom_zero(self):
        self.assertEqual([], module.uncovered(["0/0/0"], ["7/1/1"]))


class WarningsTest(unittest.TestCase):
    def test_solo_zoom_minimo_e_prima_le_assenti_dal_repo(self):
        seed = ["6/1/1", "7/5/5", "7/6/6", "8/9/9"]
        lines = module.warnings(seed, ["7/5/5"], ["7/6/6"], 7)
        self.assertEqual(
            [
                "::warning::cella 8/9/9 del seme rigenerato senza cella pubblicata (assente dal seme del repository)",
                "::warning::cella 7/6/6 del seme rigenerato senza cella pubblicata",
            ],
            lines,
        )

    def test_nessun_warning_se_tutto_coperto(self):
        self.assertEqual([], module.warnings(["7/5/5"], ["7/5/5"], [], 7))


if __name__ == "__main__":
    unittest.main()
