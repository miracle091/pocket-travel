#!/usr/bin/env python3
"""Test di city_population.py sulla logica pura (Abitanti, scelta del valore Wikidata, annotazione dei JSONL):
nessuna rete, le chiamate alle API sono sostituite con unittest.mock.

Uso: python test_city_population.py
"""
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

import city_population


def claim(amount, rank="normal", time=None):
    c = {"rank": rank, "mainsnak": {"datavalue": {"value": {"amount": amount}}}}
    if time:
        c["qualifiers"] = {"P585": [{"datavalue": {"value": {"time": time}}}]}
    return c


def entity_claim(qid, rank="normal", end=False):
    c = {"rank": rank, "mainsnak": {"datavalue": {"value": {"id": qid}}}}
    if end:
        c["qualifiers"] = {"P582": [{}]}
    return c


class AbitantiTest(unittest.TestCase):
    def test_numero_con_punti_come_separatore_delle_migliaia(self):
        self.assertEqual(360462, city_population.abitanti("{{QuickbarCity\n| Abitanti = 360.462 <small>(2023)</small>\n}}"))

    def test_spazio_non_separabile_tra_le_cifre(self):
        self.assertEqual(1234567, city_population.abitanti("| Abitanti = 1 234 567\n"))

    def test_campo_assente(self):
        self.assertIsNone(city_population.abitanti("{{QuickbarCity\n| Regione = [[Lazio]]\n}}"))

    def test_valore_non_numerico_o_zero(self):
        self.assertIsNone(city_population.abitanti("| Abitanti = n.d.\n"))
        self.assertIsNone(city_population.abitanti("| Abitanti = 0\n"))

    def test_oltre_i_primi_6000_caratteri_non_si_cerca(self):
        self.assertIsNone(city_population.abitanti("x" * 6000 + "\n| Abitanti = 100\n"))


class LatestPopulationTest(unittest.TestCase):
    def test_nessun_valore(self):
        self.assertIsNone(city_population._latest_population({}))

    def test_vince_la_data_piu_recente(self):
        claims = {"P1082": [claim("+100", time="+2010-00-00T00:00:00Z"), claim("+200", time="+2020-00-00T00:00:00Z")]}
        self.assertEqual(200, city_population._latest_population(claims))

    def test_il_rango_preferito_batte_la_data(self):
        claims = {"P1082": [claim("+100", rank="preferred", time="+2010-00-00T00:00:00Z"),
                            claim("+200", time="+2020-00-00T00:00:00Z")]}
        self.assertEqual(100, city_population._latest_population(claims))

    def test_valori_malformati_ignorati(self):
        claims = {"P1082": [{"mainsnak": {}}, claim("abc"), claim("+50")]}
        self.assertEqual(50, city_population._latest_population(claims))

    def test_valore_decimale(self):
        self.assertEqual(1500, city_population._latest_population({"P1082": [claim("+1500.0")]}))


class CurrentValuesTest(unittest.TestCase):
    def test_scarta_deprecati_e_valori_con_data_di_fine(self):
        claims = [entity_claim("Q1"), entity_claim("Q2", end=True), entity_claim("Q3", rank="deprecated")]
        self.assertEqual(["Q1"], city_population._current_values(claims))

    def test_il_preferito_batte_gli_altri(self):
        claims = [entity_claim("Q1"), entity_claim("Q2", rank="preferred", end=True)]
        self.assertEqual(["Q2"], city_population._current_values(claims))

    def test_senza_datavalue_ignorato(self):
        self.assertEqual([], city_population._current_values([{"rank": "normal", "mainsnak": {}}]))


class GetTest(unittest.TestCase):
    def _response(self, payload):
        r = mock.MagicMock()
        r.__enter__.return_value.read.return_value = json.dumps(payload).encode("utf-8")
        return r

    def test_riprova_dopo_un_errore_e_poi_riesce(self):
        with mock.patch("city_population.urllib.request.urlopen", side_effect=[OSError("429"), self._response({"a": 1})]), \
                mock.patch("city_population.time.sleep") as sleep, redirect_stderr(io.StringIO()):
            self.assertEqual({"a": 1}, city_population._get("http://x"))
        sleep.assert_called_once_with(5)

    def test_dopo_quattro_tentativi_rilancia(self):
        with mock.patch("city_population.urllib.request.urlopen", side_effect=OSError("errore")) as urlopen, \
                mock.patch("city_population.time.sleep"), redirect_stderr(io.StringIO()):
            with self.assertRaises(OSError):
                city_population._get("http://x")
        self.assertEqual(4, urlopen.call_count)


class WikidataPopulationsTest(unittest.TestCase):
    def test_segue_normalizzazioni_e_redirect_fino_al_titolo_richiesto(self):
        responses = [
            {"query": {"normalized": [{"from": "roma", "to": "Roma"}],
                       "redirects": [{"from": "Roma", "to": "Roma (citta)"}],
                       "pages": {"1": {"title": "Roma (citta)", "pageprops": {"wikibase_item": "Q220"}},
                                 "2": {"title": "Senza", "pageprops": {}}}}},
            {"entities": {"Q220": {"claims": {"P1082": [claim("+2800000")]}}}},
        ]
        with mock.patch("city_population._get", side_effect=responses), mock.patch("city_population.time.sleep"):
            result = city_population.wikidata_populations(["roma", "Senza"], "it")
        self.assertEqual({"roma": 2800000}, result)

    def test_elemento_senza_popolazione_omesso(self):
        responses = [
            {"query": {"pages": {"1": {"title": "Villa", "pageprops": {"wikibase_item": "Q5"}}}}},
            {"entities": {"Q5": {"claims": {}}}},
        ]
        with mock.patch("city_population._get", side_effect=responses), mock.patch("city_population.time.sleep"):
            self.assertEqual({}, city_population.wikidata_populations(["Villa"], "it"))


def coord(lat, lon, rank="normal", globe=city_population.EARTH):
    return {"rank": rank, "mainsnak": {"datavalue": {"value": {"latitude": lat, "longitude": lon, "globe": globe}}}}


class CoordinatesTest(unittest.TestCase):
    def test_nessuna_coordinata(self):
        self.assertIsNone(city_population._coordinates({}))

    def test_la_preferita_batte_la_prima_e_si_arrotonda(self):
        claims = {"P625": [coord(45.0, 7.0), coord(45.0703393, 7.686864, rank="preferred")]}
        self.assertEqual((45.07034, 7.68686), city_population._coordinates(claims))

    def test_deprecate_e_fuori_dalla_terra_ignorate(self):
        claims = {"P625": [coord(1.0, 2.0, rank="deprecated"), coord(3.0, 4.0, globe="http://www.wikidata.org/entity/Q405"),
                           {"mainsnak": {}}, coord(5.0, 6.0)]}
        self.assertEqual((5.0, 6.0), city_population._coordinates(claims))

    def test_wikidata_coordinates_dal_titolo_della_pagina(self):
        responses = [
            {"query": {"pages": {"1": {"title": "Torino", "pageprops": {"wikibase_item": "Q495"}}}}},
            {"entities": {"Q495": {"claims": {"P625": [coord(45.07, 7.68)]}}}},
        ]
        with mock.patch("city_population._get", side_effect=responses), mock.patch("city_population.time.sleep"):
            self.assertEqual({"Torino": (45.07, 7.68)}, city_population.wikidata_coordinates(["Torino"], "it"))


class CapitalTitlesTest(unittest.TestCase):
    def test_capitale_con_titolo_della_pagina_e_etichetta(self):
        responses = [
            {"query": {"pages": {"1": {"title": "Giappone", "pageprops": {"wikibase_item": "Q17"}}}}},
            {"entities": {"Q17": {"claims": {"P36": [entity_claim("Q1490"), entity_claim("Q99", end=True)]}}}},
            {"entities": {"Q1490": {"sitelinks": {"itwikivoyage": {"title": "Tokyo"}},
                                    "labels": {"it": {"value": "Prefettura di Tokyo"}}}}},
        ]
        with mock.patch("city_population._get", side_effect=responses), mock.patch("city_population.time.sleep"):
            result = city_population.capital_titles(["Giappone"], "it")
        self.assertEqual({"Giappone": {"Tokyo", "Prefettura di Tokyo"}}, result)


class AnnotateTest(unittest.TestCase):
    def _write(self, directory, name, rows):
        path = Path(directory) / name
        path.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
        return path

    def _read(self, path):
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]

    def test_it_usa_abitanti_poi_wikidata_e_segna_la_capitale(self):
        with tempfile.TemporaryDirectory() as d:
            path = self._write(d, "lazio.jsonl", [
                {"city": "Roma", "text": "| Abitanti = 2.800.000\n"},
                {"city": "Viterbo", "text": "nulla"},
                {"city": "Rieti", "text": "nulla"},
            ])
            with mock.patch("city_population.wikidata_populations", return_value={"Viterbo": 67000}) as wp, \
                    mock.patch("city_population.wikidata_coordinates", return_value={"Roma": (41.89306, 12.48278)}) as wc, \
                    mock.patch("city_population.capital_titles", return_value={"Lazio": {"Roma"}}), \
                    redirect_stdout(io.StringIO()) as out:
                city_population.annotate([path], "it", {path: "Lazio"})
            rows = self._read(path)
        self.assertEqual({"Viterbo", "Rieti"}, set(wp.call_args[0][0]))
        self.assertEqual([2800000, 67000, None], [r["population"] for r in rows])
        self.assertEqual([True, False, False], [r["capital"] for r in rows])
        # coordinate per tutte le citta', non solo per quelle senza Abitanti
        self.assertEqual({"Roma", "Viterbo", "Rieti"}, set(wc.call_args[0][0]))
        self.assertEqual([(41.89306, 12.48278), (None, None), (None, None)], [(r["lat"], r["lon"]) for r in rows])
        self.assertIn("popolazione per 2/3", out.getvalue())
        self.assertIn("coordinate per 1/3", out.getvalue())

    def test_en_ignora_abitanti(self):
        with tempfile.TemporaryDirectory() as d:
            path = self._write(d, "x.jsonl", [{"city": "A", "text": "| Abitanti = 5\n"}])
            with mock.patch("city_population.wikidata_populations", return_value={"A": 9}) as wp, \
                    mock.patch("city_population.wikidata_coordinates", return_value={}), \
                    redirect_stdout(io.StringIO()):
                city_population.annotate([path], "en")
            rows = self._read(path)
        self.assertEqual({"A"}, set(wp.call_args[0][0]))
        self.assertEqual(9, rows[0]["population"])
        self.assertFalse(rows[0]["capital"])

    def test_errore_di_rete_non_e_fatale(self):
        with tempfile.TemporaryDirectory() as d:
            path = self._write(d, "x.jsonl", [{"city": "A", "text": "| Abitanti = 5\n"}, {"city": "B", "text": ""}])
            with mock.patch("city_population.wikidata_populations", side_effect=OSError("rete")), \
                    mock.patch("city_population.wikidata_coordinates", side_effect=OSError("rete")), \
                    mock.patch("city_population.capital_titles", side_effect=OSError("rete")), \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()) as err:
                city_population.annotate([path], "it", {path: "Regione"})
            rows = self._read(path)
        self.assertEqual([5, None], [r["population"] for r in rows])
        self.assertEqual([False, False], [r["capital"] for r in rows])
        self.assertEqual([None, None], [r["lat"] for r in rows])
        self.assertIn("non disponibile", err.getvalue())

    def test_senza_rete_restano_le_coordinate_gia_nel_file(self):
        with tempfile.TemporaryDirectory() as d:
            path = self._write(d, "x.jsonl", [{"city": "A", "text": "", "lat": 45.1, "lon": 7.6}])
            with mock.patch("city_population.wikidata_populations", return_value={}), \
                    mock.patch("city_population.wikidata_coordinates", side_effect=OSError("rete")), \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                city_population.annotate([path], "en")
            rows = self._read(path)
        self.assertEqual((45.1, 7.6), (rows[0]["lat"], rows[0]["lon"]))


if __name__ == "__main__":
    unittest.main()
