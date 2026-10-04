#!/usr/bin/env python3
"""Test di resolve_ai_models.py: scelta dell'impronta che coincide con HuggingFace (senza rete).

Uso: python test_resolve_ai_models.py
"""
import unittest
from pathlib import Path

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
        self.assertIn("pt-qwen3.5-0.8b", [m["modelId"] for m in models])
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


if __name__ == "__main__":
    unittest.main()
