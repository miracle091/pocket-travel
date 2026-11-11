#!/usr/bin/env python3
"""Test di vault_travel_data.py sulla logica pura (filtri e ordine di aeroporti e compagnie, TSV compresso):
nessuna rete, le righe di esempio sono nel test.

Uso: python test_vault_travel_data.py
"""
import gzip
import hashlib
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


COLONNE_POR = ("iata_code^icao_code^faa_code^is_geonames^geoname_id^envelope_id^name^asciiname^latitude^longitude^"
               "fclass^fcode^page_rank^date_from^date_until^comment^country_code^cc2^country_name^continent_name^"
               "adm1_code^adm1_name_utf^adm1_name_ascii^adm2_code^adm2_name_utf^adm2_name_ascii^adm3_code^adm4_code^"
               "population^elevation^gtopo30^timezone^gmt_offset^dst_offset^raw_offset^moddate^city_code_list^"
               "city_name_list^city_detail_list^tvl_por_list^iso31662^location_type^wiki_link^alt_name_section^wac^"
               "wac_name^ccy_code^unlc_list^uic_list^geoname_lat^geoname_lon").split("^")


def por(*righe):
    """optd_por_public.csv con le righe date come dizionari (le colonne mancanti restano vuote)."""
    base = {"page_rank": "0.5", "wac": "123", "unlc_list": "ITROM|", "uic_list": "8300263|", "continent_name": "Europe"}
    return "^".join(COLONNE_POR) + "\n" + "".join(
        "^".join({**base, **r}.get(c, "") for c in COLONNE_POR) + "\n" for r in righe)


POR = por(
    {"iata_code": "ROM", "name": "Rome", "location_type": "C", "country_code": "IT", "city_code_list": "ROM",
     "alt_name_section": "en|Rome|p=it|Roma|="},
    {"iata_code": "FCO", "icao_code": "LIRF", "name": "Fiumicino", "location_type": "A", "country_code": "IT",
     "city_code_list": "ROM"},
    {"iata_code": "CIA", "icao_code": "LIRA", "name": "Ciampino", "location_type": "A", "country_code": "IT",
     "city_code_list": "ROM"},
    {"iata_code": "XRJ", "name": "Roma Termini", "location_type": "R", "country_code": "IT", "city_code_list": "ROM"},
    {"iata_code": "ZRH", "icao_code": "LSZH", "name": "Zurich", "location_type": "A", "country_code": "CH",
     "city_code_list": "ZRH"},
    {"iata_code": "ZRH", "name": "Zurich", "location_type": "C", "country_code": "CH", "city_code_list": "ZRH",
     "alt_name_section": "it|Zurigo|=it|Zurigo città|p="},
    # Codice storico riassegnato: AAW era un aeroporto, oggi e' una stazione.
    {"iata_code": "AAW", "icao_code": "OPAW", "name": "Abbottabad", "location_type": "A", "country_code": "PK",
     "date_until": "2010-01-01", "continent_name": "Asia"},
    {"iata_code": "AAW", "name": "Aachen West", "location_type": "R", "country_code": "DE", "city_code_list": "AAH"},
    {"iata_code": "OLD", "name": "Vecchio", "location_type": "A", "envelope_id": "1", "city_code_list": "ROM"},
    {"iata_code": "QLS", "icao_code": "LSGL", "name": "Lausanne", "location_type": "R", "country_code": "CH"},
    {"iata_code": "XBU", "name": "Autostazione", "location_type": "B", "country_code": "IT", "city_code_list": "XXX"},
    {"iata_code": "NYP", "name": "Penn Station", "location_type": "R", "country_code": "US",
     "continent_name": "North America"},
)


AIRLINES_OPTD = "pk^env_id^validity_from^validity_to^3char_code^2char_code^name\na^^2015-01-01^^NKS^NK^Spirit Airlines\n"


class OptdTest(unittest.TestCase):
    def test_nome_italiano_preferito_altrimenti_il_primo(self):
        self.assertEqual("Roma", v.nome_italiano("en|Rome|p=it|Roma|=it|Urbe|="))
        self.assertEqual("Zurigo città", v.nome_italiano("it|Zurigo|=it|Zurigo città|p="))
        self.assertEqual("Zurigo città", v.nome_italiano("it|Zurigo|=it|Zurigo città|ps="))
        self.assertEqual("", v.nome_italiano("en|Rome|p=|Roma|="))
        self.assertEqual("", v.nome_italiano(""))

    def test_solo_righe_valide_e_codici_riassegnati_scartati(self):
        righe = v.parse_optd_por(POR)
        codici = [(r["iata_code"], r["location_type"]) for r in righe]
        self.assertNotIn(("OLD", "A"), codici)
        self.assertEqual([("AAW", "R")], [c for c in codici if c[0] == "AAW"])
        self.assertEqual("Aachen West", next(r for r in righe if r["iata_code"] == "AAW")["name"])

    def test_nessuna_colonna_esclusa(self):
        chiavi = set(v.parse_optd_por(POR)[0])
        self.assertEqual(set(v.COLONNE_OPTD) - {"alt_name_section"} | {"it"}, chiavi)
        self.assertFalse(chiavi & {"page_rank", "wac", "unlc_list", "uic_list"})
        self.assertEqual("Roma", v.parse_optd_por(POR)[0]["it"])

    def test_citta_italiana_dell_aeroporto_da_optd(self):
        self.assertEqual({"FCO": ["Roma"], "CIA": ["Roma"], "ZRH": ["Zurigo città"]},
                         v.optd_cities_it(v.parse_optd_por(POR)))

    def test_citta_con_piu_aeroporti_fra_quelli_tenuti(self):
        aeroporti = [("FCO", "LIRF", "Fiumicino", "Rome", "IT", "Roma"), ("CIA", "LIRA", "Ciampino", "Rome", "IT", "Roma"),
                     ("ZRH", "LSZH", "Zurich", "Zurich", "CH", "")]
        self.assertEqual([("ROM", "", "Rome", "Rome", "IT", "Roma", "CIA,FCO")],
                         v.optd_city_rows(v.parse_optd_por(POR), aeroporti))
        self.assertEqual([], v.optd_city_rows(v.parse_optd_por(POR), aeroporti[:1]))

    def test_stazioni_d_europa_senza_icao_ne_codici_gia_usati(self):
        righe = v.optd_station_rows(v.parse_optd_por(POR), {"AAW"})
        self.assertEqual([("XBU", "", "Autostazione", "", "IT", ""), ("XRJ", "", "Roma Termini", "Rome", "IT", "Roma")],
                         righe)

    def test_compagnie_valide_solo_con_iata_nuovo(self):
        testo = "\n".join([
            "pk^env_id^validity_from^validity_to^3char_code^2char_code^num_code^name^flt_freq",
            "a^^2015-01-01^^NKS^NK^487^Spirit Airlines^999",
            "b^^2015-01-01^^AZA^AZ^055^Alitalia^1",
            "c^^2001-01-01^2020-01-01^OLD^ZZ^^Chiusa^",
            "d^1^2001-01-01^^ENV^ZY^^Storica^",
            "e^^2015-01-01^^^*O^^Alleanza^",
            "f^^2015-01-01^^TOOLONG^KE^^Korean Air^",
        ]) + "\n"
        self.assertEqual({("NK", "NKS", "Spirit Airlines"), ("KE", "", "Korean Air")}, v.parse_optd_airlines(testo, {"AZ"}))

    def test_sistemi_di_prenotazione_tolti(self):
        testo = "\n".join([
            "pk^env_id^validity_from^validity_to^3char_code^2char_code^num_code^name^type",
            "a^^1987-01-01^^AGT^1A^790^Amadeus^G",
            "b^^^^^1S^0^Sabre^G",
            "c^^2015-01-01^^NKS^NK^487^Spirit Airlines^P",
            "d^^2015-01-01^^KEA^KE^180^Korean Air^PC",
        ]) + "\n"
        self.assertEqual({("NK", "NKS", "Spirit Airlines"), ("KE", "KEA", "Korean Air")},
                         v.parse_optd_airlines(testo, set()))

    def test_compagnie_chiuse_tolte_con_nome_e_codice(self):
        testo = "\n".join([
            "pk^env_id^validity_from^validity_to^3char_code^2char_code^num_code^name^flt_freq",
            "a^^2015-01-01^^JAI^9W^^Jet Airways^",
            "b^^2015-01-01^^NMB^XX^^Air Namibia^",
            "c^^2015-01-01^^AZU^AD^^Azul Brazilian Airlines^",
            "d^^2015-01-01^^SEH^GQ^^Sky Express^",
        ]) + "\n"
        chiuse = v.parse_dissolved_airlines([
            {"iata": {"value": "9W"}, "icao": {"value": "JAI"}, "name": {"value": "Jet Airways"}},
            {"icao": {"value": "NMB"}, "name": {"value": "Air Namibia"}},
            {"iata": {"value": "AD"}, "name": {"value": "Air Paradise"}},
            {"iata": {"value": "G3"}, "name": {"value": "Sky Express"}},
        ])
        # Jet Airways per IATA, Air Namibia per ICAO; restano AD riassegnato e Sky Express omonima con un altro codice.
        self.assertEqual({("AD", "AZU", "Azul Brazilian Airlines"), ("GQ", "SEH", "Sky Express")},
                         v.parse_optd_airlines(testo, set(), chiuse))

    def test_impronta_controllata_e_url_al_commit_fisso(self):
        with mock.patch.object(v, "scarica", return_value="dati") as scarica, \
                mock.patch.object(sys, "stderr", io.StringIO()):
            self.assertEqual("dati", v.scarica_optd(("x.csv", hashlib.sha256(b"dati").hexdigest()), "ua"))
            with self.assertRaises(SystemExit) as ctx:
                v.scarica_optd(("x.csv", "0" * 64), "ua")
        self.assertEqual(1, ctx.exception.code)
        self.assertRegex(scarica.call_args.args[0], r"/[0-9a-f]{40}/opentraveldata/x\.csv$")


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

    def test_sparql_riprova_il_json_troncato(self):
        risposte = ['{"results": {"bindings": [{"a"', '{"results": {"bindings": [{"a": 1}]}}']
        with mock.patch.object(v, "scarica", side_effect=risposte):
            self.assertEqual([{"a": 1}], v.sparql("SELECT", "ua"))
        self.assertEqual([mock.call(30)], self.sleep.call_args_list)
        with mock.patch.object(v, "scarica", return_value='{"results"'), self.assertRaises(SystemExit):
            v.sparql("SELECT", "ua", tentativi=2)

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
                mock.patch.object(v, "scarica_optd", side_effect=lambda f, ua: POR if f == v.OPTD_POR else AIRLINES_OPTD), \
                mock.patch.object(v, "sparql", return_value=airlines):
            code = v.main()
        return code, out.getvalue()

    def test_scrive_i_due_file_e_riassume(self):
        airlines = [{"iata": {"value": "AZ"}, "icao": {"value": "ITY"}, "name": {"value": "ITA Airways"}}]
        with tempfile.TemporaryDirectory() as d:
            code, text = self.run_main(Path(d) / "assets", CSV, airlines)
            self.assertEqual(0, code)
            aeroporti = gzip.open(Path(d) / "assets" / "airports.tsv.gz", "rt", encoding="utf-8").read().splitlines()
            righe = gzip.open(Path(d) / "assets" / "airlines.tsv.gz", "rt", encoding="utf-8").read()
        self.assertEqual("AZ\tITY\tITA Airways\nNK\tNKS\tSpirit Airlines\n", righe)
        # Prima le citta', poi gli aeroporti, poi le stazioni.
        self.assertEqual("ROM\t\tRome\tRome\tIT\tRoma\tCIA,FCO", aeroporti[0])
        self.assertEqual(["ROM", "FCO", "JFK", "MXP", "CIA", "YHC", "AAW", "XBU", "XRJ"], [r.split("\t")[0] for r in aeroporti])
        self.assertIn("5 aeroporti", text)
        self.assertIn("1 citta', 3 stazioni, 2 compagnie", text)

    def test_risultato_troppo_piccolo_non_scrive_nulla(self):
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.object(sys, "argv", ["vault_travel_data.py", "--out-dir", str(Path(d) / "assets")]), \
                    mock.patch.object(sys, "stderr", io.StringIO()), \
                    mock.patch.object(v, "scarica", return_value=CSV), mock.patch.object(v, "sparql", return_value=[]), \
                    mock.patch.object(v, "scarica_optd", return_value=POR):
                self.assertEqual(1, v.main())
            self.assertFalse((Path(d) / "assets").exists())


if __name__ == "__main__":
    unittest.main()
