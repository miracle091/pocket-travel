#!/usr/bin/env python3
"""Liste di parole dell'assistente, dallo stesso file che legge l'app (WordLists.kt):
feature/ai/src/main/resources/pockettravel/word_lists.txt. Righe "#": commenti; ogni sezione comincia con [nome] e le
sue righe si uniscono con uno spazio, salvo [history-climate] (senza spazio)."""
import re
from pathlib import Path

PATH = Path(__file__).resolve().parents[3] / "feature" / "ai" / "src" / "main" / "resources" / "pockettravel" / "word_lists.txt"


def sections(text):
    """{nome della sezione: righe}, nell'ordine del file."""
    result, name = {}, None
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            name = line[1:-1]
            result[name] = []
        elif name is None:
            raise ValueError(f"riga fuori da una sezione: {line}")
        else:
            result[name].append(line)
    return result


_SECTIONS = sections(PATH.read_text(encoding="utf-8"))


def _words(name):
    return " ".join(_SECTIONS[name])


# Parole della domanda che l'app non cerca (TravelAssistant.questionStopwords).
STOPWORDS = set(_words("stopwords").split())
# Radici che indicano la categoria (CategoryWords.categoryTriggers), separate da spazi.
CATEGORY_TRIGGERS = {name.split()[1]: _words(name) for name in _SECTIONS if name.startswith("triggers ")}
# Parole dei titoli delle guide per lingua e categoria (categoryGuideWords), separate da ", ".
CATEGORY_GUIDE_WORDS = {}
for _name in _SECTIONS:
    if _name.startswith("guide "):
        _, _lang, _group = _name.split()
        CATEGORY_GUIDE_WORDS.setdefault(_lang, {})[_group] = _words(_name)
FORECAST_STEMS = _words("forecast")
NEAR_TIME_STEMS = _words("near-time")
# Parole di storia o di clima (TravelAssistant.historyClimateWords).
HISTORY_CLIMATE_WORDS = re.compile("".join(_SECTIONS["history-climate"]), re.IGNORECASE)
