#!/usr/bin/env python3
"""Test di --guide-sections di generate_sft.py: quali sezioni di guides.db entrano (consigli di travel.gc.ca e FCDO,
Sleep e Talk di Wikivoyage nella lingua del dataset) e quali no (le "#for-nationality=", i Fatti rapidi, le sezioni che
danno gia' i dump), le pagine condivise e il tetto per categoria, le licenze OGL in ATTRIBUTION, le domande nuove fuori
dal test esteso e i campi dei Fatti rapidi uguali a quelli della pipeline dei contenuti.

Uso: python test_sft_guide_sections.py
"""
import random
import re
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

import generate_eval_set
import generate_eval_set_en
import generate_sft_dataset as it
import generate_sft_dataset_en as en
from generate_sft import (GUIDE_LICENSES, GUIDE_QUESTIONS, LANGS, OTHER, QUICK_FACT_WIKIDATA, guide_source,
                          load_guide_sections, select_guide_sections, with_guide_sections)

ROOT = Path(__file__).resolve().parents[3]
COUNTRY_FACTS = ROOT / "tools" / "data-pipeline" / "content" / "src" / "main" / "kotlin" / "com" / "pockettravel" / "pipeline" / "GenerateCountryFacts.kt"

GC = "https://travel.gc.ca/destinations/jp"
UK = "https://www.gov.uk/foreign-travel-advice/palestine/safety-and-security"
ROWS = [
    ("giappone", "SICUREZZA", "Safety and security (Government of Canada)", "Take normal security precautions.", GC),
    ("giappone", "SALUTE", "Health (Government of Canada)", "Health care is very good.", GC),
    ("giappone", "SALUTE", "Stay healthy", "Tap water is safe.", "https://en.wikivoyage.org/wiki/Japan"),  # dai dump
    ("giappone", "ALLOGGIO", "Sleep", "Ryokan are traditional inns.", "https://en.wikivoyage.org/wiki/Japan"),
    ("giappone", "FATTI_RAPIDI", "Quick facts", "Capital: Tokyo", "https://en.wikivoyage.org/wiki/Japan"),
    ("palestina", "SICUREZZA", "Israel: areas (UK government)", "Avoid the border.", UK + "#for-nationality=IL"),
    ("palestina", "SICUREZZA", "Israel (UK government)", "See the Israel guide.", UK + "#not-for-nationality=IL"),
    ("francia", "ALLOGGIO", "Dove alloggiare", "Gli alberghi sono cari.", "https://it.wikivoyage.org/wiki/Francia"),
]


class LoadGuideSectionsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.db = Path(self.dir.name) / "guides.db"
        with closing(sqlite3.connect(self.db)) as db:
            db.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT, translated INTEGER)")
            db.executemany("INSERT INTO guide_sections VALUES (?,?,?,?,?,0)", ROWS)
            # tradotta da translate_guides.py: guides.db non dice con quale modello, quindi fuori
            db.execute("INSERT INTO guide_sections VALUES ('giappone', 'SICUREZZA', 'Sicurezza', 'Precauzioni normali.', ?, 1)", (GC,))
            db.commit()

    def tearDown(self):
        self.dir.cleanup()

    def bodies(self, lang):
        return {rid: [b for _, b, _, _ in secs] for rid, secs in load_guide_sections(self.db, lang).items()}

    def test_english_dataset(self):
        self.assertEqual(self.bodies("en"), {
            "giappone": ["Take normal security precautions.", "Health care is very good.", "Ryokan are traditional inns."],
            "palestina": ["See the Israel guide."]})  # fuori la sezione per i soli cittadini israeliani

    def test_italian_dataset(self):
        # i consigli governativi si', le sezioni di Wikivoyage inglese no (risposta in inglese a un utente italiano)
        self.assertEqual(self.bodies("it"), {
            "giappone": ["Take normal security precautions.", "Health care is very good."],
            "palestina": ["See the Israel guide."], "francia": ["Gli alberghi sono cari."]})

    def test_licenses(self):
        self.assertIn("Open Government Licence - Canada", guide_source(GC, "en"))
        self.assertIn("Open Government Licence v3.0", guide_source(UK, "en"))
        self.assertEqual("CC BY-SA 4.0", guide_source("https://en.wikivoyage.org/wiki/Japan", "en"))
        self.assertIsNone(guide_source("https://en.wikivoyage.org/wiki/Japan", "it"))
        self.assertIsNone(guide_source("https://www.viaggiaresicuri.it/x", "it"))
        for lic in GUIDE_LICENSES.values():  # la dichiarazione che le due licenze chiedono
            self.assertRegex(lic, r"contains (public sector )?information licensed under the Open Government Licence")


class SelectGuideSectionsTest(unittest.TestCase):
    def test_shared_page_kept_by_test_region(self):
        sec = ("SICUREZZA", "Same text for the whole country.", GC, False)
        kept, dropped = select_guide_sections({"a-nord": [sec], "z-test": [sec]}, random.Random(1), {"z-test"})
        self.assertEqual(kept, {"z-test": [sec]})
        self.assertEqual(dropped, [("a-nord", "guides-db", "SICUREZZA", "pagina-condivisa")])

    def test_cap_per_category_outside_test_regions(self):
        sections = {f"r{i}": [("SALUTE", f"health {i}", GC, False), ("ALLOGGIO", f"sleep {i}", GC, False)] for i in range(10)}
        sections["t"] = [("SALUTE", "test health", GC, False)]
        kept, dropped = select_guide_sections(sections, random.Random(2), {"t"}, cap=3)
        flat = [s for secs in kept.values() for s in secs]
        self.assertEqual(sum(s[0] == "SALUTE" for s in flat), 4)  # 3 piu' quella della regione di test
        self.assertEqual(sum(s[0] == "ALLOGGIO" for s in flat), 3)
        self.assertEqual(len(dropped), 14)
        self.assertTrue(all(d[3] == "tetto-categoria" for d in dropped))


class GuideQuestionsTest(unittest.TestCase):
    def test_tables(self):
        for lang in ("it", "en"):
            L = with_guide_sections(LANGS[lang], lang)
            for cat in GUIDE_QUESTIONS[lang]:
                self.assertEqual(L["questions"][cat], GUIDE_QUESTIONS[lang][cat])
                self.assertEqual(L["other_questions"][cat], GUIDE_QUESTIONS[OTHER[lang]][cat])
                self.assertIn(cat, L["topic"])
                self.assertIn(cat, L["keywords"])
            self.assertNotIn("FRASI_UTILI", LANGS[lang]["questions"])  # LANGS non cambia

    def test_keywords_recognise_sections(self):
        L = with_guide_sections(LANGS["en"], "en")
        self.assertTrue(it.covers("FRASI_UTILI", "English is widely spoken in the cities.", L["keywords"]))
        self.assertTrue(it.covers("ALLOGGIO", "Hostels and campsites are cheap.", L["keywords"]))

    def test_questions_not_in_eval_sets(self):
        tables = [generate_eval_set.PARA, generate_eval_set.PARA_EN, generate_eval_set.PARA_CITY,
                  generate_eval_set_en.PARA, generate_eval_set_en.PARA_CITY_EN]
        held_out = {q for t in tables for qs in t.values() for q in ([qs] if isinstance(qs, str) else qs)}
        for lang, table in GUIDE_QUESTIONS.items():
            for qs in table.values():
                self.assertFalse(set(qs) & held_out, lang)
                self.assertTrue(all("{r}" in q for q in qs), lang)


class QuickFactFieldsTest(unittest.TestCase):
    def test_fields_match_content_pipeline(self):
        # le etichette di QuickFactLabels (GenerateCountryFacts.kt), piu' i numeri di emergenza
        kotlin = COUNTRY_FACTS.read_text(encoding="utf-8")
        for name, module, emergency in (("ITALIAN", it, "Numeri di emergenza"), ("ENGLISH", en, "Emergency numbers")):
            labels = re.search(rf"val {name} = QuickFactLabels\(([^)]*)\)", kotlin).group(1)
            self.assertEqual(set(re.findall(r'"([^"]+)"', labels)) | {emergency}, set(module.QUICK_FACT_QUESTIONS), name)
            self.assertEqual(set(module.QUICK_FACT_QUESTIONS), set(module.QUICK_FACT_TOPIC), name)
            self.assertEqual(set(module.QUICK_FACT_QUESTIONS), set(module.QUICK_FACT_KEYWORDS), name)

    def test_wikidata_fields_known(self):
        for lang, module in (("it", it), ("en", en)):
            self.assertTrue(set(QUICK_FACT_WIKIDATA[lang]) < set(module.QUICK_FACT_QUESTIONS), lang)

    def test_load_quick_facts_reads_new_fields(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "guides-en.db"
            with closing(sqlite3.connect(path)) as db:
                db.execute("CREATE TABLE guide_sections (regionId TEXT, category TEXT, body TEXT)")
                db.execute("INSERT INTO guide_sections VALUES ('giappone', 'FATTI_RAPIDI', ?)",
                           ("Capital: Tokyo\nCurrency: yen (JPY)\nCalling code: +81\nDriving side: left",))
                db.commit()
            _, fields = it.load_quick_facts(path, en.QUICK_FACT_QUESTIONS)["giappone"]
        self.assertEqual(fields, {"Capital": "Capital: Tokyo", "Currency": "Currency: yen (JPY)",
                                  "Calling code": "Calling code: +81", "Driving side": "Driving side: left"})


if __name__ == "__main__":
    unittest.main()
