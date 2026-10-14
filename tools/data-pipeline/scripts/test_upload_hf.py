#!/usr/bin/env python3
"""Test di upload_hf.py per --update-app-status: lettura e aggiornamento del catalogo, controllo su Hugging Face.

Uso: python test_upload_hf.py
"""
import contextlib
import hashlib
import io
import json
import os
import tempfile
import types
import unittest
from pathlib import Path
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


class UpdateAppStatusTest(unittest.TestCase):
    """--update-app-status --push con un GGUF finto: workflow solo per i modelli gia' in app-status.json."""

    def run_update(self, file_name, repo, released, workflow_ok=True):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "model"
            folder.mkdir()
            gguf = folder / file_name
            gguf.write_bytes(b"gguf")
            catalog = Path(tmp) / "LlmModelCatalog.kt"
            catalog.write_text(CATALOG, encoding="utf-8")
            info = types.SimpleNamespace(path=file_name, size=4, lfs=types.SimpleNamespace(sha256=upload_hf.sha256_of(gguf)))
            api = types.SimpleNamespace(get_paths_info=lambda repo, paths: [info])
            with mock.patch.object(upload_hf, "CATALOG", catalog), \
                    mock.patch.object(upload_hf, "released_model_ids", return_value=released), \
                    mock.patch.object(upload_hf, "run_update_workflow", return_value=workflow_ok) as workflow, \
                    contextlib.redirect_stdout(io.StringIO()):
                exited = False
                try:
                    upload_hf.update_app_status(api, repo, folder, push=True)
                except SystemExit:
                    exited = True
            return workflow, catalog.read_text(encoding="utf-8"), exited

    def test_modello_nuovo_solo_nel_catalogo(self):
        workflow, text, exited = self.run_update("future.gguf", "pockettravel/future-GGUF", {"pt-qwen3.5-0.8b"})
        self.assertFalse(exited)
        workflow.assert_not_called()
        self.assertNotIn("sha256 = null", text)
        self.assertIn("sizeBytes = 4L", text)

    def test_modello_gia_pubblicato_passa_dal_workflow(self):
        workflow, text, exited = self.run_update("travel.gguf", "pockettravel/qwen3.5-0.8b-travel-it-GGUF",
                                                 {"pt-qwen3.5-0.8b"})
        self.assertFalse(exited)
        workflow.assert_called_once()
        self.assertEqual(["pt-qwen3.5-0.8b"], [m["modelId"] for m in workflow.call_args.args[0]])
        self.assertNotIn('sha256 = "bbbb"', text)

    def test_workflow_fallito_lascia_il_catalogo(self):
        _, text, exited = self.run_update("travel.gguf", "pockettravel/qwen3.5-0.8b-travel-it-GGUF",
                                          {"pt-qwen3.5-0.8b"}, workflow_ok=False)
        self.assertTrue(exited)
        self.assertEqual(CATALOG, text)


class RealCatalogTest(unittest.TestCase):
    def test_il_catalogo_dell_app_si_legge_e_si_riscrive_uguale(self):
        text = upload_hf.CATALOG.read_text(encoding="utf-8")
        targets = upload_hf.catalog_targets(text, "pockettravel/qwen3.5-2b-travel-it-GGUF",
                                            ["qwen3.5-2b-travel-it-Q4_K_M.gguf"])
        self.assertEqual([("pt-qwen3.5-2b", "qwen3.5-2b-travel-it-Q4_K_M.gguf")], targets)
        block = next(b for _, _, b in upload_hf.catalog_blocks(text) if "qwen3.5-2b-travel-it" in b)
        size = int(upload_hf.re.search(r"sizeBytes = ([0-9_]+)L", block).group(1).replace("_", ""))
        same = upload_hf.update_catalog(text, "qwen3.5-2b-travel-it-Q4_K_M.gguf",
                                        upload_hf.field(block, "sha256"), size)
        self.assertEqual(text, same)


class AttributionTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)

    def test_attribution_della_cartella_ha_la_precedenza(self):
        (self.tmp / "ATTRIBUTION.tsv").write_text("x\n", encoding="utf-8")
        self.assertEqual(self.tmp / "ATTRIBUTION.tsv", upload_hf.resolve_attribution(self.tmp))

    def test_senza_quello_locale_ripiega_sul_globale_avvisando(self):
        globale = self.tmp / "globale.tsv"
        globale.write_text("x\n", encoding="utf-8")
        folder = self.tmp / "merged"
        folder.mkdir()
        with mock.patch.object(upload_hf, "GLOBAL_ATTRIBUTION", globale), mock.patch("sys.stderr", io.StringIO()) as err:
            self.assertEqual(globale, upload_hf.resolve_attribution(folder))
        self.assertIn("uso quello globale", err.getvalue())

    def test_righe_viaggiare_sicuri_bloccano_il_pubblico_ma_non_il_privato(self):
        path = self.tmp / "a.tsv"
        path.write_text("Farnesina\t" + upload_hf.VS_LICENSE + "\nWikivoyage\tCC BY-SA\n", encoding="utf-8")
        with self.assertRaises(SystemExit) as ctx:
            upload_hf.check_no_vs(path, push_public=True)
        self.assertIn("1 righe di Viaggiare Sicuri", str(ctx.exception))
        with mock.patch("sys.stderr", io.StringIO()) as err:
            upload_hf.check_no_vs(path, push_public=False)
        self.assertIn("ATTENZIONE", err.getvalue())

    def test_attribution_pulita_passa_in_silenzio(self):
        path = self.tmp / "a.tsv"
        path.write_text("Wikivoyage\tCC BY-SA\n", encoding="utf-8")
        with mock.patch("sys.stderr", io.StringIO()) as err:
            upload_hf.check_no_vs(path, push_public=True)
        self.assertEqual("", err.getvalue())

    def test_attribution_assente_blocca_solo_il_pubblico(self):
        missing = self.tmp / "non-esiste.tsv"
        with self.assertRaises(SystemExit):
            upload_hf.check_no_vs(missing, push_public=True)
        self.assertIsNone(upload_hf.check_no_vs(missing, push_public=False))


class HelpersTest(unittest.TestCase):
    def test_kotlin_long_con_separatori(self):
        self.assertEqual("529_297_120L", upload_hf.kotlin_long(529297120))
        self.assertEqual("0L", upload_hf.kotlin_long(0))
        self.assertEqual("999L", upload_hf.kotlin_long(999))

    def test_sha256_di_un_file_a_piu_blocchi(self):
        data = b"abc" * (1 << 20)  # oltre il blocco di lettura da 1 MiB
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "f.bin"
            path.write_bytes(data)
            self.assertEqual(hashlib.sha256(data).hexdigest(), upload_hf.sha256_of(path))
            empty = Path(d) / "vuoto"
            empty.write_bytes(b"")
            self.assertEqual(hashlib.sha256(b"").hexdigest(), upload_hf.sha256_of(empty))

    def test_catalogo_senza_modelli_e_un_errore(self):
        with self.assertRaises(ValueError):
            upload_hf.catalog_blocks("niente")

    def test_card_generata_contiene_i_campi(self):
        card = upload_hf.CARD.format(name="m", license="cc-by-sa-4.0", base_model="b", base_license="mit")
        self.assertIn("cc-by-sa-4.0", card)
        self.assertIn("mit", card)


class MainDryRunTest(unittest.TestCase):
    ARGS = ["--repo", "u/m", "--base-model", "b", "--base-license", "mit"]

    def run_main(self, *argv):
        out = io.StringIO()
        with mock.patch("sys.argv", ["upload_hf.py", *argv]), mock.patch("sys.stdout", out):
            upload_hf.main()
        return out.getvalue()

    def make_folder(self, tmp):
        folder = Path(tmp) / "merged"
        folder.mkdir()
        (folder / "model.safetensors").write_bytes(b"x" * 1024)
        (folder / "ATTRIBUTION.tsv").write_text("Wikivoyage\tCC BY-SA\n", encoding="utf-8")
        return folder

    def test_senza_push_e_un_dry_run_che_non_tocca_huggingface(self):
        # huggingface_hub = None: un import qui fallirebbe, il dry-run non deve nemmeno tentarlo
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict("sys.modules", {"huggingface_hub": None}):
            text = self.run_main(str(self.make_folder(tmp)), *self.ARGS)
        self.assertIn("Repo: u/m (privato)", text)
        self.assertIn("File da caricare", text)
        self.assertIn("Dry-run: nulla e' stato caricato", text)

    def test_cartella_inesistente(self):
        with self.assertRaises(SystemExit) as ctx:
            self.run_main("/non/esiste", *self.ARGS)
        self.assertIn("Cartella non trovata", str(ctx.exception))

    def test_push_senza_token_esce_prima_di_importare_huggingface_hub(self):
        env = {k: v for k, v in os.environ.items() if k != "HF_TOKEN"}
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict("sys.modules", {"huggingface_hub": None}), \
                mock.patch.dict("os.environ", env, clear=True):
            with self.assertRaises(SystemExit) as ctx:
                self.run_main(str(self.make_folder(tmp)), *self.ARGS, "--push")
        self.assertIn("HF_TOKEN non impostato", str(ctx.exception))

    def test_public_con_righe_non_verificate_si_ferma_prima_di_caricare(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict("sys.modules", {"huggingface_hub": None}):
            folder = self.make_folder(tmp)
            (folder / "ATTRIBUTION.tsv").write_text("F\t" + upload_hf.VS_LICENSE + "\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                self.run_main(str(folder), *self.ARGS, "--public", "--push")


if __name__ == "__main__":
    unittest.main()
