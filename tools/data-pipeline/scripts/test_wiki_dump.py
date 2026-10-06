#!/usr/bin/env python3
"""Test di wiki_dump.py su piccoli dump XML sintetici nel formato dei MediaWiki Content File Exports (parti
<wiki>-AAAA-MM-GG-p<da>p<a>.xml.bz2) creati in una cartella temporanea: nessuna rete.

Uso: python test_wiki_dump.py
"""
import bz2
import tempfile
import unittest
from pathlib import Path

import wiki_dump

NS = "http://www.mediawiki.org/xml/export-0.11/"


def page(title, text="", ns=0, redirect=None, namespace=True):
    tag = f' xmlns="{NS}"' if namespace else ""
    red = f'<redirect title="{redirect}" />' if redirect else ""
    return (f"<page{tag}><title>{title}</title><ns>{ns}</ns>{red}"
            f"<revision><text>{text}</text></revision></page>")


def dump(*pages):
    return f"<mediawiki>{''.join(pages)}</mediawiki>".encode("utf-8")


class NormTitleTest(unittest.TestCase):
    def test_underscore_spazi_e_maiuscola_iniziale(self):
        self.assertEqual("Emilia Romagna", wiki_dump.norm_title("emilia_Romagna"))

    def test_spazi_ai_bordi_e_stringa_vuota(self):
        self.assertEqual("Roma", wiki_dump.norm_title("  roma "))
        self.assertEqual("", wiki_dump.norm_title(""))


class IterPagesTest(unittest.TestCase):
    def _iter(self, xml):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "dump.xml.bz2"
            path.write_bytes(bz2.compress(xml))
            return list(wiki_dump.iter_pages(path))

    def test_solo_namespace_zero_con_redirect_normalizzato(self):
        xml = dump(page("Roma", "testo &amp; altro", namespace=True),
                   page("Discussione:Roma", "x", ns=1),
                   page("Roma Capitale", redirect="roma_(citta)"))
        self.assertEqual([("Roma", "testo & altro", None), ("Roma Capitale", "", "Roma (citta)")], self._iter(xml))

    def test_redirect_a_una_sezione_porta_alla_pagina(self):
        self.assertEqual([("Cuisine of Guyana", "", "Culture of Guyana")],
                         self._iter(dump(page("Cuisine of Guyana", redirect="Culture_of_Guyana#Cuisine"))))

    def test_senza_namespace_xml(self):
        self.assertEqual([("Rimini", "t", None)], self._iter(dump(page("Rimini", "t", namespace=False))))


class ContentFileExportTest(unittest.TestCase):
    def _parts(self, directory, wiki="itwiki"):
        parts = {f"{wiki}-2026-09-01-p100p200.xml.bz2": dump(page("Torino", "t"), page("Alias", redirect="Destinazione")),
                 f"{wiki}-2026-09-01-p2p99.xml.bz2": dump(page("Roma", "r"), page("Vuota", "")),
                 f"{wiki}-2026-09-01-p201p300.xml.bz2": dump(page("Destinazione", "ok"))}
        for name, xml in parts.items():
            (Path(directory) / name).write_bytes(bz2.compress(xml))

    def test_parti_in_ordine_di_pagina(self):
        with tempfile.TemporaryDirectory() as d:
            self._parts(d)
            self._parts(d, "itwikivoyage")  # un'altra wiki nella stessa cartella non si mescola
            parts = wiki_dump.dump_files(d, "itwiki", "2026-09-01")
            self.assertEqual(["itwiki-2026-09-01-p2p99.xml.bz2", "itwiki-2026-09-01-p100p200.xml.bz2",
                              "itwiki-2026-09-01-p201p300.xml.bz2"], [p.name for p in parts])
            self.assertEqual(["Roma", "Vuota", "Torino", "Alias", "Destinazione"], [t for t, _, _ in wiki_dump.iter_pages(parts)])

    def test_cartella_di_una_sola_wiki_senza_data(self):
        with tempfile.TemporaryDirectory() as d:
            self._parts(d, "enwikivoyage")
            self.assertEqual(3, len(wiki_dump.dump_files(d)))

    def test_senza_parti_errore(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "itwikivoyage-20260901-pages-articles.xml.bz2").write_bytes(bz2.compress(dump()))
            with self.assertRaises(FileNotFoundError):
                wiki_dump.dump_files(d, "itwikivoyage", "2026-09-01")

    def test_pagine_richieste_dalle_parti_con_redirect_e_cache(self):
        with tempfile.TemporaryDirectory() as d:
            self._parts(d)
            self.assertEqual({"Torino": ("Torino", "t"), "Alias": ("Destinazione", "ok")},
                             wiki_dump.load_titles(d, "itwiki", "2026-09-01", ["torino", "Alias", "Vuota", "Assente"]))
            self.assertTrue((Path(d) / "itwiki-2026-09-01.pages.json").exists())
            for part in Path(d).glob("*.xml.bz2"):  # dalla cache, senza rileggere le parti
                part.unlink()
            self.assertEqual({"Alias": ("Destinazione", "ok")}, wiki_dump.load_titles(d, "itwiki", "09-2026", ["Alias"]))

    def test_cache_rovinata_si_rifa(self):
        with tempfile.TemporaryDirectory() as d:
            self._parts(d)
            (Path(d) / "itwiki-2026-09-01.pages.json").write_text('{"titles": ["Torino"', encoding="utf-8")
            self.assertEqual({"Torino": ("Torino", "t")}, wiki_dump.load_titles(d, "itwiki", "2026-09-01", ["Torino"]))

    def test_cache_senza_titolo_finale_si_rifa(self):
        with tempfile.TemporaryDirectory() as d:
            self._parts(d)
            (Path(d) / "itwiki-2026-09-01.pages.json").write_text('{"titles": ["Alias"], "pages": {"Alias": "ok"}}',
                                                                  encoding="utf-8")
            self.assertEqual({"Alias": ("Destinazione", "ok")}, wiki_dump.load_titles(d, "itwiki", "2026-09-01", ["Alias"]))

    def test_export_incompleto_errore(self):
        with tempfile.TemporaryDirectory() as d:
            self._parts(d)
            names = [p.name for p in wiki_dump.dump_files(d, "itwiki", "2026-09-01")]
            sums = Path(d) / "itwiki.SHA256SUMS"
            sums.write_text("".join(f"{'0' * 64}  {n}\n" for n in names), encoding="utf-8")
            self.assertEqual(3, len(wiki_dump.dump_files(d, "itwiki", "2026-09-01")))
            (Path(d) / names[1]).unlink()  # download a meta'
            with self.assertRaisesRegex(FileNotFoundError, names[1]):
                wiki_dump.load_titles(d, "itwiki", "2026-09-01", ["Torino"])
            self.assertFalse((Path(d) / "itwiki-2026-09-01.pages.json").exists())

    def test_data_come_mese_e_anno(self):
        with tempfile.TemporaryDirectory() as d:
            self._parts(d)
            self.assertEqual(3, len(wiki_dump.dump_files(d, "itwiki", "2026/09")))


class NormalizeDateTest(unittest.TestCase):
    def test_mese_e_anno_col_giorno_al_primo(self):
        self.assertEqual("2026-10-01", wiki_dump.normalize_date("10-2026"))
        self.assertEqual("2026-10-01", wiki_dump.normalize_date("2026/10"))
        self.assertEqual("2026-10-01", wiki_dump.normalize_date("2026.10"))
        self.assertEqual("2026-09-01", wiki_dump.normalize_date("9-2026"))

    def test_data_completa_per_sperimentare(self):
        self.assertEqual("2026-10-01", wiki_dump.normalize_date("2026-10-01"))
        self.assertEqual("2026-10-15", wiki_dump.normalize_date("2026-10-15"))

    def test_formati_e_date_non_validi(self):
        for text in ("13-2026", "01-10-2026", "2026-02-30", "2026-00", "ottobre 2026", ""):
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, "MM-AAAA"):
                wiki_dump.normalize_date(text)


if __name__ == "__main__":
    unittest.main()
