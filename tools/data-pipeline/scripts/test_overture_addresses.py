#!/usr/bin/env python3
"""Test del controllo della lista bianca di overture_addresses.py (--check-whitelist) sulla logica
pura: confronto tra le coppie del rilascio e la lista, testo del report. Nessuna rete, nessun DuckDB
(sostituito da un modulo vuoto: la query su tutto il tema la copre solo una prova manuale).

Uso: python test_overture_addresses.py
"""
import io
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

sys.modules.setdefault("duckdb", types.ModuleType("duckdb"))
sys.path.insert(0, str(Path(__file__).parent))
import overture_addresses  # noqa: E402

compare_with_whitelist = overture_addresses.compare_with_whitelist
check_report = overture_addresses.check_report
load_reviewed = overture_addresses.load_reviewed


class LoadReviewedTest(unittest.TestCase):
    def test_tiene_ammesse_ed_escluse_e_salta_commenti(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "lista.tsv"
            path.write_text(
                "# commento\n"
                "# paese\tdataset\tlicenza\tdecisione\tattribuzione\n"
                "AT\tat/countrywide\tCC-BY-4.0\tallow\tBEV\n"
                "HK\thk/countrywide-en\tLicenseRef-Proprietary\texclude\tn/a\n"
                "\n",
                encoding="utf-8",
            )
            self.assertEqual(
                load_reviewed(path),
                {("AT", "at/countrywide", "CC-BY-4.0"), ("HK", "hk/countrywide-en", "LicenseRef-Proprietary")},
            )


class CompareTest(unittest.TestCase):
    reviewed = {("AT", "at/countrywide", "CC-BY-4.0"), ("HK", "hk/countrywide-en", "LicenseRef-Proprietary")}

    def test_niente_da_rivedere(self):
        pairs = [("AT", "at/countrywide", "CC-BY-4.0", 10), ("HK", "hk/countrywide-en", "LicenseRef-Proprietary", 5)]
        self.assertEqual(compare_with_whitelist(pairs, self.reviewed), ([], []))
        self.assertEqual(check_report("2026-09-23.1", [], []), "")

    def test_dataset_nuovo_ordinato_per_righe(self):
        pairs = [
            ("AT", "at/countrywide", "CC-BY-4.0", 10),
            ("HK", "hk/countrywide-en", "LicenseRef-Proprietary", 5),
            ("XX", "xx/piccolo", "CC0-1.0", 3),
            ("YY", "yy/grande", "CC0-1.0", 300),
        ]
        new, missing = compare_with_whitelist(pairs, self.reviewed)
        self.assertEqual(new, [("YY", "yy/grande", "CC0-1.0", 300), ("XX", "xx/piccolo", "CC0-1.0", 3)])
        self.assertEqual(missing, [])

    def test_licenza_cambiata_e_nuova_e_sparita(self):
        pairs = [
            ("AT", "at/countrywide", "CC-BY-SA-4.0", 10),
            ("HK", "hk/countrywide-en", "LicenseRef-Proprietary", 5),
        ]
        new, missing = compare_with_whitelist(pairs, self.reviewed)
        self.assertEqual(new, [("AT", "at/countrywide", "CC-BY-SA-4.0", 10)])
        self.assertEqual(missing, [("AT", "at/countrywide", "CC-BY-4.0")])
        report = check_report("2026-10-21.0", new, missing)
        self.assertIn("`2026-10-21.0`", report)
        self.assertIn("| AT | at/countrywide | CC-BY-SA-4.0 | 10 |", report)
        self.assertIn("| AT | at/countrywide | CC-BY-4.0 |", report)


class MergePairCountsTest(unittest.TestCase):
    def test_somma_le_stesse_coppie_di_file_diversi(self):
        totals = {}
        overture_addresses.merge_pair_counts(totals, [("AT", "at/countrywide", "CC-BY-4.0", 10), ("HK", "hk/x", "L", 1)])
        overture_addresses.merge_pair_counts(totals, [("AT", "at/countrywide", "CC-BY-4.0", 5)])
        self.assertEqual(totals, {("AT", "at/countrywide", "CC-BY-4.0"): 15, ("HK", "hk/x", "L"): 1})


class ExcludedAreasTest(unittest.TestCase):
    def write(self, text):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "lista.tsv"
        path.write_text(text, encoding="utf-8")
        return path

    def test_area_solo_sulle_fonti_ammesse(self):
        path = self.write(
            "US\tus/mn/statewide\tLicenseRef-Proprietary\tallow\tMnGeo\tPOLYGON((0 0, 1 0, 1 1, 0 0))\n"
            "US\tus/xx/altro\tCC0-1.0\tallow\tXX\n"
            "US\tus/yy/escluso\tCC0-1.0\texclude\tn/a\tPOLYGON((0 0, 1 0, 1 1, 0 0))\n"
        )
        areas = overture_addresses.load_excluded_areas(path)
        self.assertEqual(areas, [("us/mn/statewide", "LicenseRef-Proprietary", "POLYGON((0 0, 1 0, 1 1, 0 0))")])
        clause = overture_addresses.excluded_areas_clause(areas)
        self.assertIn("AND NOT (sources[1].dataset = 'us/mn/statewide'", clause)
        self.assertIn("ST_GeomFromText('POLYGON((0 0, 1 0, 1 1, 0 0))')", clause)
        self.assertEqual(overture_addresses.excluded_areas_clause([]), "")

    def test_area_non_wkt_rifiutata(self):
        path = self.write("US\tus/mn/statewide\tLicenseRef-Proprietary\tallow\tMnGeo\t-92,46,-90,48\n")
        with self.assertRaises(ValueError):
            overture_addresses.load_excluded_areas(path)


class FormatRowTest(unittest.TestCase):
    def test_riga_con_via_e_citta(self):
        self.assertEqual(
            overture_addresses.format_row(43.9424, 12.4578, "10", "sm/countrywide", "Via Roma", "Serravalle"),
            "43.9424\t12.4578\t10\tsm/countrywide\tVia Roma\tSerravalle\n",
        )

    def test_via_e_citta_mancanti_restano_colonne_vuote(self):
        self.assertEqual(
            overture_addresses.format_row(1.5, 2.5, "7", "x/y", None, None),
            "1.5\t2.5\t7\tx/y\t\t\n",
        )

    def test_tab_e_a_capo_nei_testi_diventano_spazi(self):
        row = overture_addresses.format_row(1.5, 2.5, "7", "x/y", "Via\tdei\nMille ", " Roma")
        self.assertEqual(row, "1.5\t2.5\t7\tx/y\tVia dei Mille\tRoma\n")
        self.assertEqual(len(row.rstrip("\n").split("\t")), 6)

    def test_senza_punto_o_numero_nessuna_riga(self):
        self.assertIsNone(overture_addresses.format_row(None, 2.5, "7", "x/y", "Via Roma", None))
        self.assertIsNone(overture_addresses.format_row(1.5, 2.5, None, "x/y", "Via Roma", None))


def write_file(test, text, name="lista.tsv"):
    tmp = tempfile.TemporaryDirectory()
    test.addCleanup(tmp.cleanup)
    path = Path(tmp.name) / name
    path.write_text(text, encoding="utf-8")
    return path


class LoadWhitelistTest(unittest.TestCase):
    def test_solo_le_righe_allow_complete(self):
        path = write_file(
            self,
            "# commento\n"
            "AT\tat/countrywide\tCC-BY-4.0\tallow\tBEV\n"
            "HK\thk/countrywide-en\tLicenseRef-Proprietary\texclude\tn/a\n"
            "NL\tnl/BAG Light\tCC0-1.0\tallow\t \n"      # nome con spazio, attribuzione vuota: ammessa
            "XX\tsolo\ttre\n"                              # meno di 4 colonne: scartata
            "\tvuoto\tCC0-1.0\tallow\tn/a\n"               # paese vuoto: scartata
            "YY\tyy/x\t\tallow\tn/a\n"                     # licenza vuota: scartata
            "  CH  \t ch/x \t CC0-1.0 \t allow \tSwisstopo\n"  # spazi ai bordi rimossi
            "\n",
        )
        self.assertEqual(overture_addresses.load_whitelist(path),
                         [("AT", "at/countrywide", "CC-BY-4.0"), ("NL", "nl/BAG Light", "CC0-1.0"), ("CH", "ch/x", "CC0-1.0")])

    def test_file_vuoto(self):
        self.assertEqual(overture_addresses.load_whitelist(write_file(self, "")), [])

    def test_accenti_nell_attribuzione_non_danno_errori(self):
        path = write_file(self, "IT\tit/città\tCC0-1.0\tallow\tComune di Forlì\n")
        self.assertEqual(overture_addresses.load_whitelist(path), [("IT", "it/città", "CC0-1.0")])


class LoadReviewedEdgeTest(unittest.TestCase):
    def test_righe_incomplete_ignorate(self):
        path = write_file(self, "AT\tat/x\tCC0-1.0\n"            # senza decisione
                                "AT\t\tCC0-1.0\tallow\n"          # dataset vuoto
                                "AT\tat/y\tCC0-1.0\texclude\n")
        self.assertEqual(overture_addresses.load_reviewed(path), {("AT", "at/y", "CC0-1.0")})


class SqlStringTest(unittest.TestCase):
    def test_apici_raddoppiati(self):
        self.assertEqual("'L''Aquila'", overture_addresses.sql_string("L'Aquila"))
        self.assertEqual("''", overture_addresses.sql_string(""))


class TsvFieldTest(unittest.TestCase):
    def test_valori_non_testuali_e_spazi_multipli(self):
        self.assertEqual("", overture_addresses.tsv_field(None))
        self.assertEqual("12", overture_addresses.tsv_field(12))
        self.assertEqual("a b", overture_addresses.tsv_field("  a \t\r\n b  "))
        self.assertEqual("Forlì", overture_addresses.tsv_field("Forlì"))


class CheckReportTest(unittest.TestCase):
    def test_solo_sparite(self):
        report = check_report("r1", [], [("AT", "at/x", "CC0-1.0")])
        self.assertNotIn("Coppie nuove", report)
        self.assertIn("Coppie della lista sparite dal rilascio (1)", report)
        self.assertTrue(report.endswith("\n"))

    def test_solo_nuove_senza_sezione_sparite(self):
        report = check_report("r1", [("AT", "at/x", "CC0-1.0", 4)], [])
        self.assertIn("Coppie nuove (1, mai riviste)", report)
        self.assertNotIn("sparite", report)

    def test_ordine_a_parita_di_righe_per_chiave(self):
        pairs = [("ZZ", "z", "L", 5), ("AA", "b", "L", 5), ("AA", "a", "L", 5)]
        new, _ = compare_with_whitelist(pairs, set())
        self.assertEqual([("AA", "a", "L", 5), ("AA", "b", "L", 5), ("ZZ", "z", "L", 5)], new)

    def test_lista_vuota_e_rilascio_vuoto(self):
        self.assertEqual(([], []), compare_with_whitelist([], set()))


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self):
        return self.body.encode("utf-8")


class LatestReleaseTest(unittest.TestCase):
    def listing(self, *names):
        return "<ListBucketResult>" + "".join(f"<Prefix>release/{n}/</Prefix>" for n in names) + "</ListBucketResult>"

    def test_il_suffisso_si_confronta_come_numero(self):
        body = self.listing("2026-09-23.9", "2026-09-23.10", "2026-08-01.0")
        with mock.patch("urllib.request.urlopen", return_value=FakeResponse(body)):
            self.assertEqual("2026-09-23.10", overture_addresses.latest_release())

    def test_la_data_vince_sul_suffisso(self):
        body = self.listing("2026-09-23.5", "2026-10-01.0")
        with mock.patch("urllib.request.urlopen", return_value=FakeResponse(body)):
            self.assertEqual("2026-10-01.0", overture_addresses.latest_release())

    def test_nomi_non_data_ignorati_e_elenco_vuoto_e_errore(self):
        with mock.patch("urllib.request.urlopen", return_value=FakeResponse(self.listing("latest", "bozza"))):
            with self.assertRaises(RuntimeError):
                overture_addresses.latest_release()


class FakeConnection:
    def __init__(self, rows):
        self.rows = rows
        self.queries = []

    def sql(self, query):
        self.queries.append(query)
        return mock.Mock(fetchall=lambda: self.rows)


class FetchAddressesTest(unittest.TestCase):
    def test_query_con_bbox_paesi_e_coppie_ammesse(self):
        connection = FakeConnection([(1.0, 2.0, "5", "d", "Via", "Città")])
        allowed = [("AT", "at/countrywide", "CC-BY-4.0"), ("AT", "at/l'altro", "CC0-1.0"), ("CH", "ch/x", "CC0-1.0")]
        with mock.patch.object(overture_addresses, "duckdb", mock.Mock(connect=lambda: connection)):
            rows = overture_addresses.fetch_addresses("2026-09-23.1", 9.5, 46.0, 10.5, 47.0, allowed)
        self.assertEqual([(1.0, 2.0, "5", "d", "Via", "Città")], rows)
        query = connection.queries[-1]
        self.assertIn("release/2026-09-23.1/theme=addresses", query)
        self.assertIn("bbox.xmin BETWEEN 9.5 AND 10.5", query)
        self.assertIn("bbox.ymin BETWEEN 46.0 AND 47.0", query)
        self.assertIn("country IN ('AT', 'CH')", query)   # paesi unici e ordinati
        self.assertIn("'at/l''altro|CC0-1.0'", query)
        self.assertNotIn("ST_Within", query)

    def test_aree_escluse_nella_query(self):
        connection = FakeConnection([])
        with mock.patch.object(overture_addresses, "duckdb", mock.Mock(connect=lambda: connection)):
            overture_addresses.fetch_addresses("r", 0, 0, 1, 1, [("US", "us/mn", "L")], [("us/mn", "L", "POLYGON((0 0,1 0,1 1,0 0))")])
        self.assertIn("ST_Within(geometry, ST_GeomFromText('POLYGON((0 0,1 0,1 1,0 0))'))", connection.queries[-1])


class FetchReleasePairsTest(unittest.TestCase):
    def test_somma_i_file_e_senza_file_e_un_errore(self):
        class Connection:
            def __init__(self):
                self.calls = 0

            def sql(self, query):
                if "glob(" in query:
                    return mock.Mock(fetchall=lambda: [("s3://b/a.parquet",), ("s3://b/b.parquet",)])
                if query.lstrip().startswith("SELECT country"):
                    return mock.Mock(fetchall=lambda: [("AT", "at/x", "CC0-1.0", 3)])
                return mock.Mock()

        messages = []
        with mock.patch.object(overture_addresses, "duckdb", mock.Mock(connect=Connection)):
            pairs = overture_addresses.fetch_release_pairs("r1", log=messages.append)
        self.assertEqual([("AT", "at/x", "CC0-1.0", 6)], pairs)
        self.assertEqual(3, len(messages))  # intestazione + una riga per file

        class Empty:
            def sql(self, query):
                return mock.Mock(fetchall=lambda: [])

        with mock.patch.object(overture_addresses, "duckdb", mock.Mock(connect=Empty)):
            with self.assertRaises(RuntimeError):
                overture_addresses.fetch_release_pairs("r1", log=lambda m: None)


class MainTest(unittest.TestCase):
    def run_main(self, *argv):
        with mock.patch.object(sys, "argv", ["overture_addresses.py", *argv]), \
                mock.patch.object(sys, "stderr", io.StringIO()), mock.patch.object(sys, "stdout", io.StringIO()) as out:
            overture_addresses.main()
        return out.getvalue()

    def test_print_release(self):
        with mock.patch.object(overture_addresses, "latest_release", return_value="2026-09-23.1"):
            self.assertEqual("2026-09-23.1\n", self.run_main("--print-release"))

    def test_lista_vuota_scrive_un_file_vuoto_senza_interrogare_overture(self):
        whitelist = write_file(self, "# solo commenti\n")
        output = whitelist.parent / "out.tsv"
        with mock.patch.object(overture_addresses, "fetch_addresses") as fetch:
            self.run_main("0", "0", "1", "1", str(whitelist), str(output))
        fetch.assert_not_called()
        self.assertEqual("", output.read_text(encoding="utf-8"))

    def test_scrive_le_righe_valide_e_salta_quelle_senza_numero(self):
        whitelist = write_file(self, "AT\tat/x\tCC0-1.0\tallow\tBEV\n")
        output = whitelist.parent / "out.tsv"
        rows = [(47.5, 9.7, "3a", "at/x", "Straße\tdes\nFriedens", "Wien"), (None, 9.7, "4", "at/x", "Via", None),
                (47.6, 9.8, None, "at/x", "Via", None)]
        with mock.patch.object(overture_addresses, "fetch_addresses", return_value=rows) as fetch:
            self.run_main("9", "47", "10", "48", str(whitelist), str(output), "--release", "2026-09-23.1")
        self.assertEqual("47.5\t9.7\t3a\tat/x\tStraße des Friedens\tWien\n", output.read_text(encoding="utf-8"))
        self.assertEqual(("2026-09-23.1", 9.0, 47.0, 10.0, 48.0), fetch.call_args.args[:5])

    def test_release_latest_viene_risolta(self):
        whitelist = write_file(self, "AT\tat/x\tCC0-1.0\tallow\tBEV\n")
        with mock.patch.object(overture_addresses, "latest_release", return_value="R") as latest, \
                mock.patch.object(overture_addresses, "fetch_addresses", return_value=[]) as fetch:
            self.run_main("0", "0", "1", "1", str(whitelist), str(whitelist.parent / "o.tsv"))
        latest.assert_called_once()
        self.assertEqual("R", fetch.call_args.args[0])

    def test_check_whitelist_scrive_il_report(self):
        whitelist = write_file(self, "AT\tat/x\tCC0-1.0\tallow\tBEV\n")
        report = whitelist.parent / "report.md"
        pairs = [("AT", "at/x", "CC0-1.0", 1), ("DE", "de/y", "CC0-1.0", 9)]
        with mock.patch.object(overture_addresses, "fetch_release_pairs", return_value=pairs):
            self.run_main("--check-whitelist", str(whitelist), str(report), "--release", "R1")
        text = report.read_text(encoding="utf-8")
        self.assertIn("`R1`", text)
        self.assertIn("| DE | de/y | CC0-1.0 | 9 |", text)

    def test_check_whitelist_senza_novita_lascia_il_file_vuoto(self):
        whitelist = write_file(self, "AT\tat/x\tCC0-1.0\tallow\tBEV\n")
        report = whitelist.parent / "report.md"
        with mock.patch.object(overture_addresses, "fetch_release_pairs", return_value=[("AT", "at/x", "CC0-1.0", 1)]):
            self.run_main("--check-whitelist", str(whitelist), str(report), "--release", "R1")
        self.assertEqual("", report.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
