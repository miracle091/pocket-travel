#!/usr/bin/env python3
"""Test di build-address-cell.sh senza rete e senza Gradle (pmtiles e gradlew finti): in una divisione, una cella figlia
il cui generateAddresses fallisce fa fallire la cella (il genitore resta nell'indice), non la lascia senza voce.
Richiede bash, jq, xz.

Uso: python test_build_address_cell.py
"""
import shutil
import tempfile
import unittest
from pathlib import Path

import shell_fixtures as fx

SCRIPT = "tools/data-pipeline/scripts/build-address-cell.sh"
PMTILES = """#!/usr/bin/env bash
if [[ " $* " == *" --dry-run "* ]]; then echo "archive size of 1 MB"; exit 0; fi
printf 'z15' > "$3"
"""
# La cella genitore (12/1/1) genera un file che supera il tetto; le figlie falliscono.
GRADLE = r"""#!/usr/bin/env bash
args="$*"
if [[ "$args" == *'--cell "12/1/1"'* ]]; then
  out="$(printf '%s' "$args" | sed -n 's/^[^"]*"\([^"]*\)".*/\1/p')"
  head -c 4096 /dev/urandom > "$out"
  echo "indirizzi: 10 punti"
  exit 0
fi
exit 1
"""


@unittest.skipUnless(fx.BASH and shutil.which("jq") and shutil.which("xz"), "servono bash, jq e xz")
class BuildAddressCellTest(unittest.TestCase):
    def test_figlia_con_generate_addresses_fallito_fa_fallire_la_cella(self):
        with tempfile.TemporaryDirectory() as d:
            tree = Path(d)
            for f in (SCRIPT, "tools/data-pipeline/scripts/lib.sh", "tools/data-pipeline/scripts/portable.sh"):
                fx.copy(f, tree)
            fx.write(tree / "overture.tsv", "")
            fx.write(tree / "gradlew", GRADLE, executable=True)
            fx.shim(tree, "pmtiles", PMTILES.split("\n", 1)[1])
            env = fx.environment(tree, PMTILES_BIN="pmtiles", ADDRESS_CELL_MAX_BYTES=1, PYTHON_BIN="", ADDRESSES_ATTEMPTS=1)
            out = tree / "out"
            proc = fx.run_bash([tree / SCRIPT, "12/1/1", "2026.11.01.1.1", "http://127.0.0.1:9/x.pmtiles", "http://127.0.0.1:9/a", out], tree, env)
            self.assertEqual(1, proc.returncode, proc.stdout + proc.stderr)
            self.assertIn("figlia di una divisione", proc.stderr)
            self.assertEqual("", (out / "address-grid-entries.jsonl").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
