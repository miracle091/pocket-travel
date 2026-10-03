#!/usr/bin/env python3
"""Test di extract-cities-dump.py e extract-cities-dump-en.py: riconoscimento delle citta' e dei luoghi che le
contengono, e main() end-to-end su un piccolo dump bz2 sintetico (la popolazione da Wikidata e' sostituita con un mock:
nessuna rete).

Uso: python test_extract_cities_dump.py
"""
import bz2
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

HERE = Path(__file__).parent
# I due script importano city_population e wiki_dump dalla propria cartella.
sys.path.insert(0, str(HERE))


def _load(name, filename):
    # Il nome del file (con i trattini) non e' un identificatore Python valido: si carica dal path.
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


extract_it = _load("extract_cities_dump", "extract-cities-dump.py")
extract_en = _load("extract_cities_dump_en", "extract-cities-dump-en.py")


def page(title, text, redirect=None):
    red = f'<redirect title="{redirect}" />' if redirect else ""
    return f"<page><title>{title}</title><ns>0</ns>{red}<revision><text>{text}</text></revision></page>"


def write_dump(directory, pages):
    path = Path(directory) / "dump.xml.bz2"
    path.write_bytes(bz2.compress(f"<mediawiki>{''.join(pages)}</mediawiki>".encode("utf-8")))
    return path


def run_main(module, argv):
    with mock.patch.object(sys, "argv", ["x"] + argv), \
            mock.patch.object(module.city_population, "annotate") as annotate, \
            mock.patch.object(module.city_wikipedia, "annotate") as annotate_wikipedia, \
            redirect_stdout(io.StringIO()) as out:
        module.main()
    # Storia e Clima per gli stessi file e nella stessa lingua della popolazione
    assert annotate_wikipedia.call_args[0] == annotate.call_args[0][:2], annotate_wikipedia.call_args
    return annotate, out.getvalue()


class CityParentsTest(unittest.TestCase):
    def test_stato_e_regione_normalizzati(self):
        text = "{{QuickbarCity\n| Stato = [[italia|Italia]]\n| Regione = [[Emilia_Romagna]]\n}}"
        self.assertEqual({"Italia", "Emilia Romagna"}, extract_it.city_parents(text))

    def test_pagina_senza_quickbar_non_e_una_citta(self):
        self.assertIsNone(extract_it.city_parents("Testo libero\n| Stato = [[Italia]]"))

    def test_quickbar_senza_luoghi_da_insieme_vuoto(self):
        self.assertEqual(set(), extract_it.city_parents("{{QuickbarCity\n| Abitanti = 10\n}}"))

    def test_quickbar_case_insensitive_con_spazi(self):
        self.assertEqual({"Texas"}, extract_it.city_parents("  {{ quickbarcity\n| Stato federato = [[Texas#Citta]]\n}}"))


class RegionsOfTest(unittest.TestCase):
    ids = {"Emilia-Romagna": "emilia", "Italy": "italia"}

    def test_risale_la_catena_fino_a_tutte_le_regioni(self):
        parent = {"Rimini": "Rimini (province)", "Rimini (province)": "Emilia-Romagna", "Emilia-Romagna": "Italy"}
        self.assertEqual({"emilia", "italia"}, extract_en.regions_of("Rimini", parent, self.ids))

    def test_catena_senza_regioni_note(self):
        self.assertEqual(set(), extract_en.regions_of("A", {"A": "B", "B": "C"}, self.ids))

    def test_ciclo_termina(self):
        parent = {"A": "B", "B": "A"}
        self.assertEqual(set(), extract_en.regions_of("A", parent, self.ids))

    def test_catena_oltre_la_profondita_massima_si_ferma(self):
        parent = {f"n{i}": f"n{i + 1}" for i in range(30)}
        parent["n20"] = "Italy"
        # n0 raggiunge Italy dopo 21 passi, oltre MAX_DEPTH (12)
        self.assertEqual(set(), extract_en.regions_of("n0", parent, self.ids))
        self.assertEqual({"italia"}, extract_en.regions_of("n10", parent, self.ids))


class PatternsEnTest(unittest.TestCase):
    def test_is_part_of(self):
        m = extract_en.IS_PART_OF.search("{{IsPartOf|Emilia-Romagna}}")
        self.assertEqual("Emilia-Romagna", m.group(1))

    def test_stati_di_citta(self):
        for tpl in ("{{usablecity}}", "{{GuideCity}}", "{{ starcity }}", "{{outlinecity}}"):
            self.assertIsNotNone(extract_en.CITY_STATUS.search(tpl), tpl)
        self.assertIsNone(extract_en.CITY_STATUS.search("{{usabledistrict}}"))


class MainItTest(unittest.TestCase):
    def test_scrive_un_jsonl_per_regione_e_chiama_annotate(self):
        with tempfile.TemporaryDirectory() as d:
            dump = write_dump(d, [
                page("Rimini", "{{QuickbarCity\n| Regione = [[Emilia-Romagna]]\n}} testo"),
                page("Roma", "{{QuickbarCity\n| Regione = [[Lazio]]\n}}"),
                page("Alias", "{{QuickbarCity\n| Regione = [[Lazio]]\n}}", redirect="Roma"),
                page("Lazio", "pagina di regione"),
                page("Altrove", "{{QuickbarCity\n| Regione = [[Sconosciuta]]\n}}"),
            ])
            tsv = Path(d) / "regioni.tsv"
            tsv.write_text("emilia\tEmilia-Romagna\nlazio\tLazio\nvuota\t\n", encoding="utf-8")
            out_dir = Path(d) / "out"

            annotate, output = run_main(extract_it, [str(dump), str(tsv), str(out_dir)])

            emilia = [json.loads(l) for l in (out_dir / "emilia.cities.jsonl").read_text(encoding="utf-8").splitlines()]
            lazio = [json.loads(l) for l in (out_dir / "lazio.cities.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertEqual(["Rimini"], [r["city"] for r in emilia])
            self.assertEqual(["Roma"], [r["city"] for r in lazio])
            self.assertFalse((out_dir / "vuota.cities.jsonl").exists())
            self.assertIn("2 pagine di 2 regioni", output)
            paths, lang, titles = annotate.call_args[0]
            self.assertEqual("it", lang)
            self.assertEqual({"Emilia-Romagna", "Lazio"}, set(titles.values()))
            self.assertEqual({out_dir / "emilia.cities.jsonl", out_dir / "lazio.cities.jsonl"}, set(paths))

    def test_argomenti_sbagliati_escono_con_1(self):
        with mock.patch.object(sys, "argv", ["x"]), redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as cm:
                extract_it.main()
        self.assertEqual(1, cm.exception.code)


class MainEnTest(unittest.TestCase):
    def test_citta_risalite_via_is_part_of_e_redirect(self):
        with tempfile.TemporaryDirectory() as d:
            dump = write_dump(d, [
                page("Rimini", "{{isPartOf|Rimini (province)}} {{usablecity}}"),
                page("Rimini (province)", "{{isPartOf|Emilia-Romagna}}"),
                page("Emilia-Romagna", "{{isPartOf|Italy}}"),
                page("Lido", "{{isPartOf|Emilia Alias}} {{guidecity}}"),
                page("Emilia Alias", "", redirect="Emilia-Romagna"),
                page("Paris", "{{isPartOf|France}} {{usablecity}}"),
                page("Quartiere", "{{isPartOf|Emilia-Romagna}} {{usabledistrict}}"),
            ])
            tsv = Path(d) / "regioni.tsv"
            tsv.write_text("emilia\tEmilia-Romagna\n", encoding="utf-8")
            out_dir = Path(d) / "out"

            annotate, output = run_main(extract_en, [str(dump), str(tsv), str(out_dir)])

            rows = [json.loads(l) for l in (out_dir / "emilia.cities-en.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual({"Rimini", "Lido"}, {r["city"] for r in rows})
        self.assertIn("2 pagine di 1 regioni", output)
        self.assertEqual("en", annotate.call_args[0][1])


if __name__ == "__main__":
    unittest.main()
