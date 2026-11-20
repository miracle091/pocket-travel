#!/usr/bin/env python3
"""Test di sft_selected_text.py: contesto e domande pronte uguali all'app, frasi dei frasari di Wikivoyage IT ed EN,
righe di traduzione, spiegazione, domande libere e rifiuti, nessun caso della prova del 2026-11-12.

Uso: python test_sft_selected_text.py
"""
import random
import re
import unittest
from pathlib import Path

import audit_sft
import generate_sft as gs
import sft_selected_text as st

REPO = Path(__file__).resolve().parents[3]
AI = REPO / "feature" / "ai" / "src" / "main"


def string_resource(lang, name):
    xml = (AI / "res" / ("values-en" if lang == "en" else "values") / "strings.xml").read_text(encoding="utf-8")
    return re.search(rf'<string name="{name}">(.*?)</string>', xml).group(1).replace("\\'", "'")


IT_PAGE = """== Basilari ==
* {{Traduzione|Grazie|Merci|merssì|LL-Q150 (fra)-XANA000-merci.wav}}
* {{Traduzione|Aiuto|À l'aide ! / Au secours !|aléd/osecur|<!--Pronuncia audio-->}}
** {{Traduzione|...più tranquilla?|...plus tranquille?|... plu tranqill?|<!--Pronuncia audio-->}}
* {{Traduzione|Mi fermerò per _____ notte/i|Je reste _____ nuits|je rest|<!--Pronuncia audio-->}}
** {{Traduzione|...con vista su (mare) |<!--traduzione-->|<!--Pronuncia traslitterata-->|<!--Pronuncia audio-->}}
* {{Traduzione|Uscita|[[Sortie]]|sorti|}}
* {{Traduzione|Ciao <small>(all'arrivo)</small>|Salut|salü|}}
* {{Traduzione|Accettate questa valuta?|<Acceptez-vous|x|}}
"""
EN_PAGE = """==Pronunciation==
; a, à, â : like f'''a'''ther {{IPA|a}}
==Phrase list==
===Basics===
; Thank you. : Merci. (''mehr-SEE'')
; Where is a pharmacy? : 薬局はどこですか？ ''Yakkyoku wa doko desu ka?''
; 21 : vingt-et-un (''vihng-tay-UHN'')
; FORBIDDEN : Interdit}}
; Excuse me. : .
; I'll stay for _____ nights. : Je reste _____ nuits. (''zhuh rehst'')
; Men : Ndị ikom[[File:LL-Q33578 (ibo)-Dagentle-Ndị ikom.wav]]
; already : sudah &rarr; dah
; fish :  (апсыӡ'' '')
; Goodbye :  . ( ye shua  '' '')
; Hello. : Bonjour.*
"""


class AppTextTest(unittest.TestCase):
    def test_contesto_come_contextWithSelectedText(self):
        kotlin = (AI / "kotlin" / "com" / "pockettravel" / "feature" / "ai" / "SelectedText.kt").read_text(encoding="utf-8")
        en, it = re.search(r'if \(language == "en"\) "([^"]+)" else "([^"]+)"', kotlin).groups()
        self.assertEqual(st.SELECTED_LABEL, {"en": en, "it": it})
        self.assertEqual(st.selected_context("Sortie «à gauche»", "it"), f"{it}: «Sortie à gauche»")

    def test_domande_pronte_come_le_stringhe_dell_app(self):
        for lang in ("it", "en"):
            for kind in ("translate", "meaning", "explain"):
                self.assertEqual(st.READY_QUESTIONS[lang][kind], string_resource(lang, f"ai_selected_text_{kind}_question"))


class PhrasebookTest(unittest.TestCase):
    def test_frasari_italiani(self):
        self.assertEqual(st.phrasebook_pairs(IT_PAGE, "it"),
                         [("Grazie", "Merci"), ("Aiuto", "À l'aide ! / Au secours !"), ("Uscita", "Sortie"),
                          ("Ciao (all'arrivo)", "Salut")])

    def test_frasari_inglesi_solo_nell_elenco_delle_frasi(self):
        self.assertEqual(st.phrasebook_pairs(EN_PAGE, "en"),
                         [("Thank you.", "Merci."), ("Where is a pharmacy?", "薬局はどこですか？"), ("21", "vingt-et-un"),
                          ("Men", "Ndị ikom"), ("Hello.", "Bonjour.")])

    def test_colonne_invertite_scartate(self):
        # ; 営業中 (eigyōchū) : Open in un frasario, "Open" come frase inglese in 20 frasari
        pairs = [(f"frasario:{i}", "u", "Open", f"aperto{i}") for i in range(20)] + [("frasario:Japanese", "u", "営業中 (eigyōchū)", "Open"),
                                                                                    ("frasario:Japanese", "u", "Exit", "出口")]
        self.assertEqual([p[2] for p in st.drop_reversed(pairs) if p[0] == "frasario:Japanese"], ["Exit"])
        self.assertEqual(len(st.drop_reversed(pairs[1:])), 21)  # 19 frasari non bastano


GUIDE = ("Gli autobus urbani costano 1,50 euro e il biglietto si compra dal tabaccaio prima di salire.\n"
         "Il museo civico apre alle 9 e chiude alle 18, il lunedi' e' chiuso.")


class RowsTest(unittest.TestCase):
    def rows(self, category_counts=None, **kw):
        L = gs.LANGS["it"]
        pairs = [("frasario:Francese", "https://it.wikivoyage.org/wiki/Francese", it, fr) for it, fr in
                 [("Grazie", "Merci"), ("Uscita", "Sortie"), ("Per favore", "S'il vous plaît"), ("Versare", "Pour"),
                  ("Un cassoulet", "Un cassoulet")]]
        data = {"francia": ("Francia", [("TRASPORTI", GUIDE.split("\n")[0], False), ("COSA_VEDERE", GUIDE.split("\n")[1], False)], []),
                "canada": ("Canada", [("TRASPORTI", GUIDE.split("\n")[0], False)], [])}
        counts = {"translate": 10, "meaning": 10, "explain": 10, "free": 10, "refusal": 10, **kw}
        return st.selected_text_rows("it", L, pairs, data, counts, random.Random(1), gs.make_answer_for(L),
                                     [gs.all_keywords(L), gs.all_keywords(gs.LANGS["en"])], test_regions={"canada"},
                                     category_counts=category_counts)

    def test_traduci_e_cosa_significa_dal_frasario(self):
        rows = self.rows()
        by_q = {}
        for r in rows:
            context, q, answer = audit_sft.parse_row(r)
            by_q.setdefault(q, []).append((context, answer, r))
        translate = {c: a for c, a, _ in by_q[st.READY_QUESTIONS["it"]["translate"]]}
        self.assertEqual(translate[st.selected_context("Merci", "it")], "Grazie")
        meaning = {c: a for c, a, _ in by_q[st.READY_QUESTIONS["it"]["meaning"]]}
        self.assertEqual(meaning[st.selected_context("Sortie", "it")], "Significa «Uscita».")
        for _, _, r in by_q[st.READY_QUESTIONS["it"]["translate"]]:
            self.assertEqual((r["kind"], r["category"], r["region"]), ("pos", "TRADUZIONE", "frasario:Francese"))

    def test_spiegami_domande_libere_e_rifiuti_dalle_guide(self):
        rows = self.rows()
        explain = [r for r in rows if audit_sft.parse_row(r)[1] == st.READY_QUESTIONS["it"]["explain"]]
        self.assertTrue(explain)
        for r in explain:
            context, _, answer = audit_sft.parse_row(r)
            self.assertTrue(context.startswith("Testo selezionato dall'utente: «"))
            self.assertIn(answer, context)
        free = [r for r in rows if r["kind"] == "pos" and r["category"] in ("TRASPORTI", "COSA_VEDERE")
                and audit_sft.parse_row(r)[1] not in st.READY_QUESTIONS["it"].values()]
        self.assertTrue(free)
        refusals = [r for r in rows if r["kind"] == "neg"]
        self.assertTrue(refusals)
        for r in refusals:
            self.assertTrue(r["messages"][1]["content"].startswith("Il contesto non contiene informazioni"))

    def test_righe_valide_senza_regioni_di_test_ne_casi_misurati(self):
        rows = self.rows()
        for r in rows:
            self.assertEqual(audit_sft.hard_errors(r, "it"), [], r)
            self.assertNotEqual(r["region"], "canada")
            self.assertNotIn("Canada", audit_sft.parse_row(r)[1], r)
            self.assertFalse(st.measured(audit_sft.parse_row(r)[0]), r)
        keys = [tuple(audit_sft.parse_row(r)[:2]) for r in rows]
        self.assertEqual(len(keys), len(set(keys)))
        # una risposta del frasario al massimo una volta per domanda pronta (cap_repeats ne terrebbe solo due)
        answers = [(audit_sft.parse_row(r)[1], r["messages"][1]["content"]) for r in rows if r["category"] == "TRADUZIONE"]
        self.assertEqual(len(answers), len(set(answers)))


    def test_rifiuti_in_proporzione_alle_categorie(self):
        L = gs.LANGS["it"]
        common = "SICUREZZA"  # nessun testo delle righe la tratta (la frase sugli autobus tratta ACQUISTI)
        self.assertIn(common, L["topic"])
        rows = self.rows(translate=0, meaning=0, explain=0, free=0, refusal=10,
                         category_counts={common: 100000})
        self.assertEqual({r["category"] for r in rows if r["kind"] == "neg"}, {common})


class AuditTest(unittest.TestCase):
    def test_traduzioni_non_estrattive_accettate(self):
        row = {"messages": [{"role": "user", "content": gs.LANGS["it"]["prompt"](st.selected_context("Merci", "it"), "Traduci in italiano il testo selezionato")},
                            {"role": "assistant", "content": "Grazie"}],
               "kind": "pos", "region": "frasario:Francese", "category": "TRADUZIONE", "translated": False}
        self.assertEqual(audit_sft.hard_errors(row, "it"), [])


if __name__ == "__main__":
    unittest.main()
