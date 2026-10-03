#!/usr/bin/env python3
"""Traduzione IT<->EN delle sezioni di guida per i dataset SFT, quando una lingua ha una sezione assente o molto
piu' povera dell'altra (vedi needs_translation). Solo per il training: le guide dell'app restano quelle di
Wikivoyage nella loro lingua.

Modelli MarianMT di Helsinki-NLP (OPUS-MT, CC BY 4.0): opus-mt-tc-big-en-it e opus-mt-tc-big-it-en. Il testo
tradotto e' un'opera derivata di Wikivoyage (CC BY-SA 4.0, con l'indicazione "tradotto automaticamente" in
ATTRIBUTION). Si traduce riga per riga e frase per frase, tenendo i segni dell'app all'inizio della riga
("▸ " sottotitolo, "• " elenco); una sezione con una frase sospetta (numeri diversi dall'originale, lunghezza
fuori misura) si scarta intera invece di finire nel dataset con un errore.

Cache: una riga JSON per frase {"k": sha1(direzione + frase), "t": traduzione} in data/sft/raw/translations.<src>-<tgt>.jsonl,
cosi' una nuova generazione traduce solo le frasi nuove. GPU se c'e' (torch ROCm o CUDA), altrimenti CPU.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

MODELS = {("en", "it"): "Helsinki-NLP/opus-mt-tc-big-en-it", ("it", "en"): "Helsinki-NLP/opus-mt-tc-big-it-en"}
LICENSE = "CC BY 4.0"
MARKERS = ("▸ ", "• ")
# Fine frase: punto, ! o ? seguiti da spazio e da una maiuscola, una cifra o una virgoletta
SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+(?=[A-ZÀ-Ý0-9\"«(])")
NUMBER = re.compile(r"\d+(?:[.,:]\d+)*")
POOR_CHARS = 300  # sotto questa lunghezza una sezione e' "povera"


def needs_translation(own, other):
    """True se la sezione nell'altra lingua va tradotta al posto di [own]: [own] assente, sotto POOR_CHARS
    caratteri, o l'altra almeno 2 volte piu' lunga. [other] deve esistere ed essere piu' lunga di [own]."""
    if not other:
        return False
    if not own:
        return True
    return len(other) > len(own) and (len(own) < POOR_CHARS or len(other) >= 2 * len(own))


def _numbers(text):
    return sorted(n.replace(",", ".") for n in NUMBER.findall(text))


def _plausible(src, out):
    """Stessi numeri e lunghezza ragionevole: i modelli MarianMT a volte ripetono o saltano pezzi."""
    return _numbers(src) == _numbers(out) and 0.5 <= (len(out) + 10) / (len(src) + 10) <= 2.0


def _pick_discrete_gpu():
    """Rende visibile a torch solo la GPU con piu' unita' di calcolo, prima che torch inizializzi la GPU: con ROCm la
    grafica integrata compare spesso come device 0, non esegue i kernel (hipErrorInvalidImage) e dichiara come sua la
    RAM di sistema; sceglierla dopo con set_device non basta, generate() crea comunque tensori sul device 0.
    Un processo a parte fa la scelta; HIP_VISIBLE_DEVICES o CUDA_VISIBLE_DEVICES gia' impostati vengono rispettati."""
    if "torch" in sys.modules or any(v in os.environ for v in ("HIP_VISIBLE_DEVICES", "CUDA_VISIBLE_DEVICES")):
        return
    probe = ("import torch\n"
             "n = torch.cuda.device_count() if torch.cuda.is_available() else 0\n"
             "print(max(range(n), key=lambda i: torch.cuda.get_device_properties(i).multi_processor_count) if n else -1)")
    try:
        best = int(subprocess.run([sys.executable, "-c", probe], capture_output=True, text=True, timeout=300).stdout.strip())
    except (ValueError, subprocess.SubprocessError) as e:
        print(f"[traduzione] scelta della GPU non riuscita ({e}): imposta HIP_VISIBLE_DEVICES/CUDA_VISIBLE_DEVICES", file=sys.stderr)
        return
    if best >= 0:
        os.environ["HIP_VISIBLE_DEVICES"] = os.environ["CUDA_VISIBLE_DEVICES"] = str(best)


class Translator:
    def __init__(self, src, tgt, cache_dir, batch=32):
        self.src, self.tgt, self.batch = src, tgt, batch
        self.cache_path = Path(cache_dir) / f"translations.{src}-{tgt}.jsonl"
        self.cache = {}
        if self.cache_path.exists():
            for line in self.cache_path.read_text(encoding="utf-8").splitlines():
                if line:
                    d = json.loads(line)
                    self.cache[d["k"]] = d["t"]
        self.model = self.tokenizer = None

    def _key(self, sentence):
        return hashlib.sha1(f"{self.src}-{self.tgt}\n{sentence}".encode("utf-8")).hexdigest()

    def _load(self):
        # torchao (importato da transformers se installato) carica torch.distributed, assente in torch ROCm per
        # Windows; per la traduzione non serve
        sys.modules.setdefault("torchao", None)
        _pick_discrete_gpu()
        import torch
        from transformers import MarianMTModel, MarianTokenizer
        name = MODELS[(self.src, self.tgt)]
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.tokenizer = MarianTokenizer.from_pretrained(name)
        model = MarianMTModel.from_pretrained(name)
        # transformers 5 non lega lm_head agli embedding quando i pesi sono solo in pytorch_model.bin
        # (opus-mt-tc-big-it-en): senza, il modello genera parole a caso
        if model.config.tie_word_embeddings:
            model.lm_head.weight = model.model.shared.weight
        self.model = model.to(self.device).eval()
        if self.device == "cuda":
            self.model = self.model.half()
        print(f"[traduzione] {name} su {self.device}", file=sys.stderr)

    def _run(self, sentences):
        import torch
        if self.model is None:
            self._load()
        out = []
        # per lunghezza: lotti con frasi simili, meno padding
        order = sorted(range(len(sentences)), key=lambda i: len(sentences[i]))
        done = [None] * len(sentences)
        for start in range(0, len(order), self.batch):
            idx = order[start:start + self.batch]
            enc = self.tokenizer([sentences[i] for i in idx], return_tensors="pt", padding=True, truncation=True,
                                 max_length=512).to(self.device)
            with torch.no_grad():
                gen = self.model.generate(**enc, num_beams=4, max_new_tokens=512)
            for i, text in zip(idx, self.tokenizer.batch_decode(gen, skip_special_tokens=True)):
                done[i] = text.strip()
            if (start // self.batch) % 20 == 0:
                print(f"[traduzione] {min(start + self.batch, len(order))}/{len(order)} frasi", file=sys.stderr)
        with open(self.cache_path, "a", encoding="utf-8") as f:
            for s, t in zip(sentences, done):
                k = self._key(s)
                self.cache[k] = t
                f.write(json.dumps({"k": k, "t": t}, ensure_ascii=False) + "\n")
                out.append(t)
        return out

    @staticmethod
    def _pieces(body):
        """[(marcatore, [frasi])] per riga."""
        pieces = []
        for line in body.split("\n"):
            marker = next((m for m in MARKERS if line.startswith(m)), "")
            text = line[len(marker):].strip()
            pieces.append((marker, [s for s in SENTENCE_SPLIT.split(text) if s.strip()] if text else []))
        return pieces

    def translate_many(self, bodies):
        """Traduzioni di [bodies] nello stesso ordine; None per una sezione con una frase non plausibile."""
        parsed = [self._pieces(b) for b in bodies]
        todo = sorted({s for p in parsed for _, ss in p for s in ss if self._key(s) not in self.cache})
        if todo:
            self._run(todo)
        result = []
        for pieces in parsed:
            lines, ok = [], True
            for marker, ss in pieces:
                outs = [self.cache[self._key(s)] for s in ss]
                if not all(_plausible(s, t) for s, t in zip(ss, outs)):
                    ok = False
                    break
                lines.append(marker + " ".join(outs))
            result.append("\n".join(lines) if ok else None)
        return result


if __name__ == "__main__":  # prova: python translate_sections.py it en "Testo da tradurre."
    t = Translator(sys.argv[1], sys.argv[2], Path(__file__).resolve().parent.parent / "data" / "sft" / "raw")
    print(t.translate_many([" ".join(sys.argv[3:])])[0])
