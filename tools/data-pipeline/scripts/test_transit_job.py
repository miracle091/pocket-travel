#!/usr/bin/env python3
"""Test di .github/scripts/publish-regions/transit.sh (il job; build-transit.sh, la conversione di una rete, ha
test_build_transit.py) con build-transit.sh, gh e sleep finti e Pages come server HTTP locale: reti costruite, scadute
(uscita 3) e fallite (altre uscite), warning solo al cambio di stato, voci pubblicate valide tenute e scadute tra le
"Non incluse", nessuna rete da pubblicare, indice pubblicato non scaricabile, GH_TOKEN tolto a build-transit.sh ma non a
gh. Richiede bash, jq.

Uso: python test_transit_job.py
"""
import json
import shutil
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

SCRIPT = ".github/scripts/publish-regions/transit.sh"
FUTURE, PAST = "2999-01-01", "2020-01-01"

# build-transit.sh: --download-only <id> <cartella> non fa nulla; altrimenti <id> <versione> <URL> <uscita> <indice>.
# Comportamento della rete in $FAKE_BEHAVIOR/<id>: ok (default), expired (uscita 3) o un altro codice di uscita.
BUILD_TRANSIT = """\
if [ "$1" = --download-only ]; then echo "${GH_TOKEN-unset}" > "$FAKE_BEHAVIOR/token-download"; exit 0; fi
id="$1"; out="$4"
echo "${GH_TOKEN-unset}" > "$FAKE_BEHAVIOR/token-build"
echo "$id|$5" >> "$FAKE_BEHAVIOR/builds.log"
mode="$(cat "$FAKE_BEHAVIOR/$id" 2>/dev/null || echo ok)"
case "$mode" in
  expired)
    echo '{"id":"'"$id"'","regions":["it"],"name":"Rete '"$id"'","reason":"orari","feedEnd":"2020-01-01"}' >> "$out/transit-expired.jsonl"
    exit 3 ;;
  ok)
    echo '{"id":"'"$id"'","name":"Rete '"$id"'","validUntil":"2999-01-01","version":"'"$2"'"}' >> "$out/transit-entries.jsonl"
    echo dati > "$out/$id--$2--transit.db" ;;
  *) exit "$mode" ;;
esac
"""
GH = 'echo "${GH_TOKEN-unset} $*" >> "$FAKE_BEHAVIOR/gh.log"\n'


def feeds_tsv(ids):
    return "# id\tregioni\tnome\tlicenza\tattribuzione\turl_licenza\turl_feed\n" + "".join(
        f"{i}\tit\tRete {i}\tCC0\tAttr\thttps://licenza\thttps://127.0.0.1:9/{i}.zip\n" for i in ids)


def published(feeds=(), missing=()):
    """Indice transit.json pubblicato: feeds = [(id, validUntil)], missing = [(id, motivo)]."""
    return json.dumps({
        "version": "v0", "publishedAt": 1,
        "feeds": [{"id": i, "name": f"Rete {i}", "regions": ["it"], "validUntil": until, "version": "v0"} for i, until in feeds],
        "missing": [{"id": i, "regions": ["it"], "name": f"Rete {i}", "reason": reason} for i, reason in missing],
    }).encode()


@unittest.skipUnless(fx.BASH and shutil.which("jq"), "servono bash e jq")
class TransitJobTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        scripts = "tools/data-pipeline/scripts/"
        for f in (SCRIPT, ".github/scripts/env.sh", ".github/scripts/retry.sh", scripts + "portable.sh", scripts + "lib.sh"):
            fx.copy(f, self.tree)
        fx.write(self.tree / scripts / "build-transit.sh", "#!/usr/bin/env bash\n" + BUILD_TRANSIT, executable=True)
        fx.write(self.tree / "tools/data-pipeline/transit-missing.tsv", "# id\tregioni\tnome\tmotivo\n")
        fx.shim(self.tree, "gh", GH)
        fx.shim(self.tree, "sleep", "exit 0\n")
        self.temp = self.tree / "runner-temp"
        self.temp.mkdir()
        self.behavior = self.tree / "behavior"
        self.behavior.mkdir()
        self.summary = self.tree / "summary.md"

    def run_job(self, ids, behaviors=None, files=None, base=None):
        """Esegue il job con le reti [ids]; restituisce (processo, transit.json prodotto o None)."""
        fx.write(self.tree / "tools/data-pipeline/transit-feeds.tsv", feeds_tsv(ids))
        for net, mode in (behaviors or {}).items():
            (self.behavior / net).write_text(mode, encoding="utf-8")
        self.summary.write_text("", encoding="utf-8")
        with fx.serve(files or {}) as server:
            environment = fx.environment(
                self.tree, GITHUB_ACTIONS="true", GITHUB_REPOSITORY="o/r", GITHUB_RUN_NUMBER=7, GITHUB_RUN_ATTEMPT=1,
                GITHUB_STEP_SUMMARY=self.summary.as_posix(), RUNNER_TEMP=self.temp.as_posix(), PAGES_BASE_URL=base or server.base,
                FAKE_BEHAVIOR=self.behavior.as_posix(), GH_TOKEN="segreto")
            proc = fx.run_bash([SCRIPT], self.tree, environment)
        index = self.temp / "transit-index/transit.json"
        return proc, json.loads(index.read_text(encoding="utf-8")) if index.exists() else None

    def warnings(self, proc):
        return [line for line in proc.stdout.splitlines() if line.startswith("::warning::")]

    def test_reti_costruite_scadute_e_fallite_senza_indice_pubblicato(self):
        proc, index = self.run_job(["net-ok", "net-exp", "net-bad"], {"net-exp": "expired", "net-bad": "1"})
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["net-ok"], [f["id"] for f in index["feeds"]])
        self.assertEqual([("net-exp", "orari")], [(m["id"], m["reason"]) for m in index["missing"]])
        self.assertNotIn("feedEnd", index["missing"][0])
        warnings = self.warnings(proc)
        self.assertEqual(2, len(warnings), warnings)
        self.assertTrue(any("rete net-exp: orari scaduti (fino al 2020-01-01)" in w for w in warnings), warnings)
        self.assertTrue(any("rete net-bad non costruita" in w for w in warnings), warnings)
        summary = self.summary.read_text(encoding="utf-8")
        self.assertIn("- net-ok Rete net-ok: valido fino al 2999-01-01", summary)
        self.assertIn("Non costruite (resta la voce pubblicata): net-bad", summary)
        self.assertIn("Orari scaduti (tra le Non incluse): net-exp", summary)
        self.assertTrue((self.temp / "fragments/transit.json").exists())

    def test_rete_gia_scaduta_nell_indice_pubblicato_resta_muta(self):
        files = {"/transit.json": published(missing=[("net-exp", "orari")])}
        proc, index = self.run_job(["net-ok", "net-exp"], {"net-exp": "expired"}, files)
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual([], self.warnings(proc))
        self.assertEqual(["net-exp"], [m["id"] for m in index["missing"]])
        self.assertIn("Orari scaduti (tra le Non incluse): net-exp", self.summary.read_text(encoding="utf-8"))

    def test_rete_di_nuovo_valida_avvisa_una_volta(self):
        files = {"/transit.json": published(missing=[("net-back", "orari")])}
        proc, index = self.run_job(["net-back"], files=files)
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["::warning::rete net-back: orari di nuovo validi (fino al 2999-01-01), di nuovo pubblicata"],
                         self.warnings(proc))
        self.assertEqual([], index["missing"])
        self.assertIn("Di nuovo pubblicate (orari di nuovo validi): net-back", self.summary.read_text(encoding="utf-8"))

    def test_rete_fallita_tiene_la_voce_valida_e_elenca_la_scaduta_tra_le_non_incluse(self):
        files = {"/transit.json": published(feeds=[("keep", FUTURE), ("old", PAST), ("ok", FUTURE)])}
        proc, index = self.run_job(["ok", "keep", "old"], {"keep": "1", "old": "1"}, files)
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["keep", "ok"], sorted(f["id"] for f in index["feeds"]))
        self.assertEqual([("old", "orari")], [(m["id"], m["reason"]) for m in index["missing"]])
        # L'indice pubblicato arriva a build-transit.sh come file locale.
        builds = (self.behavior / "builds.log").read_text(encoding="utf-8").splitlines()
        self.assertTrue(all(line.split("|")[1].endswith("published-transit.json") for line in builds), builds)

    def test_nessuna_rete_da_pubblicare_e_un_errore(self):
        proc, index = self.run_job(["net-bad"], {"net-bad": "1"})
        self.assertEqual(1, proc.returncode)
        self.assertIn("Nessuna rete da pubblicare", proc.stderr)
        self.assertIsNone(index)

    def test_indice_pubblicato_non_scaricabile_e_un_errore(self):
        proc, index = self.run_job(["net-ok"], base="http://127.0.0.1:9")
        self.assertEqual(1, proc.returncode)
        self.assertIn("ERRORE: transit.json non scaricabile", proc.stderr)
        self.assertIsNone(index)

    def test_gh_vede_gh_token_e_build_transit_no(self):
        proc, _ = self.run_job(["net-ok"])
        self.assertEqual(0, proc.returncode, proc.stderr)
        for name in ("token-download", "token-build"):
            self.assertEqual("unset", (self.behavior / name).read_text(encoding="utf-8").strip(), name)
        gh = (self.behavior / "gh.log").read_text(encoding="utf-8").splitlines()
        self.assertTrue(any(line.startswith("segreto release upload transit-feeds ") for line in gh), gh)


if __name__ == "__main__":
    unittest.main()
