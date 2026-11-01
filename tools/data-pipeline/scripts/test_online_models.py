#!/usr/bin/env python3
"""Test di online_models.py: modelli in tutte e due le fonti, filtri, versioni con la data, prezzi da models.dev.

Uso: python test_online_models.py
"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from online_models import other_models

HERE = Path(__file__).resolve().parent

CURATED = {"providers": {
    "MISTRAL": [{"id": "mistral-small-latest", "role": "auto", "temperature": True, "inputUsdPerMTok": 0.15, "outputUsdPerMTok": 0.6}],
    "CLAUDE": [{"id": "claude-haiku-5-5", "role": "auto", "temperature": False, "inputUsdPerMTok": 0.1, "outputUsdPerMTok": 0.5}],
}}


def dev(cost=(1, 2), output=("text",), temperature=True, **extra):
    return {"modalities": {"input": ["text", "image"], "output": list(output)}, "temperature": temperature,
            "cost": {"input": cost[0], "output": cost[1]}, **extra}


def lite(provider, cost=(1, 2), mode="chat", **extra):
    return {"litellm_provider": provider, "mode": mode, "input_cost_per_token": cost[0] / 1e6,
            "output_cost_per_token": cost[1] / 1e6, **extra}


class OtherModelsTest(unittest.TestCase):
    def test_solo_i_modelli_di_chat_in_tutte_e_due_le_fonti(self):
        modelsdev = {"mistral": {"models": {
            "mistral-medium-latest": dev(),
            "mistral-small-latest": dev(),  # gia' proposto
            "solo-models-dev": dev(),
            "mistral-embed": dev(),  # famiglia esclusa
            "pixtral-image": dev(output=("text", "image")),
            "vecchio": dev(status="deprecated"),
        }}}
        litellm = {f"mistral/{name}": lite("mistral") for name in
                   ["mistral-medium-latest", "mistral-small-latest", "mistral-embed", "pixtral-image", "vecchio"]}
        others, warnings = other_models(CURATED, litellm, modelsdev)
        self.assertEqual({"MISTRAL": [{"id": "mistral-medium-latest", "temperature": True, "inputUsdPerMTok": 1,
                                       "outputUsdPerMTok": 2}]}, others)
        self.assertEqual([], warnings)

    def test_modalita_diversa_da_chat_o_senza_l_endpoint_di_chat(self):
        modelsdev = {"openai": {"models": {"gpt-a": dev(), "gpt-b": dev(), "gpt-c": dev()}}}
        litellm = {"gpt-a": lite("openai", mode="responses"),
                   "gpt-b": lite("openai", supported_endpoints=["/v1/responses"]),
                   "gpt-c": lite("openai", supported_endpoints=["/v1/chat/completions", "/v1/responses"])}
        self.assertEqual(["gpt-c"], [m["id"] for m in other_models(CURATED, litellm, modelsdev)[0]["CHATGPT"]])

    def test_versione_con_la_data_solo_senza_il_nome_semplice(self):
        modelsdev = {"anthropic": {"models": {
            "claude-sonnet-4-5": dev(), "claude-sonnet-4-5-20250929": dev(),
            "claude-haiku-5-5-20261001": dev(),  # il nome semplice e' gia' proposto
            "claude-opus-4-1-20250805": dev(),  # solo con la data: resta
        }}}
        litellm = {name: lite("anthropic") for name in modelsdev["anthropic"]["models"]}
        ids = [m["id"] for m in other_models(CURATED, litellm, modelsdev)[0]["CLAUDE"]]
        self.assertEqual(["claude-opus-4-1-20250805", "claude-sonnet-4-5"], ids)

    def test_prezzo_e_temperatura_da_models_dev_con_avviso_se_diverso(self):
        modelsdev = {"google": {"models": {"gemini-x": dev(cost=(0.7, 2.1), temperature=False)}}}
        litellm = {"gemini/gemini-x": lite("gemini", cost=(1.36, 4.18)), "gemini-x": lite("vertex_ai-language-models")}
        others, warnings = other_models(CURATED, litellm, modelsdev)
        self.assertEqual([{"id": "gemini-x", "temperature": False, "inputUsdPerMTok": 0.7, "outputUsdPerMTok": 2.1}],
                         others["GEMINI"])
        self.assertEqual(1, len(warnings))
        self.assertIn("LiteLLM 1.36/4.18", warnings[0])

    def test_chiave_di_un_altro_servizio_in_litellm_non_conta(self):
        modelsdev = {"google": {"models": {"gemini-y": dev()}}}
        self.assertEqual({}, other_models(CURATED, {"gemini-y": lite("vertex_ai-language-models")}, modelsdev)[0])


class MainTest(unittest.TestCase):
    def run_script(self, litellm, modelsdev):
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for name, data in [("curated.json", CURATED), ("litellm.json", litellm), ("dev.json", modelsdev)]:
                path = Path(d) / name
                path.write_text(json.dumps(data), encoding="utf-8")
                paths.append(str(path))
            out = Path(d) / "out.json"
            result = subprocess.run([sys.executable, str(HERE / "online_models.py"), *paths, str(out)],
                                    capture_output=True, text=True, encoding="utf-8")
            return result, json.loads(out.read_text(encoding="utf-8")) if out.exists() else None

    def test_scrive_l_elenco_con_other_models(self):
        result, written = self.run_script({"mistral/m": lite("mistral")}, {"mistral": {"models": {"m": dev()}}})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(CURATED["providers"], written["providers"])
        self.assertEqual(["m"], [m["id"] for m in written["otherModels"]["MISTRAL"]])

    def test_nessun_modello_e_un_errore(self):
        result, written = self.run_script({}, {})
        self.assertEqual(1, result.returncode)
        self.assertIsNone(written)


if __name__ == "__main__":
    unittest.main()
