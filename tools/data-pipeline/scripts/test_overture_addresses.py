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


class MergePairCountsTest(unittest.TestCase):
    def test_somma_le_stesse_coppie_di_file_diversi(self):
        totals = {}
        overture_addresses.merge_pair_counts(totals, [("AT", "at/countrywide", "CC-BY-4.0", 10), ("HK", "hk/x", "L", 1)])
        overture_addresses.merge_pair_counts(totals, [("AT", "at/countrywide", "CC-BY-4.0", 5)])
        self.assertEqual(totals, {("AT", "at/countrywide", "CC-BY-4.0"): 15, ("HK", "hk/x", "L"): 1})


class ExcludedAreasTest(unittest.TestCase):
    def write(self, text):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "lista.tsv"
        path.write_text(text, encoding="utf-8")
        return path

    def test_area_solo_sulle_fonti_ammesse(self):
        path = self.write(
            "US\tus/mn/statewide\tLicenseRef-Proprietary\tallow\tMnGeo\tPOLYGON((0 0, 1 0, 1 1, 0 0))\n"
            "US\tus/xx/altro\tCC0-1.0\tallow\tXX\n"
            "US\tus/yy/escluso\tCC0-1.0\texclude\tn/a\tPOLYGON((0 0, 1 0, 1 1, 0 0))\n"
        )
        areas = overture_addresses.load_excluded_areas(path)
        self.assertEqual(areas, [("us/mn/statewide", "LicenseRef-Proprietary", "POLYGON((0 0, 1 0, 1 1, 0 0))")])
        clause = overture_addresses.excluded_areas_clause(areas)
        self.assertIn("AND NOT (sources[1].dataset = 'us/mn/statewide'", clause)
        self.assertIn("ST_GeomFromText('POLYGON((0 0, 1 0, 1 1, 0 0))')", clause)
        self.assertEqual(overture_addresses.excluded_areas_clause([]), "")

    def test_area_non_wkt_rifiutata(self):
        path = self.write("US\tus/mn/statewide\tLicenseRef-Proprietary\tallow\tMnGeo\t-92,46,-90,48\n")
        with self.assertRaises(ValueError):
            overture_addresses.load_excluded_areas(path)


class FormatRowTest(unittest.TestCase):
    def test_riga_con_via_e_citta(self):
        self.assertEqual(
            overture_addresses.format_row(43.9424, 12.4578, "10", "sm/countrywide", "Via Roma", "Serravalle"),
            "43.9424\t12.4578\t10\tsm/countrywide\tVia Roma\tSerravalle\n",
        )

    def test_via_e_citta_mancanti_restano_colonne_vuote(self):
        self.assertEqual(
            overture_addresses.format_row(1.5, 2.5, "7", "x/y", None, None),
            "1.5\t2.5\t7\tx/y\t\t\n",
        )

    def test_tab_e_a_capo_nei_testi_diventano_spazi(self):
        row = overture_addresses.format_row(1.5, 2.5, "7", "x/y", "Via\tdei\nMille ", " Roma")
        self.assertEqual(row, "1.5\t2.5\t7\tx/y\tVia dei Mille\tRoma\n")
        self.assertEqual(len(row.rstrip("\n").split("\t")), 6)

    def test_senza_punto_o_numero_nessuna_riga(self):
        self.assertIsNone(overture_addresses.format_row(None, 2.5, "7", "x/y", "Via Roma", None))
        self.assertIsNone(overture_addresses.format_row(1.5, 2.5, None, "x/y", "Via Roma", None))


if __name__ == "__main__":
    unittest.main()
