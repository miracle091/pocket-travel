#!/usr/bin/env python3
"""Test di .github/scripts/publish-regions/plan-shards.sh con regioni, calendario, tempi e catalogo finti (un server
HTTP locale al posto di Pages): scelta delle regioni (bucket del giorno, input manuale, tutte), bucket vuoto, spartizione
per peso nei 3 shard, quota adattiva. Richiede bash.

Uso: python test_plan_shards.py
"""
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

REGIONS = ["aaa", "bbb", "ccc", "ddd"]
SCRIPT = ".github/scripts/publish-regions/plan-shards.sh"


def schedule(days, tiles):
    lines = [f'WEEKLY_SCHEDULE_DAY_{d}="{",".join(days.get(d, []))}"' for d in range(1, 8)]
    lines += ["declare -A WEEKLY_SCHEDULE_TILES=(", *[f"  [{r}]={t}" for r, t in tiles.items()], ")"]
    return "\n".join(lines) + "\n"


def manifest(ages):
    """Catalogo finto: regionId -> data (AAAA.MM.GG) dei POI pubblicati."""
    return ('{"regions":[' + ",".join(
        f'{{"regionId":"{r}","poi":{{"file":{{"url":"https://x/{r}--{d}--poi.db"}}}}}}' for r, d in ages.items()) + "]}").encode()


@unittest.skipUnless(fx.BASH, "serve bash")
class PlanShardsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        for f in (SCRIPT, ".github/scripts/env.sh", "tools/data-pipeline/scripts/portable.sh"):
            fx.copy(f, self.tree)
        fx.write(self.tree / "tools/data-pipeline/scripts/regions.sh", "ALL_REGIONS=(\n" + "".join(f'  "{r}|{r}|0|0|1|1"\n' for r in REGIONS) + ")\n")
        self.set_schedule({3: ["aaa", "bbb"], 4: ["ccc"]}, {r: 2 for r in REGIONS})

    def set_schedule(self, days, tiles):
        fx.write(self.tree / "tools/data-pipeline/scripts/weekly-schedule.sh", schedule(days, tiles))

    def plan(self, files=None, **env):
        """Esegue il piano; restituisce (processo, output del job)."""
        out = self.tree / "github-output.txt"
        out.write_text("", encoding="utf-8")
        with fx.serve(files if files is not None else {}) as server:
            environment = fx.environment(self.tree, GITHUB_REPOSITORY="o/r", RUNNER_TEMP=self.tree.as_posix(),
                                         GITHUB_OUTPUT=out.as_posix(), PAGES_BASE_URL=server.base, **env)
            proc = fx.run_bash([SCRIPT], self.tree, environment)
        return proc, fx.parse_output(out)

    @staticmethod
    def regions(output, key):
        return [r for shard in range(3) for r in output[f"shard{shard}{key}"].split(",") if r]

    def test_bucket_del_giorno(self):
        proc, out = self.plan(SCHEDULE="0 2 * * 3")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertCountEqual(["aaa", "bbb"], self.regions(out, ""))

    def test_domenica_e_il_giorno_7(self):
        self.set_schedule({7: ["ddd"]}, {r: 2 for r in REGIONS})
        proc, out = self.plan(SCHEDULE="0 2 * * 0")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["ddd"], self.regions(out, ""))

    def test_bucket_vuoto_non_costruisce_tutte_le_regioni(self):
        proc, out = self.plan(SCHEDULE="0 2 * * 5")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual([], self.regions(out, ""))
        self.assertEqual({"shard0", "shard1", "shard2", "shard0_extra", "shard1_extra", "shard2_extra"}, set(out))

    def test_bucket_vuoto_tiene_la_quota_adattiva(self):
        # Il giorno 5 e' vuoto: le regioni in ritardo e quelle di domani (giorno 6, vuoto qui) restano in coda.
        proc, out = self.plan({"/manifest.json": manifest({"aaa": "2020.01.01", "bbb": "2999.01.01", "ccc": "2999.01.01", "ddd": "2999.01.01"})}, SCHEDULE="0 2 * * 5")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual([], self.regions(out, ""))
        self.assertEqual(["aaa"], [e.split(":")[0] for e in self.regions(out, "_extra")])

    def test_input_manuale(self):
        proc, out = self.plan(REGIONS_INPUT=" aaa, ccc ")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertCountEqual(["aaa", "ccc"], self.regions(out, ""))
        self.assertEqual([], self.regions(out, "_extra"))

    def test_manuale_senza_regioni_le_costruisce_tutte(self):
        proc, out = self.plan()
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertCountEqual(REGIONS, self.regions(out, ""))

    def test_input_non_valido(self):
        proc, _ = self.plan(REGIONS_INPUT="aaa;rm -rf /")
        self.assertEqual(1, proc.returncode)
        self.assertIn("regions non valido", proc.stderr)

    def test_spartizione_per_peso(self):
        # Pesi misurati 300, 200, 100, 50 s: le tre piu' pesanti in shard diversi, l'ultima con la piu' leggera.
        timings = b"aaa\t300\nbbb\t200\nccc\t100\nddd\t50\nrumore senza numeri\tx\n"
        proc, out = self.plan({"/region-timings.tsv": timings}, REGIONS_INPUT="aaa,bbb,ccc,ddd")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["aaa", "bbb", "ccc,ddd"], [out[f"shard{n}"] for n in range(3)])
        self.assertIn("tempi misurati per 4 regioni", proc.stdout)

    def test_senza_tempi_il_peso_sono_le_tile(self):
        self.set_schedule({}, {"aaa": 10, "bbb": 1, "ccc": 1, "ddd": 1})
        proc, out = self.plan(REGIONS_INPUT="aaa,bbb,ccc,ddd")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual(["aaa", "bbb,ddd", "ccc"], [out[f"shard{n}"] for n in range(3)])

    def test_quota_adattiva_in_ritardo_e_domani(self):
        # Oggi (giorno 3) aaa e bbb; ccc ha i POI vecchi (in ritardo), ddd e' di domani (giorno 4).
        self.set_schedule({3: ["aaa", "bbb"], 4: ["ddd"]}, {r: 2 for r in REGIONS})
        files = {"/manifest.json": manifest({"aaa": "2999.01.01", "bbb": "2999.01.01", "ccc": "2020.01.01", "ddd": "2999.01.01"})}
        proc, out = self.plan(files, SCHEDULE="0 2 * * 3")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertCountEqual(["aaa", "bbb"], self.regions(out, ""))
        self.assertEqual(["ccc:100", "ddd:100"], sorted(self.regions(out, "_extra")))

    def test_catalogo_non_leggibile_avvisa_e_non_aggiunge_ritardi(self):
        proc, out = self.plan(SCHEDULE="0 2 * * 3")
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertIn("manifest.json pubblicato non leggibile", proc.stdout)
        self.assertEqual(["ccc:100"], self.regions(out, "_extra"))


if __name__ == "__main__":
    unittest.main()
