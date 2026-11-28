#!/usr/bin/env python3
"""Test di wiki_http.py: nuovi tentativi, Retry-After, tetto dell'attesa e scadenza. Nessuna rete: urlopen, sleep e
il caso sono sostituiti con unittest.mock.

Uso: python test_wiki_http.py
"""
import email.message
import http.client
import io
import json
import time
import unittest
import urllib.error
from contextlib import redirect_stderr
from unittest import mock

import wiki_http


class GetJsonTest(unittest.TestCase):
    def setUp(self):
        jitter = mock.patch("wiki_http.random.uniform", return_value=0)
        jitter.start()
        self.addCleanup(jitter.stop)
        sleep = mock.patch("wiki_http.time.sleep")
        self.sleep = sleep.start()
        self.addCleanup(sleep.stop)
        err = redirect_stderr(io.StringIO())
        self.err = err.__enter__()
        self.addCleanup(err.__exit__, None, None, None)

    def _response(self, payload):
        r = mock.MagicMock()
        r.__enter__.return_value.read.return_value = json.dumps(payload).encode("utf-8")
        return r

    def _http_error(self, code, retry_after=None):
        headers = email.message.Message()
        if retry_after is not None:
            headers["Retry-After"] = retry_after
        error = urllib.error.HTTPError("http://x", code, "x", headers, None)
        # Da Python 3.14 un HTTPError mai chiuso da' un ResourceWarning, che il controllo dei test conta come errore.
        self.addCleanup(error.close)
        return error

    def _urlopen(self, side_effect):
        return mock.patch("wiki_http.urllib.request.urlopen", side_effect=side_effect)

    def test_riprova_dopo_un_errore_di_rete_e_poi_riesce(self):
        with self._urlopen([OSError("rete"), self._response({"a": 1})]):
            self.assertEqual({"a": 1}, wiki_http.get_json("http://x"))
        self.sleep.assert_called_once_with(10)

    def test_attese_che_raddoppiano_e_poi_rilancia(self):
        with self._urlopen(self._http_error(429)) as urlopen:
            with self.assertRaises(urllib.error.HTTPError):
                wiki_http.get_json("http://x")
        self.assertEqual(6, urlopen.call_count)
        self.assertEqual([10, 20, 40, 80, 160], [c.args[0] for c in self.sleep.call_args_list])

    def test_tentativi_scelti_dal_chiamante(self):
        with self._urlopen(self._http_error(503)) as urlopen:
            with self.assertRaises(urllib.error.HTTPError):
                wiki_http.get_json("http://x", attempts=3)
        self.assertEqual(3, urlopen.call_count)
        self.assertEqual(2, self.sleep.call_count)

    def test_retry_after_del_429_con_un_tetto(self):
        errors = [self._http_error(429, "42"), self._http_error(429, "9999"), self._http_error(429, "domani"),
                  self._response({"a": 1})]
        with self._urlopen(errors):
            self.assertEqual({"a": 1}, wiki_http.get_json("http://x"))
        self.assertEqual([42, 300, 40], [c.args[0] for c in self.sleep.call_args_list])

    def test_riprova_sui_5xx(self):
        with self._urlopen([self._http_error(503), self._http_error(502), self._response({"a": 1})]) as urlopen:
            self.assertEqual({"a": 1}, wiki_http.get_json("http://x"))
        self.assertEqual(3, urlopen.call_count)
        self.assertEqual([10, 20], [c.args[0] for c in self.sleep.call_args_list])

    def test_nessun_tentativo_sugli_altri_4xx(self):
        for code in (400, 403, 404):
            with self._urlopen([self._http_error(code)]) as urlopen:
                with self.assertRaises(urllib.error.HTTPError):
                    wiki_http.get_json("http://x")
            self.assertEqual(1, urlopen.call_count)
        self.sleep.assert_not_called()

    def test_corpo_non_json_si_riprova(self):
        bad = mock.MagicMock()
        bad.__enter__.return_value.read.return_value = b"<html>"
        with self._urlopen([bad, self._response({"a": 1})]):
            self.assertEqual({"a": 1}, wiki_http.get_json("http://x"))
        self.sleep.assert_called_once_with(10)

    def test_attesa_casuale_aggiunta(self):
        with mock.patch("wiki_http.random.uniform", return_value=3.5) as uniform:
            self.assertEqual(13.5, wiki_http.retry_wait(OSError("rete"), 0))
        uniform.assert_called_once_with(0, wiki_http.RETRY_JITTER_S)

    def test_post_con_intestazioni_timeout_e_nome_del_servizio(self):
        with self._urlopen([OSError("rete"), self._response({"a": 1})]) as urlopen:
            self.assertEqual({"a": 1}, wiki_http.get_json("http://x", b"query=1", {"User-Agent": "t"}, 120, "Wikidata"))
        request = urlopen.call_args[0][0]
        self.assertEqual(b"query=1", request.data)
        self.assertEqual("t", request.get_header("User-agent"))
        self.assertEqual(120, urlopen.call_args[1]["timeout"])
        self.assertIn("-- Wikidata: rete", self.err.getvalue())

    def test_scadenza_superata_prima_della_richiesta(self):
        with self._urlopen(AssertionError("rete usata")) as urlopen:
            with self.assertRaises(TimeoutError):
                wiki_http.get_json("http://x", deadline=time.monotonic() - 1)
        urlopen.assert_not_called()

    def test_timeout_della_richiesta_non_supera_la_scadenza(self):
        with self._urlopen([self._response({"a": 1})]) as urlopen:
            wiki_http.get_json("http://x", timeout=180, deadline=time.monotonic() + 30)
        self.assertLessEqual(urlopen.call_args[1]["timeout"], 30)

    def test_scadenza_raggiunta_tra_due_tentativi(self):
        with self._urlopen(OSError("rete")) as urlopen, \
                mock.patch("wiki_http.time.monotonic", side_effect=[0, 0, 31]):
            with self.assertRaises(TimeoutError):
                wiki_http.get_json("http://x", deadline=30)
        self.assertEqual(1, urlopen.call_count)

    def test_riprova_su_risposta_troncata(self):
        with self._urlopen([http.client.IncompleteRead(b"ab"), self._response({"a": 1})]) as urlopen:
            self.assertEqual({"a": 1}, wiki_http.get_json("http://x"))
        self.assertEqual(2, urlopen.call_count)

    def test_attesa_limitata_al_tempo_rimasto(self):
        # Primo tentativo a 0 s con scadenza a 30 s; al calcolo dell'attesa ne restano 4.
        with self._urlopen([OSError("rete"), self._response({"a": 1})]), \
                mock.patch("wiki_http.time.monotonic", side_effect=[0, 26, 26]):
            self.assertEqual({"a": 1}, wiki_http.get_json("http://x", deadline=30))
        self.sleep.assert_called_once_with(4)


if __name__ == "__main__":
    unittest.main()
