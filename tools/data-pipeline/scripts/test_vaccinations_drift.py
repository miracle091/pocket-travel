#!/usr/bin/env python3
"""Test di vaccinations_drift.py: frasi estratte da una scheda Travel.gc.ca, confronto con l'istantanea, numeri
delle riunioni del comitato polio OMS, statement di polio-status.tsv e main() con fonti finte. Nessuna rete.

Uso: python test_vaccinations_drift.py
"""
import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import vaccinations_drift as drift


def details(title, body):
    return f"<details><summary>{title}</summary><p>{body}</p></details>"


HEALTH = (details("Yellow Fever - Country Entry Requirements",
                  "Risk<br>There is a risk of yellow fever.<br>Country Entry Requirement*<br>Proof is required."
                  "<br>Recommendation<br>Vaccination is recommended.")
          + details("Polio", "Category A country.<br>Polio is spread from person to person.")
          + details("Typhoid fever", "x") + details("Hepatitis A", "y") + details("Measles", "z"))


class FingerprintsTest(unittest.TestCase):
    def test_frasi_della_scheda(self):
        self.assertEqual({("yf", "ke"): "There is a risk of yellow fever. | Proof is required.",
                          ("polio", "ke"): "Category A country.",
                          ("vaccini", "ke"): "HEPA TYPHOID"}, drift.fingerprints_of("ke", HEALTH))

    def test_scheda_senza_blocchi(self):
        self.assertEqual({("yf", "it"): "", ("polio", "it"): "", ("vaccini", "it"): ""},
                         drift.fingerprints_of("it", "<p>niente</p>"))


class BaselineTest(unittest.TestCase):
    def test_scrittura_e_lettura(self):
        values = {("yf", "ke"): "a | b", ("vaccini", "ke"): "", ("hajj-pdf", "sa"): "abc"}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "baseline.tsv"
            drift.write_baseline(path, values)
            self.assertEqual(values, drift.read_baseline(path))

    def test_confronto(self):
        old = {("yf", "ke"): "a", ("polio", "ke"): "p", ("vaccini", "xx"): "HEPA"}
        new = {("yf", "ke"): "b", ("polio", "ke"): "p", ("vaccini", "yy"): "RABIES"}
        entries = drift.compare(old, new)
        self.assertEqual(3, len(entries))
        self.assertIn("yf `ke`", entries[2])
        self.assertIn("prima: a", entries[2])
        self.assertIn("ora: b", entries[2])
        self.assertIn("non piu' nella fonte", entries[0])
        self.assertIn("nuovo nella fonte: RABIES", entries[1])

    def test_nessuna_differenza(self):
        self.assertEqual([], drift.compare({("yf", "ke"): "a"}, {("yf", "ke"): "a"}))


class MeetingsTest(unittest.TestCase):
    def test_ordinali_in_lettere(self):
        self.assertEqual(45, drift.ordinal("Forty-fifth"))
        self.assertEqual(50, drift.ordinal("fiftieth"))
        self.assertEqual(12, drift.ordinal("twelfth"))
        self.assertIsNone(drift.ordinal("latest"))

    def test_riunioni_nella_pagina(self):
        page = ("Statement of the Forty-fifth Meeting of the Polio IHR Emergency Committee ... "
                "Forty-fourth meeting of the Polio IHR Emergency Committee ... 46th meeting of the Polio IHR "
                "Emergency Committee ... the latest meeting of the Polio IHR Emergency Committee")
        self.assertEqual({44, 45, 46}, drift.meeting_numbers(page))

    def test_statement_di_polio_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "polio-status.tsv"
            path.write_text("# commento IHR EC 1\naf\tX\tIHR EC 45, 2026-08-21\tF3\t2026-10-03\n", encoding="utf-8")
            self.assertEqual(45, drift.current_statement(path))
            path.write_text("af\tX\tIHR EC 45, 2026-08-21\nao\tX\tIHR EC 46, 2026-11-21\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                drift.current_statement(path)


class MainTest(unittest.TestCase):
    def run_main(self, tmp, who_page, pdf=b"pdf", update=False):
        """main() con la cache di Travel.gc.ca gia' piena e download() finto per OMS e PDF saudita."""
        tmp = Path(tmp)
        cache = tmp / "cache"
        cache.mkdir(exist_ok=True)
        (cache / "index-alpha-eng.json").write_text(json.dumps({"data": {"KE": {}, "CA-ON": {}}}), encoding="utf-8")
        (cache / "cta-cap-ke.json").write_text(json.dumps({"data": {"eng": {"health": HEALTH}}}), encoding="utf-8")
        polio = tmp / "polio-status.tsv"
        polio.write_text("ke\tPREVIOUSLY_INFECTED\tIHR EC 45, 2026-08-21\tF3\t2026-10-03\n", encoding="utf-8")
        sources = {drift.WHO_COMMITTEE: who_page, drift.HAJJ_PDF: pdf}
        args = ["--cache", str(cache), "--report", str(tmp / "report.md")] + (["--update"] if update else [])
        with mock.patch.object(drift, "BASELINE", tmp / "baseline.tsv"), \
                mock.patch.object(drift, "POLIO_STATUS", polio), \
                mock.patch.object(drift, "download", side_effect=sources.get), \
                redirect_stdout(io.StringIO()):
            return drift.main(args)

    def test_aggiorna_poi_nessuna_differenza(self):
        page = b"Forty-fifth Meeting of the Polio IHR Emergency Committee"
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(0, self.run_main(tmp, page, update=True))
            baseline = drift.read_baseline(Path(tmp) / "baseline.tsv")
            self.assertEqual(hashlib.sha256(b"pdf").hexdigest(), baseline[("hajj-pdf", "sa")])
            self.assertNotIn(("yf", "ca-on"), baseline)
            self.assertEqual(0, self.run_main(tmp, page))
            self.assertFalse((Path(tmp) / "report.md").exists())

    def test_pdf_cambiato_e_statement_nuovo(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.run_main(tmp, b"", update=True)
            page = b"Forty-sixth Meeting of the Polio IHR Emergency Committee"
            self.assertEqual(2, self.run_main(tmp, page, pdf=b"pdf nuovo"))
            report = (Path(tmp) / "report.md").read_text(encoding="utf-8")
            self.assertIn("riunione 46", report)
            self.assertIn("hajj-pdf `sa`", report)

    def test_pdf_sparito_e_oms_irraggiungibile(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.run_main(tmp, b"", update=True)
            self.assertEqual(2, self.run_main(tmp, "HTTP 403", pdf="HTTP 404"))
            report = (Path(tmp) / "report.md").read_text(encoding="utf-8")
            self.assertIn("ora: HTTP 404", report)
            self.assertIn("non risponde, HTTP 403", report)


if __name__ == "__main__":
    unittest.main()
