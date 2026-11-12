#!/usr/bin/env python3
"""Test di .github/scripts/publish-regions/world-map.sh con Pages (server HTTP locale), curl per Protomaps, gh,
go-pmtiles e sleep finti: mappa pubblicata recente (nessuna rigenerazione), piu' vecchia di WORLD_MAP_MAX_AGE_DAYS,
404 sul catalogo, errore di rete che non rigenera, GH_TOKEN tolto ai programmi dei dati ma non a gh. Richiede bash, jq.

Uso: python test_world_map.py
"""
import json
import shutil
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import shell_fixtures as fx

SCRIPT = ".github/scripts/publish-regions/world-map.sh"

# curl: la build Protomaps esiste, il resto passa al curl vero (cerca nel PATH senza la cartella dei finti).
CURL = """\
for a in "$@"; do
  case "$a" in *build.protomaps.com*) echo 200; exit 0 ;; esac
done
PATH="${PATH#*:}"
exec curl "$@"
"""
GH = 'echo "${GH_TOKEN-unset} $*" >> "$FAKE_BEHAVIOR/gh.log"\n'
INSTALLER = 'echo "${GH_TOKEN-unset}" > "$FAKE_BEHAVIOR/token-install"\n'
PMTILES = 'echo "${GH_TOKEN-unset}" > "$FAKE_BEHAVIOR/token-pmtiles"\n[ "$1" = extract ] && echo dati > "$3"\n'


def msys(path):
    """Percorso per il PATH di bash: su Windows "C:/x" diventa "/c/x" (i due punti spezzerebbero il PATH)."""
    posix = Path(path).as_posix()
    return f"/{posix[0].lower()}{posix[2:]}" if len(posix) > 1 and posix[1] == ":" else posix


def manifest_with_map(days_ago):
    version = (datetime.now(timezone.utc) - timedelta(days=days_ago)).strftime("%Y%m%d")
    return json.dumps({"worldMap": {"version": version}}).encode()


@unittest.skipUnless(fx.BASH and shutil.which("jq"), "servono bash e jq")
class WorldMapTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        for f in (SCRIPT, ".github/scripts/env.sh", ".github/scripts/retry.sh", "tools/data-pipeline/scripts/portable.sh",
                  "tools/data-pipeline/scripts/lib.sh"):
            fx.copy(f, self.tree)
        fx.write(self.tree / ".github/scripts/install-go-pmtiles.sh", "#!/usr/bin/env bash\n" + INSTALLER, executable=True)
        self.temp = self.tree / "runner-temp"
        fx.write(self.temp / "bin/pmtiles", "#!/usr/bin/env bash\n" + PMTILES, executable=True)
        self.behavior = self.tree / "behavior"
        self.behavior.mkdir()
        fx.shim(self.tree, "curl", CURL)
        fx.shim(self.tree, "gh", GH)
        fx.shim(self.tree, "sleep", "exit 0\n")

    def run_job(self, files=None, base=None, **env):
        """Esegue il job; restituisce (processo, output del job)."""
        out = self.tree / "github-output.txt"
        out.write_text("", encoding="utf-8")
        with fx.serve(files or {}) as server:
            environment = fx.environment(
                self.tree, GITHUB_ACTIONS="true", GITHUB_REPOSITORY="o/r", GITHUB_OUTPUT=out.as_posix(),
                RUNNER_TEMP=msys(self.temp), PAGES_BASE_URL=base or server.base, FAKE_BEHAVIOR=self.behavior.as_posix(),
                GH_TOKEN="segreto", **env)
            proc = fx.run_bash([SCRIPT], self.tree, environment)
        return proc, fx.parse_output(out)

    def assert_rigenerata(self, proc, out):
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("true", out["changed"])
        fragment = json.loads((self.temp / "fragments/world-map.json").read_text(encoding="utf-8"))
        self.assertRegex(fragment["worldMap"]["version"], r"^\d{8}$")
        self.assertEqual(5, fragment["worldMap"]["sizeBytes"])

    def test_mappa_recente_non_si_rigenera(self):
        proc, out = self.run_job({"/manifest.json": manifest_with_map(5)}, WORLD_MAP_MAX_AGE_DAYS=30)
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("false", out["changed"])
        self.assertIn("non la rigenero", proc.stdout)
        self.assertFalse((self.behavior / "token-pmtiles").exists())

    def test_mappa_vecchia_si_rigenera(self):
        proc, out = self.run_job({"/manifest.json": manifest_with_map(60)}, WORLD_MAP_MAX_AGE_DAYS=30)
        self.assert_rigenerata(proc, out)

    def test_il_limite_di_eta_e_incluso(self):
        # Esattamente WORLD_MAP_MAX_AGE_DAYS giorni: ancora valida.
        proc, out = self.run_job({"/manifest.json": manifest_with_map(10)}, WORLD_MAP_MAX_AGE_DAYS=10)
        self.assertEqual("false", out["changed"], proc.stdout)

    def test_catalogo_assente_404_si_rigenera(self):
        proc, out = self.run_job({})
        self.assert_rigenerata(proc, out)

    def test_catalogo_senza_mappa_si_rigenera(self):
        proc, out = self.run_job({"/manifest.json": b'{"regions":[]}'})
        self.assert_rigenerata(proc, out)

    def test_errore_di_rete_non_rigenera_e_lo_dice(self):
        proc, out = self.run_job(base="http://127.0.0.1:9")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("false", out["changed"])
        self.assertIn("::warning::manifest.json pubblicato non scaricabile (HTTP 000)", proc.stdout)
        self.assertFalse((self.behavior / "token-pmtiles").exists())

    def test_gh_vede_gh_token_e_i_programmi_dei_dati_no(self):
        proc, out = self.run_job({})
        self.assert_rigenerata(proc, out)
        self.assertEqual("unset", (self.behavior / "token-install").read_text(encoding="utf-8").strip())
        self.assertEqual("unset", (self.behavior / "token-pmtiles").read_text(encoding="utf-8").strip())
        gh = (self.behavior / "gh.log").read_text(encoding="utf-8").splitlines()
        self.assertTrue(any(line.startswith("segreto release upload world-map ") for line in gh), gh)


if __name__ == "__main__":
    unittest.main()
