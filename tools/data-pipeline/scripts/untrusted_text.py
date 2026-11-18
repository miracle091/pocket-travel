"""Pulizia del testo non fidato (Wikivoyage, Wikipedia) prima che arrivi a un modello: stringhe dei token dei chat
template e tag HTML. Stesse regole di stripDecodedMarkup in GenerateGuideContent.kt, che le applica dopo la decodifica
delle entita' ("&lt;|im_end|&gt;" diventa "<|im_end|>"): un "<" isolato, "a < b" e "<5 km" restano.
"""
import re

# "<|im_end|>", "<|im_start|>", "<|endoftext|>", i marcatori di turno di Gemma e i tag <think>
TEMPLATE_TOKEN = re.compile(r"<\|[^|<>\n]{0,64}\|>|</?(?:start_of_turn|end_of_turn|think)\b[^<>\n]*>", re.IGNORECASE)
HTML_TAG = re.compile(r"</?[A-Za-z][A-Za-z0-9]*(?:\s[^<>\n]*)?/?>|</>")


def has_template_token(text):
    """Il testo contiene la stringa di un token di template."""
    return TEMPLATE_TOKEN.search(text) is not None


def strip_markup(text):
    """[text] senza stringhe di token di template e senza tag HTML."""
    return HTML_TAG.sub("", TEMPLATE_TOKEN.sub("", text))
