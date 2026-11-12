#!/usr/bin/env python3
"""Test di .github/scripts/publish-regions/continue-deferred.sh con `gh` finto che registra gli argomenti: regioni
rimandate e fallite per numero di continuazione, limite di 4 continuazioni, input facoltativi, launched=true solo dopo un
lancio riuscito, identificatori non validi. Richiede bash.

Uso: python test_continue_deferred.py
"""
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

SCRIPT = ".github/scripts/publish-regions/continue-deferred.sh"


@unittest.skipUnless(fx.BASH, "serve bash")
class ContinueDeferredTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        for f in (SCRIPT, ".github/scripts/env.sh", "tools/data-pipeline/scripts/portable.sh"):
            fx.copy(f, self.tree)
        self.fragments = self.tree / "fragments"
        self.fragments.mkdir()
        self.calls = self.tree / "gh-calls.txt"
        self.set_gh(0)

    def set_gh(self, status):
        # Un argomento per riga, "--" tra le chiamate.
        fx.shim(self.tree, "gh", f'printf "%s\\n" "$@" >> "{self.calls.as_posix()}"; echo -- >> "{self.calls.as_posix()}"; exit {status}\n')

    def list_file(self, name, *lines):
        fx.write(self.fragments / name, "".join(l + "\n" for l in lines))

    def run_script(self, **env):
        out = self.tree / "github-output.txt"
        out.write_text("", encoding="utf-8")
        summary = self.tree / "summary.md"
        summary.write_text("", encoding="utf-8")
        environment = fx.environment(self.tree, GITHUB_ACTIONS="true", GITHUB_REPOSITORY="o/r", RUNNER_TEMP=self.tree.as_posix(),
                                     GITHUB_OUTPUT=out.as_posix(), GITHUB_STEP_SUMMARY=summary.as_posix(), REF_NAME="main", **env)
        proc = fx.run_bash([SCRIPT], self.tree, environment)
        return proc, fx.parse_output(out), summary.read_text(encoding="utf-8")

    def dispatch(self):
        """Argomenti dell'unica chiamata a gh, o None se gh non e' stato chiamato."""
        if not self.calls.exists():
            return None
        return self.calls.read_text(encoding="utf-8").split("\n--\n")[0].split("\n")

    def test_run_iniziale_unisce_rimandate_e_fallite(self):
        self.list_file("deferred-0.txt", "aaa\t300", "bbb")
        self.list_file("failed-1.txt", "ccc", "aaa")
        proc, out, summary = self.run_script(CONTINUATION="0")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["workflow", "run", "publish-regions.yml", "--repo", "o/r", "--ref", "main",
                          "-f", "regions=aaa,bbb,ccc", "-f", "continuation=1"], self.dispatch())
        self.assertEqual("true", out["launched"])
        self.assertIn("aaa,bbb,ccc", summary)
        self.assertIn("continuazione 1 lanciata con 3 regioni", proc.stdout)

    def test_continuazione_solo_rimandate(self):
        self.list_file("deferred-0.txt", "aaa")
        self.list_file("failed-0.txt", "ccc")
        proc, out, _ = self.run_script(CONTINUATION="1")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("regions=aaa", self.dispatch())
        self.assertIn("continuation=2", self.dispatch())
        self.assertEqual("true", out["launched"])

    def test_continuazione_non_numerica_vale_zero(self):
        self.list_file("failed-0.txt", "ccc")
        proc, _, _ = self.run_script(CONTINUATION="abc")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("regions=ccc", self.dispatch())

    def test_oltre_il_massimo_non_rilancia(self):
        self.list_file("deferred-0.txt", "aaa")
        proc, out, _ = self.run_script(CONTINUATION="4")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("non rilancio", proc.stdout)
        self.assertIsNone(self.dispatch())
        self.assertEqual({}, out)

    def test_ultima_continuazione_consentita(self):
        self.list_file("deferred-0.txt", "aaa")
        proc, _, _ = self.run_script(CONTINUATION="3")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("continuation=4", self.dispatch())

    def test_senza_elenchi_o_con_elenchi_vuoti_non_lancia(self):
        proc, out, _ = self.run_script(CONTINUATION="0")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("nessuna regione rimandata o da riprovare", proc.stdout)
        self.list_file("deferred-0.txt")
        proc, out, _ = self.run_script(CONTINUATION="0")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("nessuna regione rimandata o da riprovare", proc.stdout)
        self.assertIsNone(self.dispatch())
        self.assertEqual({}, out)

    def test_input_vuoti_omessi_e_input_presenti_passati(self):
        self.list_file("deferred-0.txt", "aaa")
        self.run_script(CONTINUATION="1", TIME_BUDGET_INPUT="", POI_MAX_AGE_INPUT="", FORCE_CITIES_INPUT="false")
        args = self.dispatch()
        for name in ("time_budget_minutes", "poi_max_age_days", "force_cities"):
            self.assertFalse(any(name in a for a in args), args)
        self.calls.unlink()
        self.run_script(CONTINUATION="1", TIME_BUDGET_INPUT="90", POI_MAX_AGE_INPUT="30", FORCE_CITIES_INPUT="true")
        args = self.dispatch()
        for expected in ("time_budget_minutes=90", "poi_max_age_days=30", "force_cities=true"):
            self.assertIn(expected, args)

    def test_lancio_fallito_non_scrive_launched(self):
        self.list_file("deferred-0.txt", "aaa")
        self.set_gh(1)
        proc, out, summary = self.run_script(CONTINUATION="1")
        self.assertEqual(1, proc.returncode)
        self.assertNotIn("launched", out)
        self.assertEqual("", summary)

    def test_identificatori_non_validi_scartati(self):
        self.list_file("deferred-0.txt", "aaa", "BBB", "c c", "d;rm -rf /", "$(x)", "eee-1", "")
        proc, _, _ = self.run_script(CONTINUATION="1")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("regions=aaa,eee-1", self.dispatch())

    def test_solo_identificatori_non_validi_non_lancia(self):
        self.list_file("deferred-0.txt", "BBB", "x y")
        proc, _, _ = self.run_script(CONTINUATION="1")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIsNone(self.dispatch())


if __name__ == "__main__":
    unittest.main()
