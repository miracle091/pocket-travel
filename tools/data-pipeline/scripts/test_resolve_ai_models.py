#!/usr/bin/env python3
"""Test di resolve_ai_models.py: scelta dell'impronta che coincide con Hugging Face (senza rete).

Uso: python test_resolve_ai_models.py
"""
import unittest
from pathlib import Path
from unittest import mock

import resolve_ai_models as r

CATALOG = '''data class LlmModelDefinition(
    val id: String,
)

object LlmModelCatalog {
    val ALL = listOf(
        LlmModelDefinition(
            id = "pt-0.8b",
            url = "https://huggingface.co/pockettravel/a-GGUF/resolve/main/a.gguf",
            fileName = "a.gguf",
            sha256 = "AAAA",
            sizeBytes = 1_000L,
        ),
        LlmModelDefinition(
            id = "pt-future",
            url = "https://huggingface.co/pockettravel/b-GGUF/resolve/main/b.gguf",
            fileName = "b.gguf",
            sha256 = null,
            sizeBytes = 0L,
        ),
    )
}
'''
URL = "https://huggingface.co/pockettravel/a-GGUF/resolve/main/a.gguf"


class CatalogModelsTest(unittest.TestCase):
    def test_solo_i_modelli_pubblicati_con_url(self):
        self.assertEqual([{"modelId": "pt-0.8b", "modelVersion": "a.gguf", "sha256": "aaaa", "sizeBytes": 1000,
                           "url": URL}], r.catalog_models(CATALOG))

    def test_catalogo_vero_dell_app(self):
        path = Path(__file__).resolve().parents[3] / "feature/ai/src/main/kotlin/com/pockettravel/feature/ai/LlmModelCatalog.kt"
        models = r.catalog_models(path.read_text(encoding="utf-8"))
        self.assertIn("pt-qwen3.5-2b", [m["modelId"] for m in models])
        self.assertTrue(all(m["url"].endswith("/" + m["modelVersion"]) for m in models))


class ResolveTest(unittest.TestCase):
    catalog = r.catalog_models(CATALOG)

    def test_catalogo_che_coincide(self):
        result = r.resolve(self.catalog, [], lambda url: ("aaaa", 1000))
        self.assertEqual([{"modelId": "pt-0.8b", "modelVersion": "a.gguf", "sha256": "aaaa", "sizeBytes": 1000}], result)

    def test_impronta_online_quando_il_catalogo_e_vecchio(self):
        online = [{"modelId": "pt-0.8b", "modelVersion": "a.gguf", "sha256": "bbbb", "sizeBytes": 2000}]
        result = r.resolve(self.catalog, online, lambda url: ("bbbb", 2000))
        self.assertEqual("bbbb", result[0]["sha256"])
        self.assertEqual(2000, result[0]["sizeBytes"])

    def test_impronta_online_di_un_altro_file_non_vale(self):
        online = [{"modelId": "pt-0.8b", "modelVersion": "vecchio.gguf", "sha256": "bbbb", "sizeBytes": 2000}]
        with self.assertRaises(ValueError):
            r.resolve(self.catalog, online, lambda url: ("bbbb", 2000))

    def test_nessuna_impronta_coincide(self):
        online = [{"modelId": "pt-0.8b", "modelVersion": "a.gguf", "sha256": "bbbb", "sizeBytes": 2000}]
        with self.assertRaises(ValueError) as error:
            r.resolve(self.catalog, online, lambda url: ("cccc", 3000))
        self.assertIn("pt-0.8b", str(error.exception))

    def test_file_senza_impronta_su_huggingface(self):
        with self.assertRaises(ValueError):
            r.resolve(self.catalog, [], lambda url: None)


class FakeConnection:
    """HTTPSConnection finta: restituisce le intestazioni di FakeConnection.headers."""
    headers = {}

    def __init__(self, netloc, timeout):
        self.netloc, self.requests = netloc, []

    def request(self, method, path):
        self.requests.append((method, path))

    def getresponse(self):
        return mock.Mock(getheader=lambda name: FakeConnection.headers.get(name))

    def close(self):
        pass


class HfFingerprintTest(unittest.TestCase):
    URL = "https://huggingface.co/pockettravel/a-GGUF/resolve/main/a.gguf"

    def fingerprint(self, headers):
        FakeConnection.headers = headers
        with mock.patch("http.client.HTTPSConnection", FakeConnection):
            return r.hf_fingerprint(self.URL)

    def test_etag_e_dimensione_dal_redirect_senza_seguirlo(self):
        self.assertEqual(("abc123", 1000), self.fingerprint({"X-Linked-ETag": '"ABC123"', "X-Linked-Size": "1000"}))

    def test_senza_intestazioni_lfs_nessuna_impronta(self):
        self.assertIsNone(self.fingerprint({}))
        self.assertIsNone(self.fingerprint({"X-Linked-ETag": '"abc"'}))

    def test_riprova_su_errore_di_rete_poi_rilancia(self):
        with mock.patch("http.client.HTTPSConnection", side_effect=OSError("rete")) as conn, mock.patch("time.sleep") as sleep:
            with self.assertRaises(OSError):
                r.hf_fingerprint(self.URL, attempts=3)
        self.assertEqual(3, conn.call_count)
        self.assertEqual([mock.call(10), mock.call(20)], sleep.call_args_list)

    def test_richiesta_head_sul_percorso_dell_url(self):
        FakeConnection.headers = {"X-Linked-ETag": '"a"', "X-Linked-Size": "1"}
        created = []
        original = FakeConnection.__init__

        def spy(self, *args, **kwargs):
            original(self, *args, **kwargs)
            created.append(self)

        with mock.patch("http.client.HTTPSConnection", FakeConnection), mock.patch.object(FakeConnection, "__init__", spy):
            r.hf_fingerprint(self.URL)
        self.assertEqual("huggingface.co", created[0].netloc)
        self.assertEqual([("HEAD", "/pockettravel/a-GGUF/resolve/main/a.gguf")], created[0].requests)


class CatalogEdgeTest(unittest.TestCase):
    def test_catalogo_vuoto_o_senza_campi_obbligatori(self):
        self.assertEqual([], r.catalog_models("val ALL = listOf(\n)"))
        incomplete = CATALOG.replace('fileName = "a.gguf",', "")
        self.assertEqual([], [m for m in r.catalog_models(incomplete) if m["modelId"] == "pt-0.8b"])

    def test_sha256_in_maiuscolo_e_dimensione_con_separatori(self):
        text = CATALOG.replace('sha256 = "AAAA"', 'sha256 = "AbCdEf"').replace("1_000L", "1_234_567L")
        model = r.catalog_models(text)[0]
        self.assertEqual("abcdef", model["sha256"])
        self.assertEqual(1234567, model["sizeBytes"])

    def test_risoluzione_con_impronta_online_in_maiuscolo(self):
        catalog = r.catalog_models(CATALOG)
        online = [{"modelId": "pt-0.8b", "modelVersion": "a.gguf", "sha256": "BBBB", "sizeBytes": 5}]
        result = r.resolve(catalog, online, lambda url: ("bbbb", 5))
        self.assertEqual("bbbb", result[0]["sha256"])

    def test_catalogo_vuoto_nessun_errore(self):
        self.assertEqual([], r.resolve([], [], lambda url: None))


if __name__ == "__main__":
    unittest.main()
