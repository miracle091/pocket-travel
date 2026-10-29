#!/usr/bin/env python3
"""Test della scrittura dei file di generate_sft.py: un dataset che esiste gia' si sovrascrive solo con --overwrite
(controllato prima di leggere fonti e dump), e i file nuovi sostituiscono i vecchi solo a scrittura finita
(replaced_when_done). Niente rete, niente dump: main() si ferma alla lettura delle fonti.

Uso: python test_sft_output.py
"""
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import generate_sft
import generate_sft_dataset as it


class ReplacedWhenDoneTest(unittest.TestCase):
    def test_sostituisce_il_file_a_scrittura_finita(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pocket_travel_sft.v11.it.jsonl"
            path.write_text("vecchio\n", encoding="utf-8")
            with generate_sft.replaced_when_done(path) as part:
                part.write_text("nuovo\n", encoding="utf-8")
                self.assertEqual("vecchio\n", path.read_text(encoding="utf-8"))  # fino alla fine resta il vecchio
            self.assertEqual("nuovo\n", path.read_text(encoding="utf-8"))
            self.assertEqual([path.name], [p.name for p in Path(tmp).iterdir()])

    def test_un_errore_a_meta_lascia_il_file_precedente(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pocket_travel_sft.v11.it.jsonl"
            path.write_text("vecchio\n", encoding="utf-8")
            with self.assertRaises(KeyboardInterrupt), generate_sft.replaced_when_done(path) as part:
                with open(part, "w", encoding="utf-8") as f:
                    f.write("a meta'")
                raise KeyboardInterrupt
            self.assertEqual("vecchio\n", path.read_text(encoding="utf-8"))
            self.assertEqual([path.name], [p.name for p in Path(tmp).iterdir()])

    def test_file_nuovo(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ATTRIBUTION.v11.it.tsv"
            with generate_sft.replaced_when_done(path) as part:
                part.write_text("regionId\n", encoding="utf-8")
            self.assertEqual("regionId\n", path.read_text(encoding="utf-8"))


class LoadSourcesReached(Exception):
    """main() ha passato i controlli sugli argomenti ed e' arrivato alla lettura delle fonti."""


class OverwriteTest(unittest.TestCase):
    def run_main(self, out, *extra):
        """main() con data/sft/ in out; stderr (i messaggi di argparse) resta in self.err."""
        argv = ["generate_sft.py", "--lang", "it", "--dump-dir", str(out), "--version", "v11", *extra]
        self.err = io.StringIO()
        with mock.patch.object(sys, "argv", argv), mock.patch.object(it, "OUT", out), \
                mock.patch.object(it, "load_sources", side_effect=LoadSourcesReached), \
                mock.patch.dict(generate_sft.LANGS), contextlib.redirect_stderr(self.err):
            generate_sft.main()

    def test_dataset_esistente_senza_overwrite_si_ferma_prima_di_cominciare(self):
        with tempfile.TemporaryDirectory() as tmp:
            dataset = Path(tmp) / "pocket_travel_sft.v11.it.jsonl"
            dataset.write_text("in uso\n", encoding="utf-8")
            with self.assertRaises(SystemExit) as stop:
                self.run_main(Path(tmp))
            self.assertEqual(2, stop.exception.code)
            self.assertIn("pocket_travel_sft.v11.it.jsonl esiste gia'", self.err.getvalue())
            self.assertIn("--overwrite", self.err.getvalue())
            self.assertEqual("in uso\n", dataset.read_text(encoding="utf-8"))

    def test_con_overwrite_o_senza_dataset_prosegue(self):
        for existing, extra in ((True, ("--overwrite",)), (False, ())):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as tmp:
                if existing:
                    (Path(tmp) / "pocket_travel_sft.v11.it.jsonl").write_text("in uso\n", encoding="utf-8")
                with self.assertRaises(LoadSourcesReached):
                    self.run_main(Path(tmp), *extra)


if __name__ == "__main__":
    unittest.main()
