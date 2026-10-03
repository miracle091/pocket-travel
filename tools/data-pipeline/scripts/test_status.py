#!/usr/bin/env python3
"""Test di status.py (durate, righe di fase e di avanzamento): l'orologio e' sostituito con unittest.mock.

Uso: python test_status.py
"""
import io
import unittest
from contextlib import redirect_stderr
from unittest import mock

import status


class DurationTest(unittest.TestCase):
    def test_formati(self):
        self.assertEqual("42s", status.duration(42.9))
        self.assertEqual("5m30s", status.duration(330))
        self.assertEqual("1m00s", status.duration(60))
        self.assertEqual("2h24m", status.duration(2 * 3600 + 24 * 60 + 5))
        self.assertEqual("1h00m", status.duration(3600))


class PhaseTest(unittest.TestCase):
    def test_con_e_senza_dettaglio_su_stderr(self):
        err = io.StringIO()
        with redirect_stderr(err):
            status.phase("caricamento modello")
            status.phase("eval", "361 righe")
        self.assertEqual("[fase] caricamento modello\n[fase] eval · 361 righe\n", err.getvalue())


class ProgressTest(unittest.TestCase):
    def _progress(self, clock, **kwargs):
        with mock.patch("status.time.monotonic", side_effect=clock):
            return status.Progress("training", total=100, **kwargs)

    def _update(self, p, clock, *args, **kwargs):
        err = io.StringIO()
        with mock.patch("status.time.monotonic", side_effect=clock), redirect_stderr(err):
            p.update(*args, **kwargs)
        return err.getvalue()

    def test_riga_con_velocita_e_tempo_residuo(self):
        p = self._progress([0.0])
        line = self._update(p, [50.0], 25)
        # 25 passi in 50 s: 2 s/passo, 75 passi mancanti = 150 s
        self.assertEqual("[training] 25/100 (25%) · 2,0 s/passo · mancano 2m30s · trascorsi 50s\n", line)

    def test_velocita_sopra_un_elemento_al_secondo(self):
        p = self._progress([0.0], unit="riga")
        line = self._update(p, [20.0], 50)
        self.assertIn("2,5 riga/s", line)

    def test_aggiornamenti_ravvicinati_saltati_ma_l_ultimo_sempre_stampato(self):
        p = self._progress([0.0], every=15.0)
        self.assertNotEqual("", self._update(p, [20.0], 10))
        self.assertEqual("", self._update(p, [25.0], 11))
        last = self._update(p, [26.0], 100)
        self.assertIn("100/100 (100%)", last)
        self.assertNotIn("mancano", last)

    def test_force_e_testo_extra(self):
        p = self._progress([0.0])
        self._update(p, [20.0], 10)
        line = self._update(p, [21.0], 11, "loss 0,394", force=True)
        self.assertTrue(line.endswith(" · loss 0,394\n"))

    def test_mark_start_riparte_la_velocita(self):
        p = self._progress([0.0])
        with mock.patch("status.time.monotonic", side_effect=[100.0]):
            p.mark_start(10)
        line = self._update(p, [120.0], 20)
        # 10 passi in 20 s dal punto di ripartenza
        self.assertIn("2,0 s/passo", line)
        self.assertIn("mancano 2m40s", line)

    def test_total_zero_non_divide_per_zero(self):
        with mock.patch("status.time.monotonic", side_effect=[0.0]):
            p = status.Progress("x", total=0)
        self.assertIn("0/0 (0%)", self._update(p, [1.0], 0))


if __name__ == "__main__":
    unittest.main()
