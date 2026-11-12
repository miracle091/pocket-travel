#!/usr/bin/env python3
"""Test di .github/scripts/publish-regions/build-shard.sh con build-region.sh, build-cities.sh e curl finti: regioni
rimandate per il budget, fallite per timeout o per un frammento di catalogo mancante (le successive proseguono),
has_fragments, riepilogo con i motivi, GH_TOKEN tolto agli script della pipeline. Richiede bash; niente rete.

Uso: python test_build_shard.py
"""
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

SCRIPT = ".github/scripts/publish-regions/build-shard.sh"
REGIONS = ["aaa", "bbb", "ccc"]

# Argomenti di build-region.sh: id, nome, versione, 4 coordinate, URL della release, cartella di uscita ($9).
BUILD_REGION = """\
region="$1"
out="$9"
echo "${GH_TOKEN-unset}" > "$FAKE_BEHAVIOR/token-$region"
mode="$(cat "$FAKE_BEHAVIOR/$region" 2>/dev/null || echo ok)"
case "$mode" in
  exit:*) exit "${mode#exit:}" ;;
esac
mkdir -p "$out"
echo dati > "$out/poi.db.xz"
[ "$mode" = no-fragment ] || echo '{"regions":[{"regionId":"'"$region"'"}]}' > "$out/manifest-fragment.json"
"""

# curl: la build Protomaps esiste, il catalogo pubblicato no (404, prima pubblicazione).
CURL = """\
for a in "$@"; do
  case "$a" in *build.protomaps.com*) echo 200; exit 0 ;; esac
done
echo 404
exit 22
"""


@unittest.skipUnless(fx.BASH, "serve bash")
class BuildShardTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        for f in (SCRIPT, ".github/scripts/env.sh", "tools/data-pipeline/scripts/portable.sh", "tools/data-pipeline/scripts/lib.sh"):
            fx.copy(f, self.tree)
        scripts = self.tree / "tools/data-pipeline/scripts"
        fx.write(scripts / "regions.sh", "REGION_RELEASES=4\nALL_REGIONS=(\n" + "".join(f'  "{r}|{r}|0|0|1|1|{r}|xx|g|G|Europa"\n' for r in REGIONS)
                 + ")\nregion_release_tag() { echo region-data-r00; }\n")
        fx.write(scripts / "build-region.sh", "#!/usr/bin/env bash\n" + BUILD_REGION, executable=True)
        for name in ("build-cities.sh", "build-cities-dump.sh"):
            fx.write(scripts / name, "#!/usr/bin/env bash\nexit 0\n", executable=True)
        fx.shim(self.tree, "curl", CURL)
        self.behavior = self.tree / "behavior"
        self.behavior.mkdir()
        self.temp = self.tree / "runner-temp"
        self.temp.mkdir()
        self.fragments = self.temp / "fragments"

    def build(self, selected, behaviors=None, **env):
        """Esegue lo shard 0; restituisce (processo, output del job, riepilogo)."""
        for region, mode in (behaviors or {}).items():
            (self.behavior / region).write_text(mode, encoding="utf-8")
        out, summary = self.tree / "github-output.txt", self.tree / "summary.md"
        out.write_text("", encoding="utf-8")
        summary.write_text("", encoding="utf-8")
        environment = fx.environment(
            self.tree, GITHUB_REPOSITORY="o/r", GITHUB_RUN_NUMBER=1, GITHUB_RUN_ATTEMPT=1, GITHUB_OUTPUT=out.as_posix(),
            GITHUB_STEP_SUMMARY=summary.as_posix(), RUNNER_TEMP=self.temp.as_posix(), PAGES_BASE_URL="http://127.0.0.1:9",
            SHARD=0, SELECTED=selected, FAKE_BEHAVIOR=self.behavior.as_posix(), GH_TOKEN="segreto", **env)
        proc = fx.run_bash([SCRIPT], self.tree, environment)
        return proc, fx.parse_output(out), summary.read_text(encoding="utf-8")

    def lines(self, name):
        path = self.fragments / name
        return path.read_text(encoding="utf-8").splitlines() if path.exists() else []

    def test_tutte_costruite(self):
        proc, out, summary = self.build("aaa,bbb")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("true", out["has_fragments"])
        self.assertTrue((self.fragments / "aaa.json").exists() and (self.fragments / "bbb.json").exists())
        self.assertEqual([], self.lines("failed-0.txt"))
        self.assertEqual([], self.lines("deferred-0.txt"))
        self.assertIn("Nessun problema.", summary)

    def test_budget_esaurito_rimanda_le_regioni_restanti(self):
        # Con budget 0 parte comunque la prima regione, le altre vanno in deferred-0.txt.
        proc, out, summary = self.build("aaa,bbb,ccc", TIME_BUDGET_MINUTES=0)
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["bbb", "ccc"], self.lines("deferred-0.txt"))
        self.assertEqual("true", out["has_fragments"])
        self.assertTrue((self.fragments / "aaa.json").exists())
        self.assertFalse((self.fragments / "bbb.json").exists())
        self.assertIn("Regioni rimandate", summary)

    def test_timeout_del_builder_va_nelle_fallite(self):
        for code in (124, 137):
            with self.subTest(code=code):
                (self.fragments / "failed-0.txt").unlink(missing_ok=True)
                proc, out, summary = self.build("aaa,bbb", {"aaa": f"exit:{code}"})
                self.assertEqual(0, proc.returncode, proc.stderr)
                self.assertEqual(["aaa\toltre 45 min"], self.lines("failed-0.txt"))
                self.assertTrue((self.fragments / "bbb.json").exists())
                self.assertIn("- aaa: oltre 45 min", summary)

    def test_builder_fallito_ha_il_codice_come_motivo(self):
        proc, _, summary = self.build("aaa,bbb", {"aaa": "exit:3"})
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["aaa\tbuild-region.sh uscito con 3"], self.lines("failed-0.txt"))
        self.assertIn("- aaa: build-region.sh uscito con 3", summary)

    def test_builder_senza_frammento_e_fallito_e_le_successive_proseguono(self):
        proc, out, summary = self.build("aaa,bbb,ccc", {"aaa": "no-fragment"})
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["aaa\tframmento di catalogo non prodotto"], self.lines("failed-0.txt"))
        self.assertEqual([], self.lines("deferred-0.txt"))
        self.assertFalse((self.fragments / "aaa.json").exists())
        self.assertTrue((self.fragments / "bbb.json").exists() and (self.fragments / "ccc.json").exists())
        self.assertEqual("true", out["has_fragments"])
        self.assertIn("- aaa: frammento di catalogo non prodotto", summary)

    def test_nessuna_regione_riuscita_e_un_errore(self):
        proc, out, summary = self.build("aaa", {"aaa": "no-fragment"})
        self.assertEqual(1, proc.returncode)
        self.assertIn("::error::nessuna regione completata", proc.stdout)
        self.assertIn("Nessuna regione completata", summary)
        # Il file delle fallite serve alla continuazione.
        self.assertEqual("true", out["has_fragments"])

    def test_gli_script_della_pipeline_non_vedono_gh_token(self):
        proc, _, _ = self.build("aaa")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("unset", (self.behavior / "token-aaa").read_text(encoding="utf-8").strip())


if __name__ == "__main__":
    unittest.main()
