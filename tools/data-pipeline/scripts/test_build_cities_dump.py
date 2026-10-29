#!/usr/bin/env python3
"""Test di build-cities-dump.sh con lib.sh e gli estrattori finti (nessuna rete): risoluzione dei titoli IT, dump non
scaricato, estrattori chiamati con le regioni risolte, ramo inglese. Richiede bash.

Uso: python test_build_cities_dump.py
"""
import json
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

SCRIPTS = "tools/data-pipeline/scripts"
FAKE_LIB = r'''
prefetch_it_wikivoyage_titles() { :; }
resolve_it_wikivoyage_title() { grep "^$1=" "$FAKE_TITLES" | cut -d= -f2 || true; }
fetch_wikivoyage_dump_parts() {
  [ "${FAKE_DUMP_FAIL:-}" != "$1" ] || return 1
  mkdir -p "$2"
  echo "$1" > "$2/parte"
}
'''
# Gli estrattori veri leggono il dump; quelli finti registrano come sono stati chiamati.
FAKE_EXTRACTOR = '''import json, os, sys
from pathlib import Path
dump, regions, out = sys.argv[1:4]
Path(os.environ["FAKE_LOG"]).write_text(json.dumps({"tool": Path(sys.argv[0]).name, "dump": Path(dump, "parte").read_text().strip(),
                                                     "regions": Path(regions).read_text()}))
for line in Path(regions).read_text().splitlines():
    Path(out, line.split("\\t")[0] + ".cities.jsonl").write_text("{}\\n")
'''


@unittest.skipUnless(fx.BASH, "serve bash")
class BuildCitiesDumpTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        scripts = self.tree / SCRIPTS
        fx.copy(f"{SCRIPTS}/build-cities-dump.sh", self.tree)
        fx.write(scripts / "lib.sh", FAKE_LIB)
        for name in ("extract-cities-dump.py", "extract-cities-dump-en.py"):
            fx.write(scripts / name, FAKE_EXTRACTOR)
        self.out = self.tree / "out"
        self.log = self.tree / "log.json"
        self.titles = fx.write(self.tree / "titles.txt", "Italy=Italia\nFrance=Francia\n")
        self.regions = fx.write(self.tree / "regioni.tsv", "italia\tItaly\nfrancia\tFrance\nnarnia\tNarnia\n")

    def run_script(self, *args, **env):
        environment = fx.environment(self.tree, FAKE_TITLES=self.titles.as_posix(), FAKE_LOG=self.log.as_posix(), **env)
        return fx.run_bash([self.tree / SCRIPTS / "build-cities-dump.sh", *args], self.tree, environment)

    def logged(self):
        return json.loads(self.log.read_text(encoding="utf-8")) if self.log.exists() else None

    def files(self):
        return sorted(p.name for p in self.out.iterdir()) if self.out.exists() else []

    def test_parametri(self):
        self.assertEqual(1, self.run_script(self.regions).returncode)
        proc = self.run_script(self.regions, self.out, "it", "extra")
        self.assertEqual(1, proc.returncode)
        self.assertIn("Uso:", proc.stderr)

    def test_italiano_estrae_solo_le_regioni_risolte(self):
        proc = self.run_script(self.regions, self.out)
        self.assertEqual(0, proc.returncode, proc.stderr)
        call = self.logged()
        self.assertEqual("extract-cities-dump.py", call["tool"])
        self.assertEqual("it", call["dump"])
        self.assertEqual("italia\tItalia\nfrancia\tFrancia\n", call["regions"])
        self.assertEqual(["francia.cities.jsonl", "italia.cities.jsonl"], self.files())

    def test_nessuna_pagina_italiana_risolta_esce_con_1_senza_scrivere(self):
        self.titles.write_text("", encoding="utf-8")
        proc = self.run_script(self.regions, self.out)
        self.assertEqual(1, proc.returncode)
        self.assertIn("::warning::citta': nessuna pagina IT Wikivoyage risolta", proc.stdout)
        self.assertIsNone(self.logged())
        self.assertEqual([], self.files())

    def test_dump_italiano_non_scaricato_esce_con_1_senza_scrivere(self):
        proc = self.run_script(self.regions, self.out, FAKE_DUMP_FAIL="it")
        self.assertEqual(1, proc.returncode)
        self.assertIn("dump Wikivoyage IT non scaricato", proc.stdout)
        self.assertIsNone(self.logged())
        self.assertEqual([], self.files())

    def test_inglese_passa_tutte_le_regioni_al_suo_estrattore(self):
        proc = self.run_script(self.regions, self.out, "en")
        self.assertEqual(0, proc.returncode, proc.stderr)
        call = self.logged()
        self.assertEqual("extract-cities-dump-en.py", call["tool"])
        self.assertEqual("en", call["dump"])
        self.assertEqual("italia\tItaly\nfrancia\tFrance\nnarnia\tNarnia\n", call["regions"])

    def test_dump_inglese_non_scaricato_esce_con_1(self):
        proc = self.run_script(self.regions, self.out, "en", FAKE_DUMP_FAIL="en")
        self.assertEqual(1, proc.returncode)
        self.assertIn("dump Wikivoyage EN non scaricato", proc.stdout)
        self.assertIsNone(self.logged())


if __name__ == "__main__":
    unittest.main()
