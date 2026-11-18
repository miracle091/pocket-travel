#!/usr/bin/env python3
"""Test di vision_eval.py senza rete: confronto con le parole attese, corpo della richiesta, riepilogo e set di foto.

Uso: python test_vision_eval.py
"""
import base64
import unittest

import vision_eval

FREE_LICENSES = ("CC0", "Public domain", "CC BY ", "CC BY-SA ")


class CorrectTest(unittest.TestCase):
    def test_una_variante_di_ogni_gruppo_senza_accenti_ne_maiuscole(self):
        expected = [["lisipp", "lysipp"], ["apoxyomenos", "apossiomeno"]]
        self.assertTrue(vision_eval.is_correct("È l'Apossiomeno, copia da un bronzo di LISIPPO.", expected))
        self.assertFalse(vision_eval.is_correct("È l'Apossiomeno.", expected))
        self.assertFalse(vision_eval.is_correct("", expected))


class RequestTest(unittest.TestCase):
    def test_domanda_istruzione_della_lingua_e_immagine_in_base64(self):
        body = vision_eval.request_body("m", "Che monumento è?", "it", b"\xff\xd8jpeg")
        text, image = body["messages"][0]["content"]
        self.assertEqual("m", body["model"])
        self.assertEqual(0, body["temperature"])
        self.assertIn("Che monumento è?", text["text"])
        self.assertIn("in italiano", text["text"])
        self.assertEqual("data:image/jpeg;base64," + base64.b64encode(b"\xff\xd8jpeg").decode(), image["image_url"]["url"])


class CacheNameTest(unittest.TestCase):
    def test_nome_valido_su_windows_anche_con_virgolette_e_due_punti(self):
        name = vision_eval.cache_name('File:"Ein Meisterwerk der Römer". 03.jpg')
        self.assertRegex(name, r"^[0-9a-f]{24}\.jpg$")
        self.assertNotEqual(name, vision_eval.cache_name("File:altro.jpg"))


class SummaryTest(unittest.TestCase):
    def test_per_gruppo_e_lingua_e_il_totale(self):
        rows = [{"group": "menu", "lang": "it", "correct": True}, {"group": "menu", "lang": "it", "correct": False},
                {"group": "luoghi", "lang": "en", "correct": True}]
        self.assertEqual({("menu", "it"): (1, 2), ("tutte", "it"): (1, 2), ("luoghi", "en"): (1, 1), ("tutte", "en"): (1, 1)},
                         vision_eval.summary(rows))


class PhotoSetTest(unittest.TestCase):
    def test_ogni_foto_ha_licenza_libera_domande_nelle_due_lingue_e_parole_attese(self):
        photos = vision_eval.load_set()
        self.assertGreaterEqual(len(photos), 20)
        self.assertEqual(len(photos), len({p["id"] for p in photos}))
        for photo in photos:
            with self.subTest(photo["id"]):
                self.assertTrue(photo["file"].startswith("File:"))
                self.assertTrue(photo["license"].startswith(FREE_LICENSES) or photo["license"] in ("CC0", "Public domain"), photo["license"])
                self.assertTrue(photo["question"]["it"] and photo["question"]["en"])
                self.assertTrue(photo["expected"] and all(photo["expected"]))


if __name__ == "__main__":
    unittest.main()
