#!/usr/bin/env python3
"""Test di --city-daily-life e --balanced-negatives di generate_sft.py: la sezione VITA_QUOTIDIANA delle citta' ha lo
stesso titolo di GenerateCities.kt (quella che l'app mette in cities.db), le domande nuove non sono nel test esteso, le
radici in piu' riconoscono le sezioni pratiche delle citta' e i rifiuti seguono quanto e' presente la categoria.

Uso: python test_sft_daily_life.py
"""
import random
import re
import unittest
from collections import Counter
from pathlib import Path

import generate_eval_set
import generate_eval_set_en
import generate_sft
import generate_sft_dataset as it
from generate_sft import CITY_DAILY_LIFE, DAILY_LIFE, LANGS, weighted_category, with_city_daily_life

ROOT = Path(__file__).resolve().parents[3]
GENERATE_CITIES = ROOT / "tools" / "data-pipeline" / "content" / "src" / "main" / "kotlin" / "com" / "pockettravel" / "pipeline" / "GenerateCities.kt"

CITY_IT = """== Cosa vedere ==
Il duomo e il castello sono in piazza, a pochi passi dalla stazione.
== Informazioni utili ==
L'ufficio turistico in piazza del Duomo e' aperto tutti i giorni dalle 9 alle 18 e distribuisce mappe gratuite.
"""
CITY_EN = """== See ==
The cathedral and the castle are on the main square.
== Cope ==
The tourist office on the main square opens daily from 9AM to 6PM and has free maps of the old town.
"""


class CityDailyLifeTest(unittest.TestCase):
    def setUp(self):
        self._clean, it.app_clean = it.app_clean, lambda raw: raw.strip()  # niente JVM: il testo e' gia' pulito

    def tearDown(self):
        it.app_clean = self._clean

    def test_headings_match_generate_cities(self):
        kotlin = GENERATE_CITIES.read_text(encoding="utf-8")
        for lang, table in CITY_DAILY_LIFE.items():
            self.assertRegex(kotlin, rf'"{re.escape(table["heading"])}" to "{DAILY_LIFE}"', lang)

    def test_city_section_becomes_daily_life(self):
        for lang, text in (("it", CITY_IT), ("en", CITY_EN)):
            L = with_city_daily_life(LANGS[lang], lang)
            secs = dict(it.parse_sections(text, L["city_headings"]))
            self.assertIn(DAILY_LIFE, secs, lang)
            self.assertTrue(it.covers(DAILY_LIFE, secs[DAILY_LIFE], L["keywords"]), lang)
            # senza l'opzione la sezione resta fuori, come nel v9
            self.assertNotIn(DAILY_LIFE, dict(it.parse_sections(text, LANGS[lang]["city_headings"])), lang)

    def test_country_keywords_kept_and_tables_copied(self):
        for lang in ("it", "en"):
            L = with_city_daily_life(LANGS[lang], lang)
            self.assertTrue(set(LANGS[lang]["keywords"][DAILY_LIFE]) < set(L["keywords"][DAILY_LIFE]))
            self.assertNotIn(DAILY_LIFE, LANGS[lang]["city_questions"])  # LANGS non cambia
            self.assertEqual(L["other_city_questions"][DAILY_LIFE], CITY_DAILY_LIFE[generate_sft.OTHER[lang]]["questions"])

    def test_keywords_at_word_start(self):
        L = with_city_daily_life(LANGS["en"], "en")
        self.assertFalse(it.covers(DAILY_LIFE, "The north coast is rocky and inhospitable.", L["keywords"]))
        self.assertTrue(it.covers(DAILY_LIFE, "Pharmacies open until 8PM.", L["keywords"]))

    def test_questions_not_in_eval_sets(self):
        tables = [generate_eval_set.PARA, generate_eval_set.PARA_EN, generate_eval_set.PARA_CITY,
                  generate_eval_set_en.PARA, generate_eval_set_en.PARA_CITY_EN]
        held_out = {q for t in tables for qs in t.values() for q in ([qs] if isinstance(qs, str) else qs)}
        for lang, table in CITY_DAILY_LIFE.items():
            self.assertFalse(set(table["questions"]) & held_out, lang)
            self.assertTrue(all("{r}" in q for q in table["questions"]), lang)


class BalancedNegativesTest(unittest.TestCase):
    def test_follows_counts(self):
        rng = random.Random(1)
        picks = Counter(weighted_category(rng, ["A", "B"], Counter(A=900, B=100)) for _ in range(5000))
        self.assertAlmostEqual(picks["A"] / 5000, 0.9, delta=0.02)

    def test_category_without_count_still_possible(self):
        rng = random.Random(2)
        picks = Counter(weighted_category(rng, ["A", "B"], Counter(A=10)) for _ in range(2000))
        self.assertGreater(picks["B"], 0)


if __name__ == "__main__":
    unittest.main()
