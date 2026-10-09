#!/usr/bin/env python3
"""Test di translate_guides.py senza rete e senza modello: scelta delle sezioni da tradurre, tetto di tempo, sezioni
gia' in cache, titoli, lettura dei database e file delle traduzioni. Il motore CTranslate2 non viene mai caricato: il
Translator usa una cache gia' piena o un _run sostituito.

Uso: python test_translate_guides.py
"""
import json
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

import translate_guides as tg


class FakeTranslator(tg.ts.Translator):
    """Traduce ogni frase come "IT:<frase>" senza modello, contando le chiamate."""

    def __init__(self, directory, src="en", tgt="it"):
        super().__init__(src, tgt, directory)
        self.calls = []

    def _run(self, sentences):
        self.calls.append(list(sentences))
        for s in sentences:
            self.cache[self._key(s)] = f"IT:{s}"
        return [f"IT:{s}" for s in sentences]


def rows(owner, category, *bodies, url="https://en.wikivoyage.org/wiki/X"):
    return [(owner, category, f"{category} {i}", b, url) for i, b in enumerate(bodies)]


class PickSectionsTest(unittest.TestCase):
    def test_sceglie_assente_povera_e_molto_piu_corta(self):
        it = tg.group(rows("a", "SALUTE", "x" * 100) + rows("a", "CIBO_BEVANDE", "y" * 600) + rows("a", "ALLOGGIO", "z" * 900))
        en = tg.group(rows("a", "SALUTE", "e" * 200) + rows("a", "CIBO_BEVANDE", "e" * 1300) + rows("a", "ALLOGGIO", "e" * 1000)
                      + rows("a", "SICUREZZA", "e" * 50))
        picks = tg.pick_sections(it, en, {"a": "a"})
        self.assertEqual([("a", "CIBO_BEVANDE"), ("a", "SALUTE"), ("a", "SICUREZZA")], [(p[0], p[1]) for p in picks])

    def test_confronta_il_testo_dell_intera_categoria(self):
        it = tg.group(rows("a", "SALUTE", "x" * 400, "x" * 400))
        en = tg.group(rows("a", "SALUTE", "e" * 700, "e" * 700))
        self.assertEqual([], tg.pick_sections(it, en, {"a": "a"}))

    def test_fatti_rapidi_mai_tradotti(self):
        en = tg.group(rows("a", "FATTI_RAPIDI", "e" * 500))
        self.assertEqual([], tg.pick_sections({}, en, {"a": "a"}))

    def test_solo_proprietari_italiani_con_omologo(self):
        en = tg.group(rows("Venice", "COSA_VEDERE", "e" * 500) + rows("Londra", "COSA_VEDERE", "e" * 500))
        picks = tg.pick_sections({}, en, {"Venezia": "Venice", "Roma": "Rome"})
        self.assertEqual([("Venezia", "COSA_VEDERE")], [(p[0], p[1]) for p in picks])

    def test_sezione_italiana_che_viene_dalla_pagina_inglese_vale_assente(self):
        it = tg.group(rows("siberia", "SALUTE", "e" * 900, url="https://en.wikivoyage.org/wiki/Siberia"), drop_url_prefix="https://en.")
        en = tg.group(rows("siberia", "SALUTE", "e" * 900))
        self.assertEqual(1, len(tg.pick_sections(it, en, {"siberia": "siberia"})))


class TravelAdviceTest(unittest.TestCase):
    GC = "https://travel.gc.ca/destinations/it"

    def test_solo_le_sezioni_di_travel_gc_ca_anche_se_la_guida_italiana_e_ricca(self):
        en = tg.group(rows("a", "SICUREZZA", "Stay safe.") + rows("a", "SICUREZZA", "Crime occurs.", url=self.GC)
                      + rows("a", "SALUTE", "Drink water.", url=self.GC) + rows("b", "SALUTE", "Ok.", url=self.GC))
        picks = tg.pick_travel_advice(en, {"a": "a"})
        self.assertEqual([("a", "SALUTE", ["Drink water."]), ("a", "SICUREZZA", ["Crime occurs."])],
                         [(p[0], p[1], [r[1] for r in p[2]]) for p in picks])

    def test_titoli_fissi(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            picks = [("a", "SALUTE", [("Health (Government of Canada)", "Drink water.", self.GC)])]
            result, _ = tg.translate_picks(picks, t, time.time() + 60)
        self.assertEqual([("Salute (Governo del Canada)", "IT:Drink water.", self.GC)], result[("a", "SALUTE")])

    def test_le_correzioni_prevalgono_sulla_cache(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            t.cache[t._key("Petty crime occurs.")] = "Il crimine di Petty si verifica."
            path = Path(d) / "correzioni.tsv"
            path.write_text("# inglese<TAB>italiano\nPetty crime occurs.\tSi verificano piccoli reati.\n", encoding="utf-8")
            tg.load_corrections(t, path)
            self.assertEqual(["Si verificano piccoli reati."], t.translate_many(["Petty crime occurs."]))
            self.assertEqual([], t.calls)

    def test_correzioni_con_bom_e_righe_vuote_e_errore_chiaro_se_manca_il_tab(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            path = Path(d) / "correzioni.tsv"
            path.write_text("﻿# commento\n\n  \nCrime\tCriminalità\n", encoding="utf-8")
            tg.load_corrections(t, path)
            self.assertEqual(["Criminalità"], t.translate_many(["Crime"]))
            path.write_text("Crime Criminalità\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "riga 1"):
                tg.load_corrections(t, path)

    def test_il_file_delle_correzioni_del_repository_e_valido(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            tg.load_corrections(t, tg.CORRECTIONS)
        self.assertGreater(len(t.trusted), 200)
        for line in tg.CORRECTIONS.read_text(encoding="utf-8").splitlines():
            if line and not line.startswith("#"):
                en, it = line.split("\t")
                # una sola frase per riga, altrimenti la chiave non corrisponde mai a una frase del testo
                self.assertEqual([en], tg.ts.SENTENCE_SPLIT.split(en))
                self.assertEqual((en.strip(), it.strip()), (en, it))

    def test_correzione_con_numeri_scritti_in_altro_modo_non_e_scartata(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            path = Path(d) / "correzioni.tsv"
            path.write_text("Shops open 8AM-10PM.\tI negozi aprono dalle 8 alle 22.\n", encoding="utf-8")
            tg.load_corrections(t, path)
            self.assertEqual(["I negozi aprono dalle 8 alle 22."], t.translate_many(["Shops open 8AM-10PM."]))


class TranslatePicksTest(unittest.TestCase):
    def _picks(self, *specs):
        return [(owner, "SALUTE", [("Stay safe", body, "https://en.wikivoyage.org/wiki/X")]) for owner, body in specs]

    def test_traduce_titoli_e_corpi_con_marcatori(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            result, pending = tg.translate_picks(self._picks(("a", "Water is safe.\n▸ Tips\n• Boil it.")), t, time.time() + 60)
        self.assertEqual([], pending)
        self.assertEqual({("a", "SALUTE"): [("IT:Stay safe", "IT:Water is safe.\n▸ IT:Tips\n• IT:Boil it.",
                                             "https://en.wikivoyage.org/wiki/X")]}, result)

    def test_tempo_finito_rimanda_le_nuove_ma_include_quelle_in_cache(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            for sentence in ("Cached text.", "Stay safe"):
                t.cache[t._key(sentence)] = f"IT:{sentence}"
            result, pending = tg.translate_picks(self._picks(("a", "Cached text."), ("b", "Brand new text.")), t, time.time() - 1)
        self.assertEqual([("a", "SALUTE")], list(result))
        self.assertEqual(["b"], [p[0] for p in pending])
        self.assertEqual([], t.calls)

    def test_tempo_finito_tra_un_lotto_e_l_altro(self):
        clock = [0]

        class SlowTranslator(FakeTranslator):
            def _run(self, sentences):
                clock[0] += 100  # ogni chiamata al modello "dura" 100 secondi
                return super()._run(sentences)

        with tempfile.TemporaryDirectory() as d, mock.patch.object(tg.time, "time", lambda: clock[0]),                 mock.patch.object(tg, "CHUNK_CHARS", 1):
            result, pending = tg.translate_picks(self._picks(("a", "Alpha."), ("b", "Beta.")), SlowTranslator(d), 50)
        self.assertEqual([("a", "SALUTE")], list(result))
        self.assertEqual(["b"], [p[0] for p in pending])

    def test_priorita_i_piu_pesanti_per_primi(self):
        with tempfile.TemporaryDirectory() as d, mock.patch.object(tg, "CHUNK_CHARS", 1):
            t = FakeTranslator(d)
            tg.translate_picks(self._picks(("piccola", "Small."), ("grande", "Big.")), t, time.time() + 60, {"grande": 1000000})
        self.assertIn("Big.", t.calls[0])
        self.assertNotIn("Small.", t.calls[0])

    def test_frase_sospetta_scarta_l_intera_categoria(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            t.cache[t._key("Open at 9.")] = "Aperto alle 10."
            t.cache[t._key("Stay safe")] = "Sicurezza"
            picks = [("a", "SALUTE", [("Stay safe", "Open at 9.", "u"), ("Stay safe", "Fine.", "u")])]
            result, pending = tg.translate_picks(picks, t, time.time() + 60)
        self.assertEqual({}, result)

    def test_titoli_uguali_dopo_la_traduzione_tengono_l_originale(self):
        with tempfile.TemporaryDirectory() as d:
            t = FakeTranslator(d)
            t.cache[t._key("See")] = "Vedere"
            t.cache[t._key("Look")] = "Vedere"
            picks = [("a", "COSA_VEDERE", [("See", "Text one.", "u"), ("Look", "Text two.", "u")])]
            t.cache[t._key("Text one.")] = "Testo uno."
            t.cache[t._key("Text two.")] = "Testo due."
            result, _ = tg.translate_picks(picks, t, time.time() + 60)
        self.assertEqual(["Vedere", "Look"], [r[0] for r in result[("a", "COSA_VEDERE")]])


class DatabaseTest(unittest.TestCase):
    def _db(self, directory, name, table, owner_column, data):
        path = Path(directory) / name
        con = sqlite3.connect(path)
        con.execute(f"CREATE TABLE {table} ({owner_column} TEXT, category TEXT, title TEXT, body TEXT, sourceUrl TEXT, population INTEGER)")
        con.executemany(f"INSERT INTO {table} VALUES (?, ?, ?, ?, ?, ?)", data)
        con.commit()
        con.close()
        return path

    def test_read_rows_tabella_assente(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "vuoto.db"
            sqlite3.connect(path).close()
            self.assertEqual([], tg.read_rows(path, "guide_sections", "regionId"))

    def test_main_guide_traduce_solo_i_consigli_del_governo_canadese(self):
        gc = "https://travel.gc.ca/destinations/it"
        with tempfile.TemporaryDirectory() as d:
            it = self._db(d, "it.db", "guide_sections", "regionId", [("a", "SALUTE", "Salute", "Poco.", "https://it.wikivoyage.org/wiki/A", None)])
            en = self._db(d, "en.db", "guide_sections", "regionId", [("a", "SALUTE", "Stay healthy", "Drink water.", "https://en.wikivoyage.org/wiki/A", None),
                                                                      ("a", "SALUTE", "Health (Government of Canada)", "Malaria occurs.", gc, None),
                                                                      ("a", "FATTI_RAPIDI", "Quick facts", "Language: x", "https://en.wikivoyage.org/wiki/A", None)])
            overlay = Path(d) / "tradotte.jsonl"
            with mock.patch.object(tg, "Ct2Translator", lambda src, tgt, cache_dir, model_dir: FakeTranslator(cache_dir, src, tgt)):
                tg.main(["guides", str(it), str(en), str(overlay), "--cache-dir", d, "--model-dir", d])
            lines = [json.loads(line) for line in overlay.read_text(encoding="utf-8").splitlines()]
        self.assertEqual([{"owner": "a", "category": "SALUTE",
                           "sections": [{"title": "Salute (Governo del Canada)", "body": "IT:Malaria occurs.", "sourceUrl": gc}]}], lines)

    def test_main_guide_inglesi_dall_italiano_spente_senza_caricare_il_modello(self):
        with tempfile.TemporaryDirectory() as d:
            en = self._db(d, "en.db", "guide_sections", "regionId", [("a", "SALUTE", "Health", "Little.", "https://en.wikivoyage.org/wiki/A", None)])
            it = self._db(d, "it.db", "guide_sections", "regionId", [("a", "SALUTE", "Salute", "Bere acqua.", "https://it.wikivoyage.org/wiki/A", None)])
            overlay = Path(d) / "tradotte.jsonl"
            with mock.patch.object(tg, "Ct2Translator", side_effect=AssertionError("modello caricato")):
                tg.main(["guides", str(en), str(it), str(overlay), "--cache-dir", d, "--model-dir", d, "--from", "it"])
            self.assertEqual("", overlay.read_text(encoding="utf-8"))

    def test_other_titles_usa_cache_e_ripiega_sul_titolo_uguale(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "city-titles.it-en.jsonl").write_text(json.dumps({"own": "Venezia", "other": "Venice"}) + "\n", encoding="utf-8")
            with mock.patch.object(tg.city_population, "wikibase_items", side_effect=OSError("rete")):
                mapping = tg.other_titles({"Venezia", "Rimini", "Roma"}, {"Venice", "Rimini"}, "it", "en", d)
        self.assertEqual({"Venezia": "Venice", "Rimini": "Rimini"}, mapping)


if __name__ == "__main__":
    unittest.main()
