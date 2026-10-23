#!/usr/bin/env python3
"""Test di sft_retrieval.py: domande diverse da quelle delle misure, regioni del training, risposte e righe su guide
finte, senza rete.

Uso: python test_sft_retrieval.py
"""
import contextlib
import io
import json
import random
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import eval_retrieval as er
import generate_eval_set as ges
import generate_eval_set_en as ges_en
import generate_sft as gs
import sft_retrieval as sr


def normalized(template):
    """Una domanda senza segnaposto, maiuscole, accenti e punteggiatura."""
    return er.normalized_words(re.sub(r"\{\w+\}", "x", template)).strip()


def measured_questions():
    """Le domande delle serie di eval_retrieval.py e dei test di generate_eval_set*.py, normalizzate."""
    out = set()

    def add(value):
        if isinstance(value, str):
            out.add(normalized(value))
        elif isinstance(value, dict):
            for v in value.values():
                add(v)
        elif isinstance(value, (list, tuple)):
            for v in value:
                add(v)
    for lang in er.LANGS:
        for series in ("direct", "reworded", "dev", "dev2", "control", "other-lang"):
            add(er.questions_of(lang, series))
        add([q for q, _ in er.GENERAL_Q[lang]])
    add([er.OTHER_COUNTRY_Q, ges.PARA, ges.PARA_EN, ges.PARA_CITY, ges.PARA_WIKI, ges_en.PARA, ges_en.PARA_CITY_EN])
    return out


class QuestionsTest(unittest.TestCase):
    def test_nessuna_domanda_uguale_a_quelle_delle_misure(self):
        measured = measured_questions()
        self.assertIn(normalized("Cosa fare in una giornata a {c}?"), measured)
        ours = {normalized(q) for qs in sr.QUESTIONS.values() for t in qs.values() for q in t}
        self.assertEqual(ours & measured, set())

    def test_da_6_a_10_domande_per_categoria_e_lingua(self):
        for lang, qs in sr.QUESTIONS.items():
            self.assertEqual(set(qs), set(sr.CATEGORIES))
            for cat, templates in qs.items():
                self.assertTrue(6 <= len(set(templates)) <= 10, (lang, cat))
                self.assertTrue(all("{c}" in t for t in templates), (lang, cat))

    def test_parole_richieste_solo_per_domande_del_modulo(self):
        templates = {t for qs in sr.QUESTIONS.values() for ts in qs.values() for t in ts}
        self.assertLessEqual(set(sr.ANSWER_NEEDS), templates)

    def test_risposta_con_le_parole_della_domanda(self):
        self.assertTrue(sr.answer_has_needs("{c} ha origini romane?", "Fu una colonia romana."))
        self.assertFalse(sr.answer_has_needs("{c} ha origini romane?", "Il porto crebbe nel Seicento."))
        self.assertFalse(sr.answer_has_needs("{c} ha origini romane?", "Ottone I, imperatore del Sacro Romano Impero, la difese."))
        self.assertFalse(sr.answer_has_needs("Does {c} have Roman origins?", "It joined the Holy Roman Empire. The church is Romanesque."))
        founded = "In what year was {c} founded?"
        self.assertTrue(sr.answer_has_needs(founded, "The town was founded in 1250 by the Moors."))
        self.assertFalse(sr.answer_has_needs(founded, "The town was founded by the Moors."))
        self.assertFalse(sr.answer_has_needs(founded, "It has 12.000 inhabitants and was founded later."))
        self.assertFalse(sr.answer_has_needs("Da quanti secoli esiste {c}?", "Nel 1888 fu fondato il birrificio."))
        self.assertTrue(sr.answer_has_needs("Da quanti secoli esiste {c}?", "I primi insediamenti risalgono all'epoca vichinga."))
        self.assertTrue(sr.answer_has_needs("Da chi è stata fondata {c}?", "Fu fondata dai Fenici."))
        self.assertTrue(sr.answer_has_needs("Dove cenare a {c}?", "Qualunque frase."))


class RegionsAndLicensesTest(unittest.TestCase):
    def test_regioni_fuori_dal_test_e_dalla_misura(self):
        cities = {"cities": {"fileXz": {"url": "u"}}}
        manifest = {"regions": [{"regionId": r, **cities} for r in
                                ("spagna", "germania", "canada-ontario", "figi-occidentali", "italia", "portogallo")]
                    + [{"regionId": "austria"}]}
        self.assertEqual(sr.training_regions(manifest, "it"), ["spagna"])
        self.assertEqual(sr.training_regions(manifest, "en"), [])

    def test_licenze_come_generate_sft(self):
        self.assertEqual(sr.section_license("https://it.wikivoyage.org/wiki/Siviglia", False, "it"), "CC BY-SA 4.0")
        self.assertEqual(sr.section_license("https://en.wikipedia.org/wiki/Seville", False, "en"), gs.WIKI_LICENSE)
        self.assertEqual(sr.section_license("https://travel.gc.ca/destinations/spain", False, "en"), gs.GUIDE_LICENSES["travel.gc.ca"])
        self.assertIn("tradotta automaticamente", sr.section_license("https://en.wikivoyage.org/wiki/Spain", True, "it"))
        self.assertIsNone(sr.section_license("https://example.com/x", False, "it"))


SPAIN_GUIDE = [("spagna", "TRASPORTI", "Come spostarsi", "I treni collegano le principali città del paese.", "https://it.wikivoyage.org/wiki/Spagna")]
SEVILLE = [
    ("Siviglia", "ALLOGGIO", "Dove alloggiare",
     "L'Hotel Sole ha camere doppie a 40 euro vicino alla cattedrale.\nL'ostello del centro costa poco ed è vicino alla stazione.",
     "https://it.wikivoyage.org/wiki/Siviglia"),
    ("Siviglia", "STORIA", "Storia",
     "Siviglia fu fondata dai Romani con il nome di Hispalis nel II secolo a.C. e divenne un porto importante.",
     "https://it.wikipedia.org/wiki/Siviglia"),
    ("Siviglia", "COSA_VEDERE", "Cosa vedere", "La cattedrale e il palazzo dell'Alcázar sono i monumenti principali della città.",
     "https://it.wikivoyage.org/wiki/Siviglia"),
]


class RowsTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.cache = Path(tmp.name)
        with contextlib.closing(sqlite3.connect(self.cache / "guides.db")) as db:
            db.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT)")
            db.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?)", SPAIN_GUIDE)
            db.commit()
        with contextlib.closing(sqlite3.connect(self.cache / "spagna.cities.db")) as db:
            db.execute("CREATE TABLE city_sections (city TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT, translated INTEGER)")
            db.executemany("INSERT INTO city_sections VALUES (?,?,?,?,?,0)", SEVILLE)
            db.commit()
        manifest = {"guides": {"fileXz": {"url": "g"}},
                    "regions": [{"regionId": "spagna", "displayName": "Spagna", "cities": {"fileXz": {"url": "c"}}}]}
        for patch in (mock.patch.object(er, "fetch", return_value=json.dumps(manifest).encode()),
                      mock.patch.object(er, "download", side_effect=lambda url, out: out),
                      mock.patch.dict(sr.QUESTIONS["it"], {"ALLOGGIO": ["Dove passo la notte a {c}?"],
                                                           "STORIA": ["{c} ha origini romane?"],
                                                           "COSA_VEDERE": ["Cosa fare a {c} in poche ore?"]})):
            patch.start()
            self.addCleanup(patch.stop)

    def rows(self, **kw):
        with contextlib.redirect_stdout(io.StringIO()):
            return sr.retrieval_rows("it", 9, random.Random(1), self.cache, regions=["spagna"], **kw)

    def test_righe_con_il_contesto_della_ricerca_e_risposta_dalla_sezione(self):
        rows, attribution = self.rows()
        by_cat = {r["category"]: r for r in rows}
        self.assertEqual(set(by_cat), {"ALLOGGIO", "STORIA", "COSA_VEDERE"})
        for cat, r in by_cat.items():
            user, assistant = r["messages"]
            self.assertEqual((user["role"], assistant["role"]), ("user", "assistant"))
            self.assertEqual((r["kind"], r["region"], r["translated"]), ("pos", "citta:Siviglia", False))
            body = next(b for _, c, _, b, _ in SEVILLE if c == cat)
            q = user["content"].split("DOMANDA: ")[1]
            top, stems = er.search(er.build_db(self.cache / "guides.db", "spagna", SEVILLE), "spagna", q, ["Siviglia"], "it", True)
            self.assertEqual(user["content"], gs.LANGS["it"]["prompt"](er.select_context([s["body"] for s in top], stems), q))
            self.assertGreaterEqual(len(assistant["content"]), sr.MIN_ANSWER)
            self.assertTrue(all(line in body for line in assistant["content"].split("\n")))
        self.assertIn("Romani", by_cat["STORIA"]["messages"][1]["content"])
        self.assertIn(("citta:Siviglia", "Siviglia", "https://it.wikipedia.org/wiki/Siviglia", gs.WIKI_LICENSE), attribution)
        self.assertIn(("citta:Siviglia", "Siviglia", "https://it.wikivoyage.org/wiki/Siviglia", "CC BY-SA 4.0"), attribution)
        self.assertEqual(len(attribution), len(set(attribution)))

    def test_storia_senza_le_parole_della_domanda_resta_fuori(self):
        with mock.patch.dict(sr.QUESTIONS["it"], {"STORIA": ["{c} è una città medievale?"]}):
            rows, _ = self.rows()
        self.assertNotIn("STORIA", {r["category"] for r in rows})

    def test_rifiuto_solo_se_il_contesto_non_ha_la_categoria(self):
        other = {"city": "Siviglia", "category": "COSA_VEDERE", "body": SEVILLE[2][3]}
        hotel = {"city": None, "region": "spagna", "category": "ALLOGGIO", "title": "Dormire", "body": "Hotel ovunque nel paese."}
        refusal = lambda topic: f"Il contesto non contiene informazioni {topic}"
        with mock.patch.object(er, "search", return_value=([other], set())):
            rows, _ = self.rows(refusal=refusal, negatives=5)
        neg = [r for r in rows if r["kind"] == "neg"]
        self.assertEqual({r["category"] for r in neg}, {"ALLOGGIO", "STORIA"})
        self.assertEqual(next(r for r in neg if r["category"] == "ALLOGGIO")["messages"][1]["content"],
                         "Il contesto non contiene informazioni su dove dormire")
        with mock.patch.object(er, "search", return_value=([hotel], set())):
            rows, _ = self.rows(refusal=refusal, negatives=5)
        self.assertEqual([r for r in rows if r["category"] == "ALLOGGIO"], [])


if __name__ == "__main__":
    unittest.main()
