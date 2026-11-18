#!/usr/bin/env python3
"""Test di untrusted_text.py: token di template e tag HTML tolti, testo legittimo con "<" intatto.

Uso: python test_untrusted_text.py
"""
import unittest

from untrusted_text import has_template_token, strip_markup


class StripMarkupTest(unittest.TestCase):
    def test_token_di_template_tolti(self):
        self.assertEqual("a b c d", strip_markup("a <|im_end|>b <|im_start|>c <|endoftext|>d"))
        self.assertEqual("ab", strip_markup("<start_of_turn>a</end_of_turn><think>b</think>"))

    def test_tag_html_tolti(self):
        self.assertEqual("x", strip_markup("<script>x</script>"))
        self.assertEqual("ciao", strip_markup('<b class="y">ciao</b>'))

    def test_testo_legittimo_resta(self):
        for text in ("a < b e c > d", "meno di <5 km", "freccia <- qui", "x <3 y", "1 < 2 > 0"):
            self.assertEqual(text, strip_markup(text))

    def test_has_template_token(self):
        self.assertTrue(has_template_token("ciao <|im_end|>"))
        self.assertTrue(has_template_token("<end_of_turn>"))
        self.assertFalse(has_template_token("a < b | c > d <b>"))


if __name__ == "__main__":
    unittest.main()
