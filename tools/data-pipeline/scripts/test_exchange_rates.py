#!/usr/bin/env python3
"""Test di exchange_rates.py: solo valute, righe non valide scartate, CUP dalla Banca Centrale di Cuba, minimo di valute.

Uso: python test_exchange_rates.py
"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import exchange_rates
from exchange_rates import exchange_rates as build

HERE = Path(__file__).resolve().parent

CURRENCIES = [
    {"iso_code": "USD", "type": "currency"},
    {"iso_code": "THB", "type": "currency"},
    {"iso_code": "CUP", "type": "currency"},
    {"iso_code": "BTC", "type": "crypto"},
    {"iso_code": "XAU", "type": "metal"},
]


def row(quote, rate, date="2026-11-11", base="EUR"):
    return {"date": date, "base": base, "quote": quote, "rate": rate}


class ExchangeRatesTest(unittest.TestCase):
    def test_solo_le_valute_con_la_loro_data(self):
        rates = [row("USD", 1.12), row("THB", 37.5, "2026-11-10"), row("BTC", 0.00001), row("XAU", 0.0003)]
        self.assertEqual(
            {"base": "EUR", "rates": {"THB": {"rate": 37.5, "date": "2026-11-10"}, "USD": {"rate": 1.12, "date": "2026-11-11"}}},
            build(rates, [], CURRENCIES),
        )

    def test_righe_non_valide_scartate(self):
        rates = [row("USD", 0), row("USD", -1), row("USD", float("inf")), row("USD", True), row("USD", "1.1"),
                 row("USD", 1.1, "11/11/2026"), row("USD", 1.1, base="USD"), row("usd", 1.1), row("EUR", 1)]
        self.assertEqual({}, build(rates, [], CURRENCIES + [{"iso_code": "EUR", "type": "currency"}])["rates"])

    def test_cup_dalla_banca_centrale_di_cuba(self):
        rates = [row("CUP", 26.9)]
        self.assertEqual({"rate": 908.85, "date": "2026-11-11"}, build(rates, [row("CUP", 908.85)], CURRENCIES)["rates"]["CUP"])

    def test_cup_senza_tasso_della_banca_centrale_resta_fuori(self):
        self.assertNotIn("CUP", build([row("CUP", 26.9)], [], CURRENCIES)["rates"])
        self.assertNotIn("CUP", build([row("CUP", 26.9)], {"status": 404}, CURRENCIES)["rates"])


class MainTest(unittest.TestCase):
    def run_script(self, rates, bcc, currencies):
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for name, data in [("rates.json", rates), ("bcc.json", bcc), ("currencies.json", currencies)]:
                path = Path(d) / name
                if data is not None:
                    path.write_text(json.dumps(data), encoding="utf-8")
                paths.append(str(path))
            out = Path(d) / "out.json"
            result = subprocess.run([sys.executable, "-W", "always::ResourceWarning", str(HERE / "exchange_rates.py"), *paths, str(out)],
                                    capture_output=True, text=True, encoding="utf-8")
            return result, json.loads(out.read_text(encoding="utf-8")) if out.exists() else None

    @staticmethod
    def many(count):
        codes = [f"{chr(65 + i // 26)}{chr(65 + i % 26)}Z" for i in range(count)]
        return [row(code, 1.0 + i) for i, code in enumerate(codes)], [{"iso_code": c, "type": "currency"} for c in codes]

    def test_scrive_il_file(self):
        rates, currencies = self.many(exchange_rates.MIN_CURRENCIES)
        result, written = self.run_script(rates, None, currencies)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("ResourceWarning", result.stderr)
        self.assertEqual(exchange_rates.MIN_CURRENCIES, len(written["rates"]))
        self.assertEqual("EUR", written["base"])

    def test_troppo_poche_valute_non_scrive(self):
        rates, currencies = self.many(exchange_rates.MIN_CURRENCIES - 1)
        result, written = self.run_script(rates, [], currencies)
        self.assertEqual(1, result.returncode)
        self.assertIsNone(written)

    def test_risposta_non_elenco_non_scrive(self):
        result, written = self.run_script({"status": 500}, [], CURRENCIES)
        self.assertEqual(1, result.returncode)
        self.assertIsNone(written)


if __name__ == "__main__":
    unittest.main()
