#!/usr/bin/env python3
"""Estrae da LlmModelCatalog.kt la lista di modelli da pubblicare in app-status.json
(campo aiModels), usato da .github/workflows/publish-apk.yml.

Un heredoc inline nello YAML del workflow avrebbe un conflitto reale tra due vincoli:
il terminatore di un heredoc bash deve stare a colonna 0 (nessuna indentazione), mentre
un block scalar YAML (`run: |`) richiede che ogni riga sia indentata almeno quanto la
prima - da qui uno script separato invece di un python3 -c inline.

Le entry con sha256 non ancora verificato restano commentate nel sorgente Kotlin: il regex sui blocchi "LlmModelDefinition(...)"
le esclude gia' da solo, senza doverle filtrare qui.
"""
import json
import re
import sys


def extract_entries(kotlin_source: str) -> list[dict]:
    # Il file dichiara anche "data class LlmModelDefinition(...)" (i campi, non una
    # istanza): senza tagliare da qui in poi quel blocco verrebbe scambiato per la
    # prima entry.
    content = kotlin_source[kotlin_source.index("listOf("):]
    entries = []
    for block in re.findall(r"LlmModelDefinition\((.*?)\n\s*\),", content, re.S):
        def field(name: str) -> str | None:
            m = re.search(rf'{name}\s*=\s*"([^"]*)"', block)
            return m.group(1) if m else None

        size_match = re.search(r"sizeBytes\s*=\s*([0-9_]+)L", block)
        model_id, file_name, sha256 = field("id"), field("fileName"), field("sha256")
        if model_id and file_name and sha256 and size_match:
            entries.append({
                "modelId": model_id,
                "modelVersion": file_name,
                "sha256": sha256,
                "sizeBytes": int(size_match.group(1).replace("_", "")),
            })
    return entries


if __name__ == "__main__":
    catalog_path = sys.argv[1]
    with open(catalog_path, encoding="utf-8") as f:
        print(json.dumps(extract_entries(f.read())))
