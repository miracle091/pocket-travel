#!/usr/bin/env python3
"""Test delle note di ATTRIBUTION nella lingua del dataset (localized_attribution di generate_sft.py): il dataset inglese
non ha note italiane, l'italiano resta com'e'.

Uso: python test_sft_attribution.py
"""
import unittest

import generate_sft
import generate_sft_dataset as it
import travel_questions
import wiki_dump

ITALIAN_WORDS = (" del ", " delle ", "testo ", "fonti ", " per riga", "domande ", "vaccinazioni", "numeri ", "fatti rapidi",
                 "dati originali", "guida generale")


class LocalizedAttributionTest(unittest.TestCase):
    def rows(self):
        return ([("-", f"dump {w} del 2026-10-01", wiki_dump.export_url(w, "2026-10-01"), "CC BY-SA 4.0 (testo delle pagine elencate sopra)")
                 for w in ("enwikivoyage", "enwiki")]
                + [("-", "off-topic (alpaca_cleaned)", "https://huggingface.co/datasets/yahma/alpaca-cleaned", it.ALPACA_LICENSE)]
                + generate_sft.VACC_ATTRIBUTION + travel_questions.ATTRIBUTION
                + [("_general", "guida generale", "https://en.wikivoyage.org/wiki/Visa", "CC BY-SA 4.0")]
                + [(r, n, u, l) for r, n, u, l in [("-", "numeri di emergenza", "https://github.com/x/emergency-numbers.tsv",
                    "fonti per riga nel file: Travel.gc.ca e gov.uk (Open Government Licence), Wikipedia e Wikivoyage (CC BY-SA 4.0), Wikidata (CC0)"),
                   ("-", "fatti rapidi: Wikidata (capitale, valuta, prefisso, lato di guida e dove manca Wikivoyage)",
                    "https://www.wikidata.org/", "CC0 1.0")]])

    def test_dataset_inglese_senza_note_italiane(self):
        out = generate_sft.localized_attribution(self.rows(), "en")
        self.assertEqual(len(out), len(self.rows()))
        for rid, name, url, lic in out:
            for word in ITALIAN_WORDS:
                self.assertNotIn(word, f" {name} {lic} ".lower(), (name, lic))
        self.assertIn(("-", "dump enwiki of 2026-10-01"), [(r, n) for r, n, _, _ in out])
        self.assertEqual([u for _, _, u, _ in out], [u for _, _, u, _ in self.rows()])  # url invariati

    def test_dataset_italiano_invariato(self):
        self.assertEqual(generate_sft.localized_attribution(self.rows(), "it"), self.rows())


if __name__ == "__main__":
    unittest.main()
