#!/usr/bin/env python3
"""Test del controllo della lista bianca di overture_addresses.py (--check-whitelist) sulla logica
pura: confronto tra le coppie del rilascio e la lista, testo del report. Nessuna rete, nessun DuckDB
(sostituito da un modulo vuoto: la query su tutto il tema la copre solo una prova manuale).

Uso: python test_overture_addresses.py
"""
import sys
import tempfile
import types
import unittest
from pathlib import Path

sys.modules.setdefault("duckdb", types.ModuleType("duckdb"))
sys.path.insert(0, str(Path(__file__).parent))
import overture_addresses  # noqa: E402

compare_with_whitelist = overture_addresses.compare_with_whitelist
check_report = overture_addresses.check_report
load_reviewed = overture_addresses.load_reviewed


class LoadReviewedTest(unittest.TestCase):
    def test_tiene_ammesse_ed_escluse_e_salta_commenti(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "lista.tsv"
            path.write_text(
                "# commento\n"
                "# paese\tdataset\tlicenza\tdecisione\tattribuzione\n"
                "AT\tat/countrywide\tCC-BY-4.0\tallow\tBEV\n"
                "HK\thk/countrywide-en\tLicenseRef-Proprietary\texclude\tn/a\n"
                "\n",
                encoding="utf-8",
            )
            self.assertEqual(
                load_reviewed(path),
                {("AT", "at/countrywide", "CC-BY-4.0"), ("HK", "hk/countrywide-en", "LicenseRef-Proprietary")},
            )


class CompareTest(unittest.TestCase):
    reviewed = {("AT", "at/countrywide", "CC-BY-4.0"), ("HK", "hk/countrywide-en", "LicenseRef-Proprietary")}

    def test_niente_da_rivedere(self):
        pairs = [("AT", "at/countrywide", "CC-BY-4.0", 10), ("HK", "hk/countrywide-en", "LicenseRef-Proprietary", 5)]
        self.assertEqual(compare_with_whitelist(pairs, self.reviewed), ([], []))
        self.assertEqual(check_report("2026-09-23.1", [], []), "")

    def test_dataset_nuovo_ordinato_per_righe(self):
        pairs = [
            ("AT", "at/countrywide", "CC-BY-4.0", 10),
            ("HK", "hk/countrywide-en", "LicenseRef-Proprietary", 5),
            ("XX", "xx/piccolo", "CC0-1.0", 3),
            ("YY", "yy/grande", "CC0-1.0", 300),
        ]
        new, missing = compare_with_whitelist(pairs, self.reviewed)
        self.assertEqual(new, [("YY", "yy/grande", "CC0-1.0", 300), ("XX", "xx/piccolo", "CC0-1.0", 3)])
        self.assertEqual(missing, [])

    def test_licenza_cambiata_e_nuova_e_sparita(self):
        pairs = [
            ("AT", "at/countrywide", "CC-BY-SA-4.0", 10),
            ("HK", "hk/countrywide-en", "LicenseRef-Proprietary", 5),
        ]
        new, missing = compare_with_whitelist(pairs, self.reviewed)
        self.assertEqual(new, [("AT", "at/countrywide", "CC-BY-SA-4.0", 10)])
        self.assertEqual(missing, [("AT", "at/countrywide", "CC-BY-4.0")])
        report = check_report("2026-10-21.0", new, missing)
        self.assertIn("`2026-10-21.0`", report)
        self.assertIn("| AT | at/countrywide | CC-BY-SA-4.0 | 10 |", report)
        self.assertIn("| AT | at/countrywide | CC-BY-4.0 |", report)


if __name__ == "__main__":
    unittest.main()
