#!/usr/bin/env python3
"""Test di translate_dataset.py sulle parti senza modello: needs_translation, controllo di plausibilita', divisione
in frasi e cache. Il modello MarianMT (torch/transformers, importati solo in _load) non viene mai caricato: Translator
usa una cache gia' piena o un _run sostituito.

Uso: python test_translate_dataset.py
"""
import io
import json
import os
import subprocess
import sys
import tempfile
import types
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

    def test_carattere_perso_dal_modello(self):
        # MarianMT rende " ⁇ " i caratteri che non conosce: "Rösti" -> "R ⁇ sti", "грн" -> " ⁇ "
        self.assertFalse(ts._plausible("Rösti is a potato dish.", "Il R ⁇ sti è un piatto di patate."))

    def test_numbers_ordinati(self):
        self.assertEqual(["1.5", "20"], ts._numbers("20 e 1,5"))


class SentenceSplitTest(unittest.TestCase):
    def test_divide_prima_di_maiuscola_cifra_o_virgolette(self):
        self.assertEqual(["Uno.", "Due!", "3 tre?", "\"Quattro\""],
                         ts.SENTENCE_SPLIT.split("Uno. Due! 3 tre? \"Quattro\""))

    def test_non_divide_prima_di_minuscola(self):
        self.assertEqual(["Vedi pag. tre e altro."], ts.SENTENCE_SPLIT.split("Vedi pag. tre e altro."))

    def test_non_divide_dopo_un_abbreviazione(self):
        # "St." tradotto da solo darebbe "San Pietroburgo" e poi "Petersburg." in inglese
        self.assertEqual(["Drones hit Moscow and St. Petersburg.", "Flights stopped."],
                         ts.SENTENCE_SPLIT.split("Drones hit Moscow and St. Petersburg. Flights stopped."))
        self.assertEqual(["Ask the U.S. Embassy."], ts.SENTENCE_SPLIT.split("Ask the U.S. Embassy."))

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

    def test_parentesi_svuotate_dalla_traduzione_tolte(self):
        # MarianMT perde i caratteri cinesi o arabi: "Yu Cai (豫菜)" -> "Yu Cai ()"
        with tempfile.TemporaryDirectory() as d:
            t = self._translator(d, {"Lo Henan è la patria di Yu Cai (豫菜).": "Henan is home to Yu Cai ().",
                                     "I Bai (白族, Baizu) vivono qui.": "The Bai (, Baizu) live here."})
            self.assertEqual(["Henan is home to Yu Cai.\nThe Bai (Baizu) live here."],
                             t.translate_many(["Lo Henan è la patria di Yu Cai (豫菜).\nI Bai (白族, Baizu) vivono qui."]))

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


class RunTest(unittest.TestCase):
    """_run con _decode sostituito: ordine per lunghezza, lotti, cache su disco."""

    def test_risultati_nell_ordine_di_ingresso_lotti_per_lunghezza_e_cache_scritta(self):
        with tempfile.TemporaryDirectory() as d:
            t = ts.Translator("it", "en", d, batch=2)
            t.model = object()  # niente _load
            batches = []

            def decode(batch):
                batches.append(list(batch))
                return [f" EN:{s} " for s in batch]

            sentences = ["Una frase molto più lunga delle altre.", "Corta.", "Media frase.", "Ok."]
            with mock.patch.object(t, "_decode", side_effect=decode), mock.patch("sys.stderr"):
                out = t._run(sentences)
            self.assertEqual([[ "Ok.", "Corta."], ["Media frase.", "Una frase molto più lunga delle altre."]], batches)
            self.assertEqual([f"EN:{s}" for s in sentences], out)
            lines = (Path(d) / "translations.it-en.jsonl").read_text(encoding="utf-8").splitlines()
            reloaded = ts.Translator("it", "en", d)
        self.assertEqual(4, len(lines))
        self.assertEqual("EN:Corta.", reloaded.cache[reloaded._key("Corta.")])
        self.assertIn("più", lines[0])  # UTF-8 non escapato

    def test_traduzione_in_cache_non_viene_richiesta_di_nuovo(self):
        with tempfile.TemporaryDirectory() as d:
            t = ts.Translator("it", "en", d)
            t.model = object()
            t.cache[t._key("Ciao.")] = "Hello."
            with mock.patch.object(t, "_decode", side_effect=lambda b: [s + "!" for s in b]) as decode, \
                    mock.patch("sys.stderr"):
                result = t.translate_many(["Ciao.", "Grazie."])
        self.assertEqual(["Hello.", "Grazie.!"], result)
        decode.assert_called_once_with(["Grazie."])

    def test_corpo_vuoto_e_solo_righe_vuote(self):
        with tempfile.TemporaryDirectory() as d:
            t = ts.Translator("it", "en", d)
            with mock.patch.object(t, "_run") as run:
                self.assertEqual(["", "\n"], t.translate_many(["", "\n"]))
        run.assert_not_called()


class PickDiscreteGpuTest(unittest.TestCase):
    def setUp(self):
        env = {k: v for k, v in os.environ.items() if k not in ("HIP_VISIBLE_DEVICES", "CUDA_VISIBLE_DEVICES")}
        patcher = mock.patch.dict(os.environ, env, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        modules = mock.patch.dict(sys.modules)
        modules.start()
        self.addCleanup(modules.stop)
        sys.modules.pop("torch", None)

    def test_imposta_entrambe_le_variabili_con_la_gpu_migliore(self):
        with mock.patch("subprocess.run", return_value=mock.Mock(stdout="1\n")):
            ts._pick_discrete_gpu()
        self.assertEqual("1", os.environ["HIP_VISIBLE_DEVICES"])
        self.assertEqual("1", os.environ["CUDA_VISIBLE_DEVICES"])

    def test_variabile_gia_impostata_viene_rispettata(self):
        os.environ["HIP_VISIBLE_DEVICES"] = "0"
        with mock.patch("subprocess.run") as run:
            ts._pick_discrete_gpu()
        run.assert_not_called()
        self.assertNotIn("CUDA_VISIBLE_DEVICES", os.environ)

    def test_torch_gia_importato_non_si_tocca_nulla(self):
        sys.modules["torch"] = types.ModuleType("torch")
        with mock.patch("subprocess.run") as run:
            ts._pick_discrete_gpu()
        run.assert_not_called()

    def test_nessuna_gpu_lascia_l_ambiente_com_e(self):
        with mock.patch("subprocess.run", return_value=mock.Mock(stdout="-1\n")):
            ts._pick_discrete_gpu()
        self.assertNotIn("HIP_VISIBLE_DEVICES", os.environ)

    def test_sonda_fallita_avvisa_senza_eccezioni(self):
        for failure in (mock.Mock(stdout="traceback"), subprocess.TimeoutExpired("x", 300)):
            kwargs = {"side_effect": failure} if isinstance(failure, Exception) else {"return_value": failure}
            with mock.patch("subprocess.run", **kwargs), mock.patch("sys.stderr", io.StringIO()) as err:
                ts._pick_discrete_gpu()
            self.assertIn("scelta della GPU non riuscita", err.getvalue())
        self.assertNotIn("CUDA_VISIBLE_DEVICES", os.environ)


class EdgeTest(unittest.TestCase):
    def test_parentesi_con_virgola_iniziale_e_vuote(self):
        self.assertEqual("I Bai (Baizu) e Yu Cai.", ts.drop_empty_parens("I Bai (, Baizu) e Yu Cai ( )."))
        self.assertEqual("Testo (ok).", ts.drop_empty_parens("Testo (ok)."))
        self.assertEqual("", ts.drop_empty_parens(""))

    def test_numeri_con_orari_e_migliaia(self):
        self.assertTrue(ts._plausible("Apre alle 9:30 e costa 1,50.", "Opens at 9:30 and costs 1.50."))
        self.assertFalse(ts._plausible("Apre alle 9:30.", "Opens at 9."))

    def test_modelli_e_revisioni_coerenti(self):
        self.assertEqual(set(ts.MODELS.values()), set(ts.REVISIONS))
        for revision in ts.REVISIONS.values():
            self.assertRegex(revision, r"^[0-9a-f]{40}$")

    def test_abbreviazioni_non_dividono_la_frase(self):
        for text in ("Dr. Rossi arriva.", "Mt. Everest e' alto.", "Vedi No. 5 qui."):
            self.assertEqual(1, len(ts.SENTENCE_SPLIT.split(text)), text)
        self.assertEqual(2, len(ts.SENTENCE_SPLIT.split("Il treno parte. Ècco la stazione.")))


if __name__ == "__main__":
    unittest.main()
