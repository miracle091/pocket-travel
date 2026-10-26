#!/usr/bin/env python3
"""Test dell'elenco delle regioni (regions.sh) e dei suoi lettori in Python: load_regions e vs_codes di
generate_sft_dataset.py, flag_regions di generate_sft.py. Il file vero e' controllato per coerenza (identificativi
unici, rettangoli validi, codici paese); i lettori sono provati su un regions.sh sintetico. Niente rete.

Uso: python test_regions_list.py
"""
import json
import re
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest import mock

import generate_sft
import generate_sft_dataset as g

REGIONS_SH = Path(__file__).resolve().parent / "regions.sh"
ROW = re.compile(r'^\s*"([^"]+\|[^"]+)"\s*$', re.M)


def real_rows():
    return [r.split("|") for r in ROW.findall(REGIONS_SH.read_text(encoding="utf-8"))]


class RealRegionsTest(unittest.TestCase):
    """regions.sh e' la fonte unica di regioni per pipeline e workflow: un errore qui arriva a tutti."""

    def setUp(self):
        self.full = [r for r in real_rows() if len(r) >= 11]   # le altre sono le regioni sostituite (id|sostituta)

    def test_identificativi_unici_e_nel_formato_degli_slug(self):
        ids = [r[0] for r in real_rows()]
        self.assertEqual([], [i for i, n in Counter(ids).items() if n > 1])
        for region_id in ids:
            self.assertRegex(region_id, r"^[a-z0-9]+(-[a-z0-9]+)*$")

    def test_riquadri_validi(self):
        for r in self.full:
            min_lon, min_lat, max_lon, max_lat = (float(v) for v in r[2:6])
            self.assertTrue(-180 <= min_lon < max_lon <= 180, r[0])
            self.assertTrue(-90 <= min_lat < max_lat <= 90, r[0])

    def test_codice_paese_titolo_wikivoyage_e_continente(self):
        continents = {"Nord America", "Sud America", "Europa", "Asia", "Africa", "Oceania"}
        for r in self.full:
            self.assertRegex(r[7], r"^[a-z]{2}$", r[0])
            self.assertTrue(r[6] and " " not in r[6], r[0])        # titolo Wikivoyage con i trattini bassi
            self.assertIn(r[10], continents, r[0])
            self.assertTrue(r[1].strip(), r[0])

    def test_gruppo_con_etichetta(self):
        for r in self.full:
            self.assertEqual(bool(r[8]), bool(r[9]), r[0])           # groupName e groupLabel vanno in coppia

    def test_le_regioni_sostituite_non_sono_piu_nell_elenco(self):
        replaced = [r for r in real_rows() if len(r) == 2]
        ids = {r[0] for r in self.full}
        for old, _ in replaced:
            self.assertNotIn(old, ids)

    def test_nomi_inglesi_espliciti_solo_per_regioni_con_gruppo(self):
        block = REGIONS_SH.read_text(encoding="utf-8").split("GROUP_LABELS_EN=(", 1)[1].split("\n)", 1)[0]
        labels = dict(re.findall(r'^\s*\[([a-z0-9-]+)\]="([^"]+)"$', block, re.M))
        grouped = {r[0] for r in self.full if r[8]}
        self.assertTrue(labels)
        self.assertEqual([], sorted(set(labels) - grouped))

    def test_load_regions_legge_tutte_quelle_complete(self):
        self.assertEqual([(r[0], r[1], r[6]) for r in real_rows() if len(r) >= 7], g.load_regions())


SYNTHETIC = '''#!/usr/bin/env bash
# "commento|con barra" non e' una riga
REGIONS=(
  "italia|Italia|6.60|35.29|18.60|47.10|Italy|it|||Europa"
  "san-marino|San Marino|12.40|43.89|12.52|43.99|San_Marino|sm|||Europa"
  "stati-uniti-texas|Stati Uniti - Texas|-106.65|25.84|-93.51|36.50|Texas|us|stati-uniti|Stati Uniti|Nord America"
  "stati-uniti-ohio|Stati Uniti - Ohio|-84.82|38.40|-80.52|41.98|Ohio|us|stati-uniti|Stati Uniti|Nord America"
  "isola|Isola|0|0|1|1|Isola|zz|||Oceania"
  "corta|Corta"
)
REPLACED_REGIONS=(
  "stati-uniti|stati-uniti-*"
)
'''


class ReadersTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.here = Path(tmp.name)
        (self.here / "regions.sh").write_text(SYNTHETIC, encoding="utf-8")
        for module in (g, generate_sft.it):
            patcher = mock.patch.object(module, "HERE", self.here)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_load_regions_scarta_le_righe_con_meno_di_sette_campi(self):
        self.assertEqual([("italia", "Italia", "Italy"), ("san-marino", "San Marino", "San_Marino"),
                          ("stati-uniti-texas", "Stati Uniti - Texas", "Texas"), ("stati-uniti-ohio", "Stati Uniti - Ohio", "Ohio"),
                          ("isola", "Isola", "Isola")], g.load_regions())

    def test_flag_regions_raggruppa_per_codice_paese(self):
        flags = generate_sft.flag_regions()
        self.assertEqual({"it": ["italia"], "sm": ["san-marino"], "us": ["stati-uniti-texas", "stati-uniti-ohio"], "zz": ["isola"]},
                         dict(flags))

    def test_vs_codes_solo_per_regioni_con_bandiera_propria_e_fuori_dai_gruppi(self):
        nations = [{"Codice-2": "IT", "Codice-3": "ITA"}, {"Codice-2": "SM", "Codice-3": "SMR"}, {"Codice-2": "US", "Codice-3": "USA"}]
        with mock.patch.object(g, "get", return_value=json.dumps(nations)):
            codes = g.vs_codes([])
        # Texas e Ohio condividono "us" e hanno un gruppo; "zz" non e' nell'elenco di Viaggiare Sicuri
        self.assertEqual({"italia": "ITA", "san-marino": "SMR"}, codes)

    def test_vs_codes_per_la_regione_principale_di_un_paese_con_gli_arcipelaghi_a_parte(self):
        (self.here / "regions.sh").write_text('''REGIONS=(
  "portogallo|Portogallo|-9.54|36.84|-6.17|42.29|Portugal|pt|Portogallo|Portogallo|Europa"
  "portogallo-azzorre|Portogallo - Azzorre|-31.29|36.91|-24.99|39.75|Azores|pt|Portogallo|Azzorre|Europa"
  "figi-occidentali|Figi - Isole occidentali|176.97|-20.70|180.00|-12.42|Fiji|fj|Figi|Isole occidentali|Oceania"
  "figi-lau|Figi - Isole Lau|-180.00|-21.05|-178.00|-15.59|Fiji|fj|Figi|Isole Lau|Oceania"
)
''', encoding="utf-8")
        nations = [{"Codice-2": "PT", "Codice-3": "PRT"}, {"Codice-2": "FJ", "Codice-3": "FJI"}]
        with mock.patch.object(g, "get", return_value=json.dumps(nations)):
            codes = g.vs_codes([])
        # Le Azzorre sono una parte del Portogallo; le due regioni delle Figi sono tutte e due una parte.
        self.assertEqual({"portogallo": "PRT"}, codes)


if __name__ == "__main__":
    unittest.main()
