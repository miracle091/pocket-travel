#!/usr/bin/env python3
"""Test di upload_hf.py per --update-app-status: lettura e aggiornamento del catalogo, controllo su HuggingFace.

Uso: python test_upload_hf.py
"""
import json
import types
import unittest
from unittest import mock

import upload_hf

CATALOG = '''data class LlmModelDefinition(
    val id: String,
    val sha256: String?,
)

object LlmModelCatalog {
    val ALL: List<LlmModelDefinition> = listOf(
        LlmModelDefinition(
            id = "qwen3.5-0.8b",
            url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/model.gguf",
            fileName = "model.gguf",
            sha256 = "aaaa",
            sizeBytes = 10L,
        ),
        LlmModelDefinition(
            id = "pt-qwen3.5-0.8b",
            url = "https://huggingface.co/pockettravel/qwen3.5-0.8b-travel-it-GGUF/resolve/main/travel.gguf",
            fileName = "travel.gguf",
            sha256 = "bbbb",
            sizeBytes = 529_297_120L,
            minRamTier = RamTier.MINIMO,
        ),
        LlmModelDefinition(
            id = "pt-future",
            url = "https://huggingface.co/pockettravel/future-GGUF/resolve/main/future.gguf",
            fileName = "future.gguf",
            sha256 = null,
            sizeBytes = 0L,
        ),
    )
}
'''


class CatalogTargetsTest(unittest.TestCase):
    def test_trova_il_modello_che_scarica_il_file_dal_repo(self):
        targets = upload_hf.catalog_targets(CATALOG, "pockettravel/qwen3.5-0.8b-travel-it-GGUF", ["travel.gguf"])
        self.assertEqual([("pt-qwen3.5-0.8b", "travel.gguf")], targets)

    def test_file_sconosciuto_o_di_un_altro_repo_e_un_errore(self):
        with self.assertRaises(SystemExit):
            upload_hf.catalog_targets(CATALOG, "pockettravel/qwen3.5-0.8b-travel-it-GGUF", ["altro.gguf"])
        with self.assertRaises(SystemExit):
            upload_hf.catalog_targets(CATALOG, "pockettravel/qwen3.5-0.8b-travel-it-GGUF", ["model.gguf"])


class UpdateCatalogTest(unittest.TestCase):
    def test_cambia_solo_impronta_e_dimensione_del_file_indicato(self):
        updated = upload_hf.update_catalog(CATALOG, "travel.gguf", "c" * 64, 529_300_000)
        self.assertIn(f'sha256 = "{"c" * 64}",\n            sizeBytes = 529_300_000L,', updated)
        self.assertIn('sha256 = "aaaa"', updated)
        self.assertIn("sizeBytes = 10L", updated)
        self.assertIn("minRamTier = RamTier.MINIMO", updated)
        self.assertEqual(CATALOG.count("\n"), updated.count("\n"))

    def test_impronta_nulla_diventa_stringa(self):
        updated = upload_hf.update_catalog(CATALOG, "future.gguf", "d" * 64, 7)
        self.assertIn(f'sha256 = "{"d" * 64}",\n            sizeBytes = 7L,', updated)

    def test_la_dichiarazione_della_classe_non_cambia(self):
        updated = upload_hf.update_catalog(CATALOG, "travel.gguf", "c" * 64, 1)
        self.assertTrue(updated.startswith("data class LlmModelDefinition(\n    val id: String,\n    val sha256: String?,"))

    def test_file_assente_e_un_errore(self):
        with self.assertRaises(ValueError):
            upload_hf.update_catalog(CATALOG, "altro.gguf", "c" * 64, 1)


class CheckOnHfTest(unittest.TestCase):
    def api(self, sha, size):
        info = types.SimpleNamespace(path="travel.gguf", size=size, lfs=types.SimpleNamespace(sha256=sha))
        return types.SimpleNamespace(get_paths_info=lambda repo, paths: [info])

    def model(self):
        return [{"modelId": "pt", "fileName": "travel.gguf", "sha256": "e" * 64, "sizeBytes": 5}]

    def test_impronte_uguali_passano(self):
        upload_hf.check_on_hf(self.api("e" * 64, 5), "repo", self.model())

    def test_impronta_o_dimensione_diverse_fermano_lo_script(self):
        with self.assertRaises(SystemExit):
            upload_hf.check_on_hf(self.api("f" * 64, 5), "repo", self.model())
        with self.assertRaises(SystemExit):
            upload_hf.check_on_hf(self.api("e" * 64, 6), "repo", self.model())


class WorkflowRunTest(unittest.TestCase):
    def gh(self, stdout):
        return mock.patch.object(upload_hf.subprocess, "run",
                                 return_value=types.SimpleNamespace(stdout=json.dumps(stdout), returncode=0))

    def test_trova_solo_il_run_con_il_proprio_identificativo(self):
        runs = [{"databaseId": 1, "displayTitle": "Impronte dei modelli altro123"},
                {"databaseId": 2, "displayTitle": "Impronte dei modelli abc123"}]
        with self.gh(runs):
            self.assertEqual("2", upload_hf.find_run("abc123"))
            self.assertIsNone(upload_hf.find_run("zzz999"))

    def test_esito_dal_job_che_aggiorna_app_status(self):
        jobs = {"jobs": [{"name": "check-version-bump", "conclusion": "success"},
                         {"name": upload_hf.UPDATE_JOB, "conclusion": "failure"}]}
        with self.gh(jobs):
            self.assertEqual("failure", upload_hf.job_conclusion("7"))

    def test_job_non_finito(self):
        with self.gh({"jobs": [{"name": upload_hf.UPDATE_JOB, "conclusion": ""}]}):
            self.assertIsNone(upload_hf.job_conclusion("7"))


class RealCatalogTest(unittest.TestCase):
    def test_il_catalogo_dell_app_si_legge_e_si_riscrive_uguale(self):
        text = upload_hf.CATALOG.read_text(encoding="utf-8")
        targets = upload_hf.catalog_targets(text, "pockettravel/qwen3.5-0.8b-travel-it-GGUF",
                                            ["qwen3.5-0.8b-travel-it-Q4_K_M.gguf"])
        self.assertEqual([("pt-qwen3.5-0.8b", "qwen3.5-0.8b-travel-it-Q4_K_M.gguf")], targets)
        block = next(b for _, _, b in upload_hf.catalog_blocks(text) if "qwen3.5-0.8b-travel-it" in b)
        size = int(upload_hf.re.search(r"sizeBytes = ([0-9_]+)L", block).group(1).replace("_", ""))
        same = upload_hf.update_catalog(text, "qwen3.5-0.8b-travel-it-Q4_K_M.gguf",
                                        upload_hf.field(block, "sha256"), size)
        self.assertEqual(text, same)


if __name__ == "__main__":
    unittest.main()
