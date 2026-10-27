#!/usr/bin/env python3
"""Test di poi_wikidata.py: nessuna rete, la query SPARQL e' sostituita con unittest.mock.

Uso: python test_poi_wikidata.py
"""
import io
import json
import sqlite3
import tempfile
import time
import unittest
from contextlib import closing, redirect_stderr
from datetime import date, timedelta
from pathlib import Path
from unittest import mock

import poi_wikidata

PARIS = (48.8584, 2.2945)


def binding(qid, links, protected=False, unesco=False, at=PARIS):
    row = {"item": {"value": f"http://www.wikidata.org/entity/Q{qid}"}, "links": {"value": str(links)}}
    if at:
        row["coord"] = {"value": f"Point({at[1]} {at[0]})"}
    if protected:
        row["protected"] = {"value": "1"}
    if unesco:
        row["unesco"] = {"value": "1"}
    return row


def answer(*rows):
    return {"results": {"bindings": list(rows)}}


def poi_db(path, qids, at=PARIS):
    """poi.db con le sole colonne che lo script legge e scrive, una riga per elemento di [qids] (None: senza), tutte nel
    punto [at]."""
    with closing(sqlite3.connect(path)) as db:
        db.execute("CREATE TABLE poi (name TEXT NOT NULL, latE6 INTEGER NOT NULL, lonE6 INTEGER NOT NULL, "
                   "wikidata INTEGER, sitelinks INTEGER, heritage INTEGER)")
        db.executemany("INSERT INTO poi (name, latE6, lonE6, wikidata) VALUES (?, ?, ?, ?)",
                       [(f"p{i}", round(at[0] * 1e6), round(at[1] * 1e6), q) for i, q in enumerate(qids)])
        db.commit()
    return path


def rows(path):
    with closing(sqlite3.connect(path)) as db:
        return db.execute("SELECT wikidata, sitelinks, heritage FROM poi ORDER BY rowid").fetchall()


class FetchTest(unittest.TestCase):
    def test_fama_patrimonio_e_coordinate_unesco_vince_sulla_tutela_elemento_assente_vuoto(self):
        with mock.patch("poi_wikidata._post", return_value=answer(
                binding(243, 191, protected=True, unesco=True), binding(10285, 148, protected=True),
                binding(19675, 170, at=None))), mock.patch("poi_wikidata.time.sleep"):
            facts = poi_wikidata.fetch({243, 10285, 19675, 5}, time.monotonic() + 60)
        self.assertEqual({243: [191, 2, *PARIS], 10285: [148, 1, *PARIS], 19675: [170, None, None, None],
                          5: [None, None, None, None]}, facts)

    def test_lotti_da_250_elementi_nella_query(self):
        with mock.patch("poi_wikidata._post", return_value=answer()) as post, mock.patch("poi_wikidata.time.sleep"):
            poi_wikidata.fetch(set(range(1, 502)), time.monotonic() + 60)
        self.assertEqual(3, post.call_count)
        self.assertIn("wd:Q1 wd:Q2 ", post.call_args_list[0].args[0])
        self.assertIn("wd:Q501 }", post.call_args_list[2].args[0])

    def test_errore_di_rete_o_tempo_finito_lasciano_gli_elementi_non_chiesti(self):
        with mock.patch("poi_wikidata._post", side_effect=[answer(binding(1, 3)), OSError("rete")]), \
                mock.patch("poi_wikidata.time.sleep"), redirect_stderr(io.StringIO()) as err:
            facts = poi_wikidata.fetch(set(range(1, 600)), time.monotonic() + 60)
        self.assertEqual(250, len(facts))
        self.assertIn("::warning::", err.getvalue())
        with mock.patch("poi_wikidata._post") as post, redirect_stderr(io.StringIO()):
            self.assertEqual({}, poi_wikidata.fetch({1, 2}, time.monotonic() - 1))
        post.assert_not_called()


class AnnotateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.tmp = Path(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def test_scrive_fama_e_patrimonio_in_tutti_i_pacchetti_e_li_salva_nella_cache(self):
        base = poi_db(self.tmp / "poi.db", [243, None, 243])
        extra = poi_db(self.tmp / "poi-extra.db", [10285])
        cache = self.tmp / "cache" / "poi-wikidata.json"
        today = date(2026, 10, 27)
        with mock.patch("poi_wikidata._post", return_value=answer(
                binding(243, 191, protected=True, unesco=True), binding(10285, 148, protected=True))) as post, \
                mock.patch("poi_wikidata.time.sleep"):
            self.assertEqual((3, 2, 0), poi_wikidata.annotate([base, extra], cache, today))
        post.assert_called_once()
        self.assertEqual([(243, 191, 2), (None, None, None), (243, 191, 2)], rows(base))
        self.assertEqual([(10285, 148, 1)], rows(extra))
        saved = json.loads(cache.read_text(encoding="utf-8"))
        self.assertEqual({"243": [191, 2, *PARIS, "2026-10-27"], "10285": [148, 1, *PARIS, "2026-10-27"]}, saved)

    def test_tag_che_non_indicano_il_luogo_si_tolgono(self):
        # 517: Napoleone, senza coordinate (la sua tomba ha un altro elemento); 90: un elemento a Lione, lontano dal POI;
        # 7: elemento cancellato; 8: a 15 km, come il punto di un parco grande, resta.
        base = poi_db(self.tmp / "poi.db", [517, 90, 7, 8])
        with mock.patch("poi_wikidata._post", return_value=answer(
                binding(517, 288, at=None), binding(90, 400, at=(45.76, 4.84)),
                binding(8, 12, at=(PARIS[0] + 0.135, PARIS[1])))), mock.patch("poi_wikidata.time.sleep"):
            self.assertEqual((1, 0, 3), poi_wikidata.annotate([base], None, date(2026, 10, 27)))
        self.assertEqual([(None, None, None), (None, None, None), (None, None, None), (8, 12, None)], rows(base))

    def test_la_cache_fresca_evita_la_query_quella_scaduta_no(self):
        base = poi_db(self.tmp / "poi.db", [1, 2])
        cache = self.tmp / "poi-wikidata.json"
        today = date(2026, 10, 27)
        old = (today - timedelta(days=poi_wikidata.CACHE_MAX_AGE_DAYS)).isoformat()
        cache.write_text(json.dumps({"1": [5, None, *PARIS, today.isoformat()], "2": [9, 1, *PARIS, old]}),
                         encoding="utf-8")
        with mock.patch("poi_wikidata._post", return_value=answer(binding(2, 10, protected=True))) as post, \
                mock.patch("poi_wikidata.time.sleep"):
            poi_wikidata.annotate([base], cache, today)
        self.assertIn("wd:Q2 }", post.call_args.args[0])
        self.assertNotIn("wd:Q1 ", post.call_args.args[0])
        self.assertEqual([(1, 5, None), (2, 10, 1)], rows(base))

    def test_elementi_non_chiesti_restano_come_sono(self):
        base = poi_db(self.tmp / "poi.db", [1])
        with mock.patch("poi_wikidata._post", side_effect=OSError("rete")), mock.patch("poi_wikidata.time.sleep"), \
                redirect_stderr(io.StringIO()):
            self.assertEqual((0, 0, 0), poi_wikidata.annotate([base], None, date(2026, 10, 27)))
        self.assertEqual([(1, None, None)], rows(base))

    def test_cache_illeggibile_o_nel_formato_vecchio_si_ignora(self):
        cache = self.tmp / "poi-wikidata.json"
        cache.write_text("{non json", encoding="utf-8")
        self.assertEqual({}, poi_wikidata._load_cache(cache, date(2026, 10, 27)))
        cache.write_text(json.dumps({"1": [5, None, "2026-10-27"], "2": "x", "3": [1, None, 1, 2, "ieri"]}),
                         encoding="utf-8")
        self.assertEqual({}, poi_wikidata._load_cache(cache, date(2026, 10, 27)))

    def test_main_salta_i_file_che_mancano_e_senza_file_esce_con_2(self):
        base = poi_db(self.tmp / "poi.db", [])
        with mock.patch("poi_wikidata._post") as post, mock.patch.dict("os.environ", {}, clear=True), \
                redirect_stderr(io.StringIO()), mock.patch("sys.stdout", io.StringIO()) as out:
            self.assertEqual(0, poi_wikidata.main(["x", str(base), str(self.tmp / "poi-extra.db")]))
            self.assertEqual(2, poi_wikidata.main(["x", str(self.tmp / "manca.db")]))
        post.assert_not_called()
        self.assertIn("0 POI con fama e patrimonio", out.getvalue())


if __name__ == "__main__":
    unittest.main()
