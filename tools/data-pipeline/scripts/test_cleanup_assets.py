#!/usr/bin/env python3
"""Test di .github/scripts/publish-regions/cleanup-assets.sh con `gh` finto: quali asset si cancellano (non referenziati
dal catalogo nuovo e dal precedente, piu' vecchi di 24 ore), prima pubblicazione, catalogo precedente mancante, catalogo
senza asset, release legacy vuote, release fuori dall'elenco. Richiede bash.

Uso: python test_cleanup_assets.py
"""
import re
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import shell_fixtures as fx

SCRIPT = ".github/scripts/publish-regions/cleanup-assets.sh"
BASE = "https://github.com/o/r/releases/download"
OLD = "2020-01-01T00:00:00Z"
RECENT = (datetime.now(timezone.utc) - timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")

# gh finto: legge release e asset da $FAKE, registra le cancellazioni; l'id di una release e' il suo tag.
GH = r'''echo "$*" >> "$FAKE/calls.log"
case "$1 $2" in
  "release list") cat "$FAKE/tags.txt" ;;
  "release delete") echo "$3" >> "$FAKE/releases-deleted.txt" ;;
  "api --paginate") id="${3#*/releases/}"; cat "$FAKE/assets-${id%%/*}.tsv" ;;
  "api -X") echo "${4##*/}" >> "$FAKE/assets-deleted.txt" ;;
  api\ repos/*/releases/tags/*) echo "${2##*/}" ;;
  *) echo "gh inatteso: $*" >&2; exit 2 ;;
esac
'''


def url(tag, name):
    return f'"{BASE}/{tag}/{name}"'


@unittest.skipUnless(fx.BASH, "serve bash")
class CleanupAssetsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        for f in (SCRIPT, ".github/scripts/env.sh", ".github/scripts/retry.sh", "tools/data-pipeline/scripts/portable.sh"):
            fx.copy(f, self.tree)
        self.fake = self.tree / "fake"
        self.fake.mkdir()
        fx.shim(self.tree, "gh", GH)
        fx.shim(self.tree, "sleep", "exit 0\n")
        self.site, self.previous = self.tree / "site", self.tree / "previous"
        self.site.mkdir()
        self.previous.mkdir()
        self.script = SCRIPT

    def releases(self, **assets):
        """Release finte {tag: [(nome, data, id)]}; i tag usano "_" al posto di "-"."""
        tags = [tag.replace("_", "-") for tag in assets]
        fx.write(self.fake / "tags.txt", "".join(t + "\n" for t in tags))
        for tag, items in assets.items():
            fx.write(self.fake / f"assets-{tag.replace('_', '-')}.tsv", "".join(f"{n}\t{d}\t{i}\n" for n, d, i in items))

    def catalogue(self, directory, name, *urls):
        fx.write(directory / name, '{"urls":[' + ",".join(urls) + "]}")

    def run_script(self):
        env = fx.environment(self.tree, GITHUB_ACTIONS="true", GITHUB_REPOSITORY="o/r", RUNNER_TEMP=self.tree.as_posix(), FAKE=self.fake.as_posix())
        return fx.run_bash([self.script, self.site.as_posix(), self.previous.as_posix()], self.tree, env)

    def lines(self, name):
        path = self.fake / name
        return path.read_text(encoding="utf-8").split() if path.exists() else []

    def test_cancella_solo_il_vecchio_non_referenziato(self):
        self.catalogue(self.site, "manifest.json", url("region-data-r01", "a--1--poi.db"))
        self.catalogue(self.previous, "previous-manifest.json", url("region-data-r01", "d--1--poi.db"))
        self.releases(region_data_r01=[("a--1--poi.db", OLD, 1), ("b--0--poi.db", OLD, 2), ("c--2--poi.db", RECENT, 3), ("d--1--poi.db", OLD, 4)])
        proc = self.run_script()
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["2"], self.lines("assets-deleted.txt"))
        self.assertIn("1 asset non referenziati rimossi (0 non rimovibili), 3 restano", proc.stdout)
        self.assertEqual([], self.lines("releases-deleted.txt"))

    def test_conta_anche_i_cataloghi_dei_civici_e_dei_mezzi(self):
        self.catalogue(self.site, "manifest.json", url("world-map", "w--1.pmtiles"))
        self.catalogue(self.site, "address-grid.json", url("address-cells-01", "cell--1.db"))
        self.catalogue(self.previous, "previous-manifest.json", url("world-map", "w--1.pmtiles"))
        self.catalogue(self.previous, "previous-transit.json", url("transit-feeds", "t--1.zip"))
        self.releases(world_map=[("w--1.pmtiles", OLD, 1)], address_cells_01=[("cell--1.db", OLD, 2), ("cell--0.db", OLD, 3)],
                      transit_feeds=[("t--1.zip", OLD, 4), ("t--0.zip", OLD, 5)])
        proc = self.run_script()
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertCountEqual(["3", "5"], self.lines("assets-deleted.txt"))

    def test_prima_pubblicazione_non_cancella_nulla(self):
        self.catalogue(self.site, "manifest.json", url("region-data-r01", "a--1--poi.db"))
        fx.write(self.previous / "first-publication", "")
        self.releases(region_data_r01=[("b--0--poi.db", OLD, 2)])
        proc = self.run_script()
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("non cancello nulla in questa run", proc.stdout)
        self.assertEqual([], self.lines("assets-deleted.txt"))

    def test_catalogo_precedente_mancante_ferma_il_passo(self):
        self.catalogue(self.site, "manifest.json", url("region-data-r01", "a--1--poi.db"))
        self.releases(region_data_r01=[("b--0--poi.db", OLD, 2)])
        proc = self.run_script()
        self.assertEqual(1, proc.returncode)
        self.assertIn("manifest.json precedente mancante senza che sia una prima pubblicazione", proc.stderr)
        self.assertEqual([], self.lines("assets-deleted.txt"))

    def test_catalogo_senza_asset_non_cancella_nulla(self):
        self.catalogue(self.site, "manifest.json", '"https://example.com/x"')
        self.catalogue(self.previous, "previous-manifest.json")
        self.releases(region_data_r01=[("b--0--poi.db", OLD, 2)])
        proc = self.run_script()
        self.assertEqual(0, proc.returncode)
        self.assertIn("::warning::Il catalogo non referenzia alcun asset di release: non cancello nulla", proc.stderr)
        self.assertEqual([], self.lines("assets-deleted.txt"))

    def test_senza_la_guardia_del_grep_il_messaggio_sparisce(self):
        # Prova che il test sopra scopre la regressione: senza `|| true` il grep senza righe ferma lo script in silenzio.
        text = (fx.REPO / SCRIPT).read_text(encoding="utf-8")
        broken = re.sub(r"\{ (grep -oE '[^']+') \|\| true; \}", r"\1", text)
        self.assertNotEqual(text, broken)
        fx.write(self.tree / "broken.sh", broken, executable=True)
        self.script = "broken.sh"
        self.catalogue(self.site, "manifest.json", '"https://example.com/x"')
        self.catalogue(self.previous, "previous-manifest.json")
        proc = self.run_script()
        self.assertNotIn("Il catalogo non referenzia alcun asset", proc.stderr)

    def test_release_legacy_vuota_si_rimuove(self):
        self.catalogue(self.site, "manifest.json", url("region-data-r01", "a--1--poi.db"))
        self.catalogue(self.previous, "previous-manifest.json")
        self.releases(region_data_europe=[("x--0--poi.db", OLD, 7)], region_data_r01=[("a--1--poi.db", OLD, 1)])
        proc = self.run_script()
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["region-data-europe"], self.lines("releases-deleted.txt"))
        self.assertIn("region-data-europe: release vuota rimossa", proc.stdout)

    def test_release_per_numero_resta_anche_se_vuota(self):
        self.catalogue(self.site, "manifest.json", url("world-map", "w--1.pmtiles"))
        self.catalogue(self.previous, "previous-manifest.json")
        self.releases(region_data_r02=[("x--0--poi.db", OLD, 7)], region_data_guide=[("g--0.json", OLD, 8)])
        proc = self.run_script()
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertCountEqual(["7", "8"], self.lines("assets-deleted.txt"))
        self.assertEqual([], self.lines("releases-deleted.txt"))

    def test_region_fragments_non_si_tocca(self):
        self.catalogue(self.site, "manifest.json", url("region-data-r01", "a--1--poi.db"))
        self.catalogue(self.previous, "previous-manifest.json")
        self.releases(region_fragments=[("frag--0.json", OLD, 9)], region_data_r01=[("a--1--poi.db", OLD, 1)])
        proc = self.run_script()
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual([], self.lines("assets-deleted.txt"))
        self.assertNotIn("region-fragments", proc.stdout)


if __name__ == "__main__":
    unittest.main()
