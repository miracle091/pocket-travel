#!/usr/bin/env python3
"""Test di vault_travel_data.py sulla logica pura (filtri e ordine di aeroporti e compagnie, TSV compresso):
nessuna rete, le righe di esempio sono nel test.

Uso: python test_vault_travel_data.py
"""
import gzip
import io
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import vault_travel_data as v

INTESTAZIONE = "id,ident,type,name,municipality,iso_country,icao_code,iata_code\n"
CSV = INTESTAZIONE + "\n".join([
    '1,LIRF,large_airport,"Rome–Fiumicino",Rome,IT,LIRF,FCO',
    "2,LIRA,medium_airport,Ciampino,Rome,IT,LIRA,CIA",
    "3,XX01,heliport,Eliporto,Roma,IT,,HEL",
    "4,YYYY,closed,Chiuso,Altrove,IT,,CLO",
    "5,ZZ02,small_airport,Privato,Altrove,IT,,",
    "6,KJFK,small_airport,Doppione,New York,US,KJFK,FCO",
    "7,KJFK,large_airport,John F. Kennedy,New York,US,KJFK,JFK",
    "8,ABCD,seaplane_base,Idrobase,Vancouver,CA,,YHC",
    '9,LIMC,large_airport,"Tab\tnel nome",Milano,IT,LIMC,mxp',
]) + "\n"


class ParseAirportsTest(unittest.TestCase):
    def test_tiene_solo_aeroporti_con_iata_aperti_e_non_eliporti(self):
        codici = [r[0] for r in v.parse_airports(CSV)]
        self.assertEqual(["FCO", "JFK", "MXP", "CIA", "YHC"], codici)

    def test_codice_ripetuto_vince_il_tipo_piu_grande(self):
        fco = next(r for r in v.parse_airports(CSV) if r[0] == "FCO")
        self.assertEqual("Rome–Fiumicino", fco[2])

    def test_campi_e_pulizia(self):
        righe = {r[0]: r for r in v.parse_airports(CSV)}
        self.assertEqual(("FCO", "LIRF", "Rome–Fiumicino", "Rome", "IT", ""), righe["FCO"])
        self.assertEqual("Tab nel nome", righe["MXP"][2])
        self.assertEqual("ABCD", righe["YHC"][1])  # senza icao_code vale l'ident


class ParseCitiesItTest(unittest.TestCase):
    @staticmethod
    def binding(iata, city):
        return {"iata": {"value": iata}, "city": {"value": city}}

    def test_nomi_unici_i_piu_corti_per_primi_al_massimo_due(self):
        citta = v.parse_cities_it([
            self.binding("MXP", "Lombardia"), self.binding("MXP", "Milano"),
            self.binding("MXP", "Varese"), self.binding("MXP", "Milano"),
            self.binding("FCO", "Roma"), self.binding("XXX", ""),
        ])
        self.assertEqual({"MXP": ["Milano", "Varese"], "FCO": ["Roma"]}, citta)

    def test_aeroporti_con_citta_italiana_solo_se_diversa_dal_comune(self):
        csv_text = INTESTAZIONE + "\n".join([
            '1,LIRF,large_airport,"Rome–Fiumicino",Rome,IT,LIRF,FCO',
            "2,EGLL,large_airport,Heathrow,London,GB,EGLL,LHR",
            "3,KJFK,large_airport,Kennedy,New York,US,KJFK,JFK",
        ]) + "\n"
        righe = {r[0]: r for r in v.parse_airports(csv_text, {"FCO": ["Roma"], "LHR": ["Londra"], "JFK": ["new york"]})}
        self.assertEqual("Roma", righe["FCO"][5])
        self.assertEqual("Londra", righe["LHR"][5])
        self.assertEqual("", righe["JFK"][5])


class ParseAirlinesTest(unittest.TestCase):
    @staticmethod
    def binding(iata=None, icao=None, nome="X"):
        b = {"name": {"value": nome}}
        if iata:
            b["iata"] = {"value": iata}
        if icao:
            b["icao"] = {"value": icao}
        return b

    def test_unisce_duplicati_e_ordina_per_nome(self):
        righe = v.parse_airlines([
            self.binding("FR", "RYR", "Ryanair"),
            self.binding("AZ", "ITY", "ITA Airways"),
            self.binding("FR", "RYR", "Ryanair"),
        ])
        self.assertEqual([("AZ", "ITY", "ITA Airways"), ("FR", "RYR", "Ryanair")], righe)

    def test_richiede_un_codice_valido_e_un_nome(self):
        righe = v.parse_airlines([
            self.binding(nome="Senza codici"),
            self.binding("TOOLONG", None, "Codice errato"),
            self.binding(None, "ABC", "Solo ICAO"),
            self.binding("ZZ", "ZZZ", ""),
        ])
        self.assertEqual([("", "ABC", "Solo ICAO")], righe)


class ScriviTsvTest(unittest.TestCase):
    def test_file_riproducibile_e_leggibile(self):
        righe = [("FCO", "LIRF", "Rome–Fiumicino", "Rome", "IT", "Roma"), ("JFK", "", "Kennedy", "", "US", "")]
        with tempfile.TemporaryDirectory() as d:
            a, b = Path(d, "a.tsv.gz"), Path(d, "b.tsv.gz")
            v.scrivi_tsv_gz(a, righe)
            v.scrivi_tsv_gz(b, righe)
            self.assertEqual(a.read_bytes(), b.read_bytes())
            testo = gzip.open(a, "rt", encoding="utf-8").read()
        self.assertEqual("FCO\tLIRF\tRome–Fiumicino\tRome\tIT\tRoma\nJFK\t\tKennedy\t\tUS\t\n", testo)


class ParseAirportsEdgeTest(unittest.TestCase):
    def test_csv_vuoto_o_solo_intestazione(self):
        self.assertEqual([], v.parse_airports(""))
        self.assertEqual([], v.parse_airports(INTESTAZIONE))

    def test_iata_non_valido_scartato(self):
        csv_text = INTESTAZIONE + "\n".join([
            "1,AAAA,small_airport,Lungo,X,IT,AAAA,ABCD",   # quattro caratteri
            "2,BBBB,small_airport,Corto,X,IT,BBBB,A",      # un carattere
            "3,CCCC,small_airport,Simboli,X,IT,CCCC,A-B",
            "4,DDDD,small_airport,Buono,X,IT,DDDD,d1",     # minuscolo: viene portato in maiuscolo
        ]) + "\n"
        self.assertEqual(["D1"], [r[0] for r in v.parse_airports(csv_text)])

    def test_icao_non_valido_lascia_il_campo_vuoto_e_senza_nome_la_riga_salta(self):
        csv_text = INTESTAZIONE + "\n".join([
            "1,TOOLONG1,small_airport,Pista,X,IT,TOOLONG1,PST",
            "2,NONAME,small_airport,,X,IT,NONA,NNM",
        ]) + "\n"
        self.assertEqual([("PST", "", "Pista", "X", "IT", "")], v.parse_airports(csv_text))

    def test_stesso_tipo_ordinato_per_codice_iata_e_accenti_conservati(self):
        csv_text = INTESTAZIONE + "\n".join([
            "1,LTFM,large_airport,İstanbul,İstanbul,TR,LTFM,IST",
            "2,LFPG,large_airport,Paris–Charles de Gaulle,Paris,FR,LFPG,CDG",
        ]) + "\n"
        righe = v.parse_airports(csv_text)
        self.assertEqual(["CDG", "IST"], [r[0] for r in righe])
        self.assertEqual("İstanbul", righe[1][2])

    def test_citta_italiana_uguale_al_comune_senza_distinguere_maiuscole(self):
        csv_text = INTESTAZIONE + "1,LIMC,large_airport,Malpensa,MILANO,IT,LIMC,MXP\n"
        righe = v.parse_airports(csv_text, {"MXP": ["Milano", "Varese"]})
        self.assertEqual("Varese", righe[0][5])

    def test_piu_citta_italiane_separate_da_barra(self):
        csv_text = INTESTAZIONE + "1,LFSB,large_airport,Basilea,Mulhouse,FR,LFSB,BSL\n"
        self.assertEqual("Basilea / Mulhouse-X", v.parse_airports(csv_text, {"BSL": ["Basilea", "Mulhouse-X"]})[0][5])


class ParseCitiesItEdgeTest(unittest.TestCase):
    def test_iata_in_minuscolo_e_spazi(self):
        binding = lambda i, c: {"iata": {"value": i}, "city": {"value": c}}
        self.assertEqual({"FCO": ["Roma"]}, v.parse_cities_it([binding(" fco ", " Roma\n")]))

    def test_binding_senza_campi_ignorato(self):
        self.assertEqual({}, v.parse_cities_it([{}, {"iata": {"value": "FCO"}}]))


class ParseAirlinesEdgeTest(unittest.TestCase):
    def test_ordine_senza_distinguere_maiuscole_e_codici_in_maiuscolo(self):
        b = lambda iata, icao, nome: {"iata": {"value": iata}, "icao": {"value": icao}, "name": {"value": nome}}
        righe = v.parse_airlines([b("zz", "zzz", "zeta air"), b("AA", "AAL", "American"), b("bb", "bbb", "Beta")])
        self.assertEqual(["American", "Beta", "zeta air"], [r[2] for r in righe])
        self.assertEqual(("ZZ", "ZZZ", "zeta air"), righe[2])

    def test_codice_non_valido_ma_icao_valido_tiene_solo_l_icao(self):
        righe = v.parse_airlines([{"iata": {"value": "TOOLONG"}, "icao": {"value": "ABC"}, "name": {"value": "Mista"}}])
        self.assertEqual([("", "ABC", "Mista")], righe)

    def test_elenco_vuoto(self):
        self.assertEqual([], v.parse_airlines([]))


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self):
        return self.body.encode("utf-8")


class ScaricaTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch("time.sleep")
        self.sleep = patcher.start()
        self.addCleanup(patcher.stop)
        err = mock.patch.object(sys, "stderr", io.StringIO())
        err.start()
        self.addCleanup(err.stop)

    def test_riprova_con_attesa_crescente(self):
        responses = [urllib.error.URLError("rete"), TimeoutError("lento"), FakeResponse("ciao è")]
        with mock.patch("urllib.request.urlopen", side_effect=responses):
            self.assertEqual("ciao è", v.scarica("https://example.org/x", "ua"))
        self.assertEqual([mock.call(10), mock.call(20)], self.sleep.call_args_list)

    def test_dopo_tutti_i_tentativi_esce_con_1(self):
        with mock.patch("urllib.request.urlopen", side_effect=urllib.error.URLError("giu")) as urlopen:
            with self.assertRaises(SystemExit) as ctx:
                v.scarica("https://example.org/x", "ua", tentativi=2)
        self.assertEqual(1, ctx.exception.code)
        self.assertEqual(2, urlopen.call_count)

    def test_user_agent_inviato(self):
        with mock.patch("urllib.request.urlopen", return_value=FakeResponse("x")) as urlopen:
            v.scarica("https://example.org/x", "UA-di-prova")
        self.assertEqual("UA-di-prova", urlopen.call_args.args[0].get_header("User-agent"))

    def test_sparql_legge_i_binding_anche_con_caratteri_di_controllo(self):
        body = '{"results": {"bindings": [{"name": {"value": "Air\tOne"}}]}}'
        with mock.patch.object(v, "scarica", return_value=body) as scarica:
            self.assertEqual([{"name": {"value": "Air\tOne"}}], v.sparql("SELECT 1", "ua"))
        self.assertIn("format=json", scarica.call_args.args[0])
        self.assertIn("query=SELECT+1", scarica.call_args.args[0])


class MainTest(unittest.TestCase):
    def run_main(self, out_dir, airports, airlines):
        argv = ["vault_travel_data.py", "--out-dir", str(out_dir)]
        with mock.patch.object(sys, "argv", argv), mock.patch.object(sys, "stdout", io.StringIO()) as out, \
                mock.patch.object(sys, "stderr", io.StringIO()), \
                mock.patch.object(v, "MIN_AIRPORTS", 1), mock.patch.object(v, "MIN_AIRLINES", 1), \
                mock.patch.object(v, "scarica", return_value=airports), \
                mock.patch.object(v, "sparql", return_value=airlines):
            code = v.main()
        return code, out.getvalue()

    def test_scrive_i_due_file_e_riassume(self):
        airlines = [{"iata": {"value": "AZ"}, "icao": {"value": "ITY"}, "name": {"value": "ITA Airways"}}]
        with tempfile.TemporaryDirectory() as d:
            code, text = self.run_main(Path(d) / "assets", CSV, airlines)
            self.assertEqual(0, code)
            self.assertTrue((Path(d) / "assets" / "airports.tsv.gz").exists())
            righe = gzip.open(Path(d) / "assets" / "airlines.tsv.gz", "rt", encoding="utf-8").read()
        self.assertEqual("AZ\tITY\tITA Airways\n", righe)
        self.assertIn("5 aeroporti", text)
        self.assertIn("1 compagnie", text)

    def test_risultato_troppo_piccolo_non_scrive_nulla(self):
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.object(sys, "argv", ["vault_travel_data.py", "--out-dir", str(Path(d) / "assets")]), \
                    mock.patch.object(sys, "stderr", io.StringIO()), \
                    mock.patch.object(v, "scarica", return_value=CSV), mock.patch.object(v, "sparql", return_value=[]):
                self.assertEqual(1, v.main())
            self.assertFalse((Path(d) / "assets").exists())


if __name__ == "__main__":
    unittest.main()
