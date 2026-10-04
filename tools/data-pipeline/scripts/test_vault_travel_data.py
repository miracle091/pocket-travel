#!/usr/bin/env python3
"""Test di vault_travel_data.py sulla logica pura (filtri e ordine di aeroporti e compagnie, TSV compresso):
nessuna rete, le righe di esempio sono nel test.

Uso: python test_vault_travel_data.py
"""
import gzip
import tempfile
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
