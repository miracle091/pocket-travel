#!/usr/bin/env python3
"""Controllo di un GGUF prima di misurarlo o caricarlo: metadati del tokenizer, token di fine e template, e
tokenizzazione del prompt dell'app uguale tra llama.cpp e il tokenizer di Hugging Face del modello base.

Un GGUF con l'EOS sbagliato non smette di generare, e uno con i token diversi da quelli del training risponde peggio
senza errori: misure e prove su quel file non dicono niente del modello. Il prompt e' quello dell'app (ChatML di
chatml_prompt, con il blocco <think> vuoto quando il template ha enable_thinking, come ai_chat.cpp) con un contesto e
una domanda di prova nella lingua scelta.

Uso: python check_gguf.py <file .gguf> --tokenizer <tokenizer.json o cartella del modello HF> --llama-cpp <cartella>
                          [--lang it|en]
Esce con errore se ne' l'EOS ne' l'EOT e' <|im_end|>, se manca il template o se i token sono diversi.
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from eval_common import chatml_prompt, find_llama_bin, llama_env, on_device_prompt, uses_empty_think

END_OF_TURN = "<|im_end|>"
SAMPLE = {
    "it": ("Cosa fare\nIl GR20 attraversa l'isola da nord a sud: quindici tappe in montagna, da giugno a settembre.\n"
           "Le spiagge del sud sono affollate in agosto; a Capo Corso ci sono sentieri lungo la costa.",
           "Dove posso fare escursioni in montagna?"),
    "en": ("Do\nThe GR20 crosses the island from north to south: fifteen mountain stages, from June to September.\n"
           "Southern beaches are crowded in August; Cap Corse has trails along the coast.",
           "Where can I go hiking in the mountains?"),
}
FIELDS = ("general.architecture", "tokenizer.ggml.model", "tokenizer.ggml.pre", "tokenizer.ggml.bos_token_id",
          "tokenizer.ggml.eos_token_id", "tokenizer.ggml.eot_token_id", "tokenizer.ggml.padding_token_id",
          "tokenizer.ggml.add_bos_token")


def field(reader, key):
    """Valore di un campo dei metadati: stringa, numero o lista; None se manca."""
    f = reader.fields.get(key)
    if f is None:
        return None
    if f.types and f.types[0].name == "STRING":
        return bytes(f.parts[f.data[0]]).decode("utf-8")
    value = f.parts[f.data[0]].tolist()
    return value[0] if len(value) == 1 else value


def hf_tokenizer_file(path):
    return path / "tokenizer.json" if path.is_dir() else path


def llama_ids(exe, model, prompt):
    """Token di [prompt] con llama-tokenize, senza BOS (l'app non lo aggiunge ai modelli ChatML)."""
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="", suffix=".txt", delete=False) as tmp:
        tmp.write(prompt)
    try:
        out = subprocess.run([exe, "-m", str(model), "-f", tmp.name, "--ids", "--log-disable", "--no-bos"],
                             capture_output=True, env=llama_env(exe))
    finally:
        Path(tmp.name).unlink()
    if out.returncode:
        sys.exit(f"llama-tokenize uscito con codice {out.returncode}: {out.stderr.decode('utf-8', 'replace')[-500:]}")
    return json.loads(out.stdout.decode("utf-8", "replace").strip().splitlines()[-1])


def first_difference(a, b):
    return next((i for i, (x, y) in enumerate(zip(a, b)) if x != y), min(len(a), len(b)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("model", type=Path)
    ap.add_argument("--tokenizer", type=Path, required=True, help="tokenizer.json del modello base, o la sua cartella HF")
    ap.add_argument("--llama-cpp", type=Path, required=True, help="cartella dei binari di llama.cpp (get_llama_tools.py) o clone con build/bin")
    ap.add_argument("--lang", choices=sorted(SAMPLE), default="it", help="lingua del prompt di prova")
    a = ap.parse_args()

    from gguf import GGUFReader
    from tokenizers import Tokenizer

    reader = GGUFReader(a.model)
    tokens = reader.fields["tokenizer.ggml.tokens"]
    token_text = lambda i: bytes(tokens.parts[tokens.data[i]]).decode("utf-8")  # noqa: E731
    errors = []
    print(f"== {a.model}")
    for key in FIELDS + (f"{field(reader, 'general.architecture')}.context_length",):
        value = field(reader, key)
        name = f" = {token_text(value)!r}" if key.endswith("_token_id") and isinstance(value, int) else ""
        print(f"  {key}: {value}{name}")
    ends = {token_text(field(reader, k)) for k in ("tokenizer.ggml.eos_token_id", "tokenizer.ggml.eot_token_id")
            if isinstance(field(reader, k), int)}
    if END_OF_TURN not in ends:
        errors.append(f"ne' l'EOS ne' l'EOT e' {END_OF_TURN} (trovati {sorted(ends)}): il modello non chiude il turno")
    template = field(reader, "tokenizer.chat_template")
    if not template:
        errors.append("manca tokenizer.chat_template: l'app non riconosce il formato al caricamento")
    else:
        print(f"  template: {len(template)} caratteri, enable_thinking: {uses_empty_think(template)}")

    context, question = SAMPLE[a.lang]
    prompt = chatml_prompt(on_device_prompt(context, question, a.lang), empty_think=uses_empty_think(template))
    hf = Tokenizer.from_file(str(hf_tokenizer_file(a.tokenizer))).encode(prompt, add_special_tokens=False).ids
    ids = llama_ids(find_llama_bin("llama-tokenize", a.llama_cpp), a.model, prompt)
    print(f"  token del prompt: llama.cpp {len(ids)}, Hugging Face {len(hf)}, uguali: {ids == hf}")
    if ids != hf:
        i = first_difference(ids, hf)
        errors.append(f"token diversi dalla posizione {i}: llama.cpp {ids[i:i + 5]}, Hugging Face {hf[i:i + 5]}")
    for e in errors:
        print(f"  ERRORE: {e}")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
