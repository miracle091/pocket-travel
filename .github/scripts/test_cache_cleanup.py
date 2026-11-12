#!/usr/bin/env python3
"""Test delle regole di cache_cleanup.py (famiglie di chiavi e voci da cancellare), senza rete."""

import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cache_cleanup  # noqa: E402
from cache_cleanup import family_of, plan  # noqa: E402

NOW = datetime(2026, 10, 15, 20, 0, tzinfo=timezone.utc)
_next_id = iter(range(1, 1000))


def entry(key, accessed, created=None, ref="refs/heads/main", size=1):
    return {"id": next(_next_id), "key": key, "ref": ref, "sizeInBytes": size,
            "lastAccessedAt": accessed, "createdAt": created or accessed}


def keys(result):
    return [e["key"] for e, _ in result]


class FamilyTest(unittest.TestCase):
    def test_famiglie_delle_chiavi_vere(self):
        cases = {
            "gradle-transforms-v2-c6da0a48bbb44454e05e5cb5e13e8e36": ("gradle-transforms-v2", False),
            "gradle-home-v2|Linux-X64|build[5df42585ae8f8da228c604b8b4e8707c]-252070ed4c27b8614a69fc51e424ff665446e2af":
                ("gradle-home-v2|Linux-X64|build[5df42585ae8f8da228c604b8b4e8707c]", False),
            "ccache-llama-d140be9140a6eec618a85c8c13f81c1eec270169d7964eabbb07095e8640a51e": ("ccache-llama", False),
            "wikivoyage-dumps-ited0e67023521-end6ed669e2952": ("wikivoyage-dumps", False),
            "overture-check-2026-09-23.1": ("overture-check", False),
            "city-facts-0-38718619106-1": ("city-facts-0", True),
            "translations-cities-2-38718619106-2": ("translations-cities-2", True),
            "translations-guides-38718619106-1": ("translations-guides", True),
        }
        for key, want in cases.items():
            self.assertEqual(want, family_of(key), key)


class PlanTest(unittest.TestCase):
    def test_tiene_le_due_piu_recenti_di_ogni_famiglia(self):
        entries = [entry("gradle-build-cache-v2-%032x" % i, "2026-10-15T1%d:00:00Z" % i) for i in range(4)]
        self.assertEqual(keys(plan(entries, NOW)), ["gradle-build-cache-v2-%032x" % i for i in (0, 1)])

    def test_voci_di_una_run_dopo_tre_giorni_ma_non_la_piu_recente(self):
        entries = [
            entry("city-facts-0-38000000001-1", "2026-10-10T10:00:00Z"),
            entry("city-facts-0-38000000002-1", "2026-10-11T10:00:00Z"),
            entry("city-facts-1-38000000003-1", "2026-10-15T10:00:00Z"),
            entry("city-facts-1-38000000004-1", "2026-10-15T11:00:00Z"),
        ]
        # city-facts-0: la piu' recente resta anche se vecchia, l'altra ha piu' di 3 giorni; city-facts-1: recenti.
        self.assertEqual(keys(plan(entries, NOW)), ["city-facts-0-38000000001-1"])

    def test_voce_unica_vecchia_resta(self):
        entries = [entry("wikivoyage-dumps-it0123456789ab-en0123456789ab", "2026-09-01T00:00:00Z")]
        self.assertEqual(plan(entries, NOW), [])

    def test_ref_diversi_da_main(self):
        entries = [
            entry("ccache-llama-%064x" % 1, "2026-10-15T10:00:00Z", ref="refs/pull/12/merge"),
            entry("ccache-llama-%064x" % 2, "2026-10-15T09:00:00Z"),
        ]
        result = plan(entries, NOW)
        self.assertEqual(keys(result), ["ccache-llama-%064x" % 1])
        self.assertIn("refs/pull/12/merge", result[0][1])


class MainTest(unittest.TestCase):
    def test_cancellazione_fallita_identificata_per_id_con_la_stessa_chiave_su_due_ref(self):
        key = "ccache-llama-%064x" % 1
        entries = [
            entry(key, "2026-10-15T10:00:00Z", ref="refs/pull/12/merge", size=1048576),
            entry(key, "2026-10-15T10:00:00Z", ref="refs/pull/13/merge", size=1048576),
        ]
        failing = entries[0]["id"]

        def fake_gh(*args):
            if args[:2] == ("cache", "list"):
                return json.dumps(entries)
            if str(failing) == args[2]:
                raise subprocess.CalledProcessError(1, args, stderr="errore")
            return ""

        out = io.StringIO()
        env = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "o/r"}
        with mock.patch.dict(os.environ, env), mock.patch.object(sys, "argv", ["cache_cleanup.py"]), \
                mock.patch.object(cache_cleanup, "gh", fake_gh), contextlib.redirect_stdout(out):
            os.environ.pop("GITHUB_STEP_SUMMARY", None)
            cache_cleanup.main()
        text = out.getvalue()
        # Una sola voce risulta cancellata (quella di refs/pull/13), l'altra no.
        self.assertIn("Dopo: 1 voci", text)
        self.assertIn("| refs/pull/13/merge |", text)
        self.assertNotIn("| refs/pull/12/merge |", text)
        self.assertIn(f"`{key}` (refs/pull/12/merge)", text)

    def test_elenco_non_leggibile_scrive_l_errore_ed_esce_con_1(self):
        def fake_gh(*args):
            raise subprocess.CalledProcessError(1, args, stderr="HTTP 403: Resource not accessible\n")

        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp, "summary.md")
            out = io.StringIO()
            env = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "o/r", "GITHUB_STEP_SUMMARY": str(summary)}
            with mock.patch.dict(os.environ, env), mock.patch.object(sys, "argv", ["cache_cleanup.py"]), \
                    mock.patch.object(cache_cleanup, "gh", fake_gh), contextlib.redirect_stdout(out):
                self.assertEqual(cache_cleanup.main(), 1)
            self.assertIn("::error::elenco delle cache non leggibile", out.getvalue())
            self.assertIn("HTTP 403", out.getvalue())
            self.assertIn("HTTP 403", summary.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
