#!/usr/bin/env python3
"""Test degli aiuti del dataset inglese (generate_sft_dataset_en.py) non coperti altrove: articolo davanti ai nomi di
paese, scelta della risposta, titolo della pagina EN di una regione, lettura di un dump di Wikivoyage EN con gli
isPartOf e i redirect. Dump sintetico in una cartella temporanea: nessuna rete.

Uso: python test_sft_en_helpers.py
"""
import bz2
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import generate_sft_dataset_en as en

NS = "http://www.mediawiki.org/xml/export-0.11/"


def page(title, text="", redirect=None):
    red = f'<redirect title="{redirect}" />' if redirect else ""
    return f'<page xmlns="{NS}"><title>{title}</title><ns>0</ns>{red}<revision><text>{text}</text></revision></page>'


def write_dump(directory, *pages):
    path = Path(directory) / "enwikivoyage-2026-09-01-p1p9.xml.bz2"
    path.write_bytes(bz2.compress(f"<mediawiki>{''.join(pages)}</mediawiki>".encode("utf-8")))
    return path


class WithArticleTest(unittest.TestCase):
    def test_nomi_con_articolo(self):
        for name in ("Philippines", "Netherlands", "Gambia", "Vatican City", "Cayman Islands", "United Arab Emirates",
                     "United States", "Czech Republic", "United Kingdom", "Democratic Republic of the Congo"):
            self.assertEqual(f"the {name}", en.with_article(name), name)

    def test_nomi_senza_articolo(self):
        for name in ("Italy", "Canada", "Georgia", "San Marino", "Republic of Korea", "Statesville"):
            self.assertEqual(name, en.with_article(name), name)


class AnswerForTest(unittest.TestCase):
    BODY = ("Tap water is safe to drink in most cities. The country has many beautiful beaches. "
            "Bring a hat and sunscreen for the sun.")

    def test_frase_con_parola_chiave_della_categoria(self):
        answer = en.answer_for(self.BODY, self.BODY, "Is the tap water safe?", "SALUTE", "Italy")
        self.assertIn("Tap water", answer)

    def test_nessuna_frase_pertinente_risposta_vuota(self):
        body = "The country has many beautiful beaches. Bring a hat and sunscreen for the sun."
        self.assertEqual("", en.answer_for(body, body, "What vaccinations do I need?", "SALUTE", "Italy"))

    def test_il_nome_della_regione_non_conta_come_radice_della_domanda(self):
        body = "Zanzibar is a lovely island. Many tourists come every year."
        self.assertEqual("", en.answer_for(body, body, "Where do I sleep in Zanzibar?", "ALLOGGIO", "Zanzibar"))

    def test_rifiuto_con_il_tema(self):
        self.assertEqual(f"{en.REFUSAL} on health: check an official source.", en.refusal("on health", "check an official source."))


class PageTitleTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.out = Path(tmp.name)
        (self.out / "raw").mkdir()
        patcher = mock.patch.object(en, "OUT", self.out)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_dal_file_delle_fonti_normalizzato(self):
        self.assertEqual("New York", en.page_title("new-york", {("new-york", "en"): "new_York"}))

    def test_fonte_assente_ripiega_sull_url_in_cache(self):
        (self.out / "raw" / "canada.en.url").write_text("https://en.wikivoyage.org/wiki/Cura%C3%A7ao_Island\n", encoding="utf-8")
        self.assertEqual("Curaçao Island", en.page_title("canada", {("canada", "en"): "-"}))
        self.assertEqual("Curaçao Island", en.page_title("canada", {}))

    def test_senza_fonte_ne_cache_nessun_titolo(self):
        self.assertIsNone(en.page_title("palau", {}))
        (self.out / "raw" / "palau.en.url").write_text("\n", encoding="utf-8")
        self.assertIsNone(en.page_title("palau", {("palau", "en"): "-"}))

    def test_display_name_toglie_il_disambiguatore(self):
        self.assertEqual("Georgia", en.display_name("Georgia (U.S. state)"))
        self.assertEqual("Georgia", en.display_name("Georgia"))
        self.assertEqual("Washington, D.C.", en.display_name("Washington, D.C."))


class LoadEnDumpTest(unittest.TestCase):
    def test_pagine_genitori_citta_e_redirect(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write_dump(
                tmp,
                page("Italy", "{{pagebanner}} Italy is a country."),
                page("Tuscany", "{{IsPartOf|Italy}} Tuscany is a region."),
                page("Florence", "{{isPartOf|Tuscany}}\n{{usablecity}}\nFlorence is a city."),
                page("Pisa", "{{isPartOf|Tuscany}}\nPisa is a town without a status template."),
                page("Firenze", redirect="Florence"),
                page("Bel Paese", redirect="Italy"),
                page("Siena", "{{IsPartOf|Toscana}}\n{{guidecity}}"),
                page("Toscana", redirect="Tuscany"),
            )
            pages, parent, cities = en.load_en_dump(path)
        self.assertEqual({"Florence", "Siena"}, cities)
        self.assertEqual("Tuscany", parent["Florence"])
        self.assertEqual("Italy", parent["Tuscany"])
        self.assertEqual("Tuscany", parent["Siena"])         # il redirect del genitore si segue
        self.assertEqual(pages["Florence"], pages["Firenze"])  # il redirect porta il testo della destinazione
        self.assertEqual(pages["Italy"], pages["Bel Paese"])
        self.assertNotIn("Pisa", cities)

    def test_dump_vuoto(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(({}, {}, set()), en.load_en_dump(write_dump(tmp)))

    def test_redirect_senza_destinazione_nel_dump_resta_fuori(self):
        with tempfile.TemporaryDirectory() as tmp:
            pages, _, _ = en.load_en_dump(write_dump(tmp, page("Alias", redirect="Assente"), page("Reale", "Testo.")))
        self.assertEqual({"Reale": "Testo."}, pages)

    def test_isPartOf_oltre_i_primi_seimila_caratteri_si_legge_in_coda(self):
        text = "x" * 7000 + "\n{{isPartOf|Italy}}\n{{outlinecity}}"
        with tempfile.TemporaryDirectory() as tmp:
            _, parent, cities = en.load_en_dump(write_dump(tmp, page("Lontana", text)))
        self.assertEqual("Italy", parent["Lontana"])
        self.assertEqual({"Lontana"}, cities)


if __name__ == "__main__":
    unittest.main()
