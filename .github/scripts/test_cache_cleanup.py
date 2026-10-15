#!/usr/bin/env python3
"""Test delle regole di cache_cleanup.py (famiglie di chiavi e voci da cancellare), senza rete."""

import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
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


if __name__ == "__main__":
    unittest.main()
