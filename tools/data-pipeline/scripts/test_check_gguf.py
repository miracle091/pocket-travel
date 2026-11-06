#!/usr/bin/env python3
"""Test di check_gguf.py: metadati letti dal GGUF, errori su token di fine, template e tokenizzazione. GGUFReader,
il tokenizer di Hugging Face e llama-tokenize sono sostituiti da finti (nessun modello vero, nessun binario).

Uso: python test_check_gguf.py
"""
import contextlib
import io
import sys
import types
import unittest
from pathlib import Path
from unittest import mock

import check_gguf

TOKENS = ["<|endoftext|>", "<|im_start|>", "<|im_end|>", "ciao"]
TEMPLATE = "{% for message in messages %}<|im_start|>{{ message.role }}{% endfor %}"


class Values(list):
    """Al posto dell'array numpy di GGUFReader: bytes() ne legge le stringhe, tolist() i numeri."""

    def tolist(self):
        return list(self)


class Field:
    """Campo di GGUFReader: parts con i valori, data con l'indice di ciascun valore in parts."""

    def __init__(self, values, string=False):
        self.types = [types.SimpleNamespace(name="STRING" if string else "UINT32")]
        self.parts = [Values(v.encode("utf-8")) if string else Values([v]) for v in values]
        self.data = list(range(len(values)))


def reader(eos=2, template=TEMPLATE):
    fields = {"general.architecture": Field(["qwen35"], string=True), "tokenizer.ggml.tokens": Field(TOKENS, string=True),
              "tokenizer.ggml.eos_token_id": Field([eos]), "qwen35.context_length": Field([262144])}
    if template:
        fields["tokenizer.chat_template"] = Field([template], string=True)
    return types.SimpleNamespace(fields=fields)


def run(gguf_reader, hf_ids, llama):
    """main() con i finti; ritorna (codice di uscita, output)."""
    tokenizer = types.SimpleNamespace(encode=lambda text, add_special_tokens: types.SimpleNamespace(ids=hf_ids))
    modules = {"gguf": types.SimpleNamespace(GGUFReader=lambda path: gguf_reader),
               "tokenizers": types.SimpleNamespace(Tokenizer=types.SimpleNamespace(from_file=lambda path: tokenizer))}
    argv = ["check_gguf.py", "m.gguf", "--tokenizer", "tokenizer.json", "--llama-cpp", "bin"]
    out = io.StringIO()
    with mock.patch.dict(sys.modules, modules), mock.patch.object(sys, "argv", argv), \
            mock.patch.object(check_gguf, "find_llama_bin", return_value="llama-tokenize"), \
            mock.patch.object(check_gguf, "llama_ids", return_value=llama), contextlib.redirect_stdout(out), \
            exit_code() as code:
        check_gguf.main()
    return code[0], out.getvalue()


@contextlib.contextmanager
def exit_code():
    code = [None]
    try:
        yield code
    except SystemExit as e:
        code[0] = e.code


class CheckGgufTest(unittest.TestCase):
    def test_gguf_corretto(self):
        code, out = run(reader(), [1, 3, 2], [1, 3, 2])
        self.assertEqual(0, code)
        self.assertIn("tokenizer.ggml.eos_token_id: 2 = '<|im_end|>'", out)
        self.assertIn("qwen35.context_length: 262144", out)
        self.assertIn("uguali: True", out)

    def test_eos_sbagliato(self):
        code, out = run(reader(eos=0), [1], [1])
        self.assertEqual(1, code)
        self.assertIn("non chiude il turno", out)

    def test_senza_template(self):
        code, out = run(reader(template=None), [1], [1])
        self.assertEqual(1, code)
        self.assertIn("manca tokenizer.chat_template", out)

    def test_token_diversi(self):
        code, out = run(reader(), [1, 3, 2], [1, 0, 2])
        self.assertEqual(1, code)
        self.assertIn("token diversi dalla posizione 1", out)

    def test_cartella_del_modello_hf(self):
        with mock.patch.object(Path, "is_dir", return_value=True):
            self.assertEqual(Path("hf") / "tokenizer.json", check_gguf.hf_tokenizer_file(Path("hf")))
        self.assertEqual(Path("t.json"), check_gguf.hf_tokenizer_file(Path("t.json")))

    def test_prima_differenza(self):
        self.assertEqual(2, check_gguf.first_difference([1, 2, 3], [1, 2, 4]))
        self.assertEqual(2, check_gguf.first_difference([1, 2], [1, 2, 3]))


if __name__ == "__main__":
    unittest.main()
