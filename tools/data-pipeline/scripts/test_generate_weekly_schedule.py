#!/usr/bin/env python3
"""Test di generate-weekly-schedule.sh con regioni finte e un curl finto al posto di brouter.de e Pages: peso per
tile land o tempo misurato, giganti su giorni diversi, regioni con rank nei giorni 1-4, ogni regione una sola volta,
file generato leggibile da bash. Richiede bash.

Uso: python test_generate_weekly_schedule.py
"""
import re
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

SCRIPTS = "tools/data-pipeline/scripts"
# regionId, minLon, minLat, maxLon, maxLat: ognuna su una tile 5x5 gradi a parte (E<lon>_N0).
REGIONS = [("francia", 0, 0, 1, 1), ("francia-sud", 5, 0, 6, 1), ("ghiaccio", 10, 0, 11, 1), ("gigante-a", 15, 0, 16, 1),
           ("gigante-b", 20, 0, 21, 1), ("piccola-1", 25, 0, 26, 1), ("piccola-2", 30, 0, 31, 1), ("piccola-3", 35, 0, 36, 1)]
# Il resto delle tile ha un rd5; E10_N0 no (oceano).
CURL = r'''
args=("$@"); url="${args[${#args[@]}-1]}"
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[$i]}" in -I) head=1 ;; -o) out="${args[$((i + 1))]}" ;; esac
done
if [ -n "${head:-}" ]; then
  tile="$(basename "$url" .rd5)"
  case " $FAKE_OCEAN " in *" $tile "*) printf 404 ;; *) printf 200 ;; esac
  exit 0
fi
[ -f "${FAKE_TIMINGS:-/nonexistent}" ] || exit 22
cp "$FAKE_TIMINGS" "$out"
'''


@unittest.skipUnless(fx.BASH, "serve bash")
class WeeklyScheduleTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.tree = Path(self.dir.name)
        self.scripts = self.tree / SCRIPTS
        for f in ("generate-weekly-schedule.sh", "lib.sh", "portable.sh"):
            fx.copy(f"{SCRIPTS}/{f}", self.tree)
        fx.write(self.scripts / "regions.sh", "ALL_REGIONS=(\n" + "".join(f'  "{r}|{r}|{a}|{b}|{c}|{d}|x|x|x|x|x"\n' for r, a, b, c, d in REGIONS) + ")\n")
        fx.shim(self.tree, "curl", CURL)
        self.timings = fx.write(self.tree / "timings.tsv", "gigante-a\t5000\ngigante-b\t3000\nrumore\tx\n")

    def generate(self, with_timings=True):
        env = fx.environment(self.tree, FAKE_OCEAN="E10_N0", **({"FAKE_TIMINGS": self.timings.as_posix()} if with_timings else {}))
        proc = fx.run_bash([self.scripts / "generate-weekly-schedule.sh"], self.tree, env)
        self.assertEqual(0, proc.returncode, proc.stderr)
        return (self.scripts / "weekly-schedule.sh").read_text(encoding="utf-8")

    @staticmethod
    def days(text):
        return {d: [r for r in ids.split(",") if r] for d, ids in re.findall(r'^WEEKLY_SCHEDULE_DAY_(\d)="([^"]*)"$', text, re.M)}

    @staticmethod
    def tiles(text):
        return {r: int(n) for r, n in re.findall(r"^  \[([a-z0-9-]+)\]=(\d+)$", text, re.M)}

    def test_ogni_regione_compare_una_sola_volta(self):
        days = self.days(self.generate())
        self.assertEqual(["1", "2", "3", "4", "5", "6", "7"], sorted(days))
        assigned = [r for ids in days.values() for r in ids]
        self.assertCountEqual([r[0] for r in REGIONS], assigned)

    def test_peso_in_tile_land_o_dal_tempo_misurato(self):
        tiles = self.tiles(self.generate())
        self.assertEqual({"francia": 1, "francia-sud": 1, "ghiaccio": 0, "gigante-a": 100, "gigante-b": 60,
                          "piccola-1": 1, "piccola-2": 1, "piccola-3": 1}, tiles)

    def test_senza_tempi_misurati_pesa_una_tile_ciascuna(self):
        tiles = self.tiles(self.generate(with_timings=False))
        self.assertEqual({"francia": 1, "francia-sud": 1, "ghiaccio": 0, "gigante-a": 1, "gigante-b": 1,
                          "piccola-1": 1, "piccola-2": 1, "piccola-3": 1}, tiles)

    def test_giganti_in_giorni_diversi_e_rank_nei_primi_quattro(self):
        days = self.days(self.generate())
        self.assertEqual({
            "1": ["gigante-a"], "2": ["gigante-b"], "3": ["francia"], "4": ["francia-sud"],
            "5": ["ghiaccio", "piccola-1"], "6": ["piccola-2"], "7": ["piccola-3"],
        }, days)

    def test_il_file_si_carica_con_source(self):
        self.generate()
        proc = fx.run_bash(["-c", f'source "{(self.scripts / "weekly-schedule.sh").as_posix()}"; echo "$WEEKLY_SCHEDULE_DAY_2 ${{WEEKLY_SCHEDULE_TILES[gigante-a]}}"'],
                           self.tree, fx.environment(self.tree))
        self.assertEqual(0, proc.returncode, proc.stderr)
        self.assertEqual("gigante-b 100", proc.stdout.strip())

    def test_riga_di_carico_per_giorno(self):
        text = self.generate()
        self.assertEqual(["100", "60", "1", "1", "1", "1", "1"], re.findall(r"^#   giorno \d: (\d+) tile land$", text, re.M))

    def test_due_generazioni_uguali(self):
        self.assertEqual(self.generate(), self.generate())


if __name__ == "__main__":
    unittest.main()
