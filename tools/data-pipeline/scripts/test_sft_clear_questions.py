#!/usr/bin/env python3
"""Test di --clear-questions di generate_sft.py: le domande di AMBIGUOUS_QUESTIONS esistono nelle tabelle (altrimenti
il filtro non toglie nulla) e spariscono, SALUTE non ha piu' domande sui vaccini (le stesse parole di
isVaccinationQuestion), ogni categoria conserva delle domande e le tabelle originali non cambiano; il test esteso non ha domande ambigue simili.

Uso: python test_sft_clear_questions.py
"""
import re
import unittest

import generate_eval_set
import generate_eval_set_en
from generate_sft import AMBIGUOUS_QUESTIONS, DAILY_LIFE, LANGS, VACC_WORDS, with_city_daily_life, with_clear_questions

POOLS = ("questions", "other_questions", "city_questions", "other_city_questions")


def all_questions(L):
    return {q for k in POOLS for qs in L[k].values() for q in qs}


class ClearQuestionsTest(unittest.TestCase):
    def test_ambiguous_questions_exist_and_go(self):
        before = all_questions(LANGS["it"]) | all_questions(LANGS["en"])
        self.assertEqual(AMBIGUOUS_QUESTIONS - before, set())  # una domanda rinominata non sfugge al filtro
        for lang in ("it", "en"):
            self.assertFalse(all_questions(with_clear_questions(LANGS[lang])) & AMBIGUOUS_QUESTIONS, lang)

    def test_no_vaccine_questions_in_health(self):
        for lang in ("it", "en"):
            self.assertTrue(any(VACC_WORDS.search(q) for k in POOLS for q in LANGS[lang][k].get("SALUTE", [])), lang)
            L = with_clear_questions(LANGS[lang])
            self.assertFalse([q for k in POOLS for q in L[k].get("SALUTE", []) if VACC_WORDS.search(q)], lang)

    def test_every_category_keeps_questions(self):
        for lang in ("it", "en"):
            L = with_clear_questions(with_city_daily_life(LANGS[lang], lang))
            for k in POOLS:
                self.assertTrue(all(L[k].values()), (lang, k))
            self.assertTrue(L["city_questions"][DAILY_LIFE], lang)

    def test_original_tables_unchanged(self):
        before = {lang: all_questions(LANGS[lang]) for lang in ("it", "en")}
        for lang in ("it", "en"):
            with_clear_questions(LANGS[lang])
            self.assertEqual(all_questions(LANGS[lang]), before[lang])

    def test_eval_sets_without_practical_info_questions(self):
        # "info pratiche"/"practical info" la soddisfa ogni sezione, come AMBIGUOUS_QUESTIONS: fuori anche dal test esteso
        for table in (generate_eval_set.PARA, generate_eval_set_en.PARA):
            self.assertFalse([q for q in table[DAILY_LIFE] if re.search(r"pratic|practical", q, re.I)])


if __name__ == "__main__":
    unittest.main()
