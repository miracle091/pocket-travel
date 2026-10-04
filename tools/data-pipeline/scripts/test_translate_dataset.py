#!/usr/bin/env python3
"""Test di translate_dataset.py sulle parti senza modello: needs_translation, controllo di plausibilita', divisione
in frasi e cache. Il modello MarianMT (torch/transformers, importati solo in _load) non viene mai caricato: Translator
usa una cache gia' piena o un _run sostituito.

Uso: python test_translate_dataset.py
"""
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import translate_dataset as ts


class NeedsTranslationTest(unittest.TestCase):
    def test_altra_lingua_assente(self):
        self.assertFalse(ts.needs_translation("testo", ""))
        self.assertFalse(ts.needs_translation("", ""))

    def test_propria_assente(self):
        self.assertTrue(ts.needs_translation("", "x"))

    def test_propria_povera(self):
        self.assertTrue(ts.needs_translation("a" * 100, "b" * 101))
        self.assertFalse(ts.needs_translation("a" * 100, "b" * 100))

    def test_propria_lunga_serve_il_doppio(self):
        self.assertFalse(ts.needs_translation("a" * 400, "b" * 799))
        self.assertTrue(ts.needs_translation("a" * 400, "b" * 800))

    def test_altra_piu_corta_non_traduce(self):
        self.assertFalse(ts.needs_translation("a" * 100, "b" * 50))


class PlausibleTest(unittest.TestCase):
    def test_stessi_numeri_con_virgola_o_punto(self):
        self.assertTrue(ts._plausible("Costa 3,50 euro.", "It costs 3.50 euros."))

    def test_numeri_diversi(self):
        self.assertFalse(ts._plausible("Aperto dalle 9 alle 17.", "Open from 9 to 18."))

    def test_lunghezza_fuori_misura(self):
        self.assertFalse(ts._plausible("Una frase di media lunghezza qui.", "Ok " * 50))
        self.assertFalse(ts._plausible("Una frase di media lunghezza qui, abbastanza lunga da contare." * 3, "Si."))

    def test_numbers_ordinati(self):
        self.assertEqual(["1.5", "20"], ts._numbers("20 e 1,5"))


class SentenceSplitTest(unittest.TestCase):
    def test_divide_prima_di_maiuscola_cifra_o_virgolette(self):
        self.assertEqual(["Uno.", "Due!", "3 tre?", "\"Quattro\""],
                         ts.SENTENCE_SPLIT.split("Uno. Due! 3 tre? \"Quattro\""))

    def test_non_divide_prima_di_minuscola(self):
        self.assertEqual(["Vedi pag. tre e altro."], ts.SENTENCE_SPLIT.split("Vedi pag. tre e altro."))

    def test_pieces_mantiene_marcatori_e_righe_vuote(self):
        body = "Titolo.\n▸ Sezione uno. Seconda frase.\n• voce\n\n"
        self.assertEqual([("", ["Titolo."]), ("▸ ", ["Sezione uno.", "Seconda frase."]),
                          ("• ", ["voce"]), ("", []), ("", [])], ts.Translator._pieces(body))


class TranslatorTest(unittest.TestCase):
    def _translator(self, directory, entries=None):
        t = ts.Translator("it", "en", directory)
        for sentence, translated in (entries or {}).items():
            t.cache[t._key(sentence)] = translated
        return t

    def test_chiave_dipende_dalla_direzione(self):
        with tempfile.TemporaryDirectory() as d:
            a = ts.Translator("it", "en", d)
            b = ts.Translator("en", "it", d)
            self.assertNotEqual(a._key("Ciao."), b._key("Ciao."))
            self.assertEqual(40, len(a._key("Ciao.")))

    def test_legge_la_cache_dal_disco(self):
        with tempfile.TemporaryDirectory() as d:
            probe = ts.Translator("it", "en", d)
            key = probe._key("Ciao.")
            (Path(d) / "translations.it-en.jsonl").write_text(
                json.dumps({"k": key, "t": "Hello."}) + "\n\n", encoding="utf-8")
            self.assertEqual({key: "Hello."}, ts.Translator("it", "en", d).cache)

    def test_traduce_con_marcatori_e_senza_chiamare_il_modello(self):
        with tempfile.TemporaryDirectory() as d:
            t = self._translator(d, {"Il museo apre alle 9.": "The museum opens at 9.", "Biglietto.": "Ticket.",
                                     "Costa 5 euro.": "It costs 5 euros."})
            with mock.patch.object(t, "_run") as run:
                result = t.translate_many(["Il museo apre alle 9.\n▸ Biglietto.\n• Costa 5 euro."])
        run.assert_not_called()
        self.assertEqual(["The museum opens at 9.\n▸ Ticket.\n• It costs 5 euros."], result)

    def test_frasi_mancanti_vanno_a_run_una_volta_sola_e_ordinate(self):
        with tempfile.TemporaryDirectory() as d:
            t = self._translator(d)

            def fake_run(sentences):
                for s in sentences:
                    t.cache[t._key(s)] = s.upper()

            with mock.patch.object(t, "_run", side_effect=fake_run) as run:
                result = t.translate_many(["Beta. Alfa.", "Alfa."])
        run.assert_called_once_with(["Alfa.", "Beta."])
        self.assertEqual(["BETA. ALFA.", "ALFA."], result)

    def test_sezione_con_frase_non_plausibile_e_none_le_altre_restano(self):
        with tempfile.TemporaryDirectory() as d:
            t = self._translator(d, {"Aperto alle 9.": "Open at 10.", "Chiuso.": "Closed."})
            with mock.patch.object(t, "_run"):
                result = t.translate_many(["Aperto alle 9.", "Chiuso."])
        self.assertEqual([None, "Closed."], result)


if __name__ == "__main__":
    unittest.main()
