"""Base del training senza vision e senza thinking, con i controlli che train_lora.py esegue prima di addestrare.

- Vision: i Qwen3.5 sono modelli vision-language (Qwen3_5ForConditionalGeneration, torre `model.visual.*`) e l'app usa
  solo il testo. text_only_base() ne salva una copia solo testo (config di AutoModelForCausalLM, cioe'
  Qwen3_5ForCausalLM, e i tensori del testo dei file originali, senza torre visiva ne' layer MTP) in
  HF_HOME/pocket-travel-text-only/, la prima volta, e la controlla tensore per tensore contro l'originale: training,
  merge e GGUF non caricano ne' salvano piu' la torre visiva.
- Thinking: CHATML sostituisce il chat template del modello. E' il testo che manda ai_chat.cpp (formatter CHATML di
  llama.cpp, use_jinja=false), senza blocchi <think> ne' la variabile enable_thinking: il template salvato con il LoRA
  e i pesi fusi finisce nel GGUF, e app, convert_gguf.py ed eval_gguf.py, che aggiungono il blocco <think> vuoto solo
  ai template con enable_thinking, non lo aggiungono.
"""
import hashlib
import re
import shutil
import sys
from contextlib import ExitStack
from pathlib import Path

from eval_common import chat_prompt_and_answer, chatml_answer, chatml_prompt

# Con trim_blocks e lstrip_blocks di transformers: nessun a capo subito dopo un tag {% %}, che verrebbe tolto
CHATML = ("{% for m in messages %}<|im_start|>{{ m['role'] }}\n{{ m['content'] }}<|im_end|>\n{% endfor %}"
          "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}")
VISION = re.compile(r"visual|vision")
DONE = "text-only.txt"  # scritto dopo i controlli: contiene la cartella dell'originale da cui e' stata fatta la copia


def fail(message):
    sys.exit(f"Controllo prima del training fallito: {message}")


def vision_names(names):
    """Nomi di parametri o tensori della torre visiva."""
    return [n for n in names if VISION.search(n)]


def text_names(names):
    """Tensori del testo: senza torre visiva ne' MTP (che il merge non salva e l'app non usa)."""
    return [n for n in names if not VISION.search(n) and not n.startswith("mtp.")]


def check_no_vision(model):
    """Il modello caricato per il training non ha parametri della torre visiva."""
    if left := vision_names(n for n, _ in model.named_parameters()):
        fail(f"il modello ha ancora {len(left)} parametri vision (es. {left[0]})")


def check_chat_format(tok, rows):
    """Il testo del training e' il ChatML che manda l'app, senza blocco <think>: prompt fino all'apertura del turno
    assistente, risposta del dataset chiusa da <|im_end|>, che deve essere un token unico (la fine della risposta)."""
    if re.search(r"enable_thinking|<think>", tok.chat_template or ""):
        fail("il chat template ha ancora il thinking")
    if len(tok("<|im_end|>", add_special_tokens=False)["input_ids"]) != 1:
        fail("<|im_end|> non e' un token unico per questo tokenizer: il modello non e' ChatML")
    for r in rows:
        user, answer = (m["content"] for m in r["messages"][:2])
        got = chat_prompt_and_answer(tok, r["messages"])
        want = (chatml_prompt(user), chatml_answer(answer))
        if got != want:
            fail(f"testo di training diverso dal ChatML dell'app\n  atteso: {want!r:.300}\n  ottenuto: {got!r:.300}")


def _open_tensors(stack, folder):
    """Nome -> file aperto (safe_open) per i tensori di una cartella: letti uno alla volta, non tutti in memoria."""
    from safetensors import safe_open
    handles = {}
    for f in sorted(Path(folder).glob("*.safetensors")):
        s = stack.enter_context(safe_open(str(f), "pt"))
        handles.update(dict.fromkeys(s.keys(), s))
    return handles


def check_same_text_weights(source, copy):
    """La copia ha tutti e soli i tensori di testo dell'originale, con gli stessi nomi, valori e dtype."""
    import torch
    with ExitStack() as stack:
        src, dst = _open_tensors(stack, source), _open_tensors(stack, copy)
        expected = set(text_names(src))
        if set(dst) != expected:
            fail(f"copia solo testo con tensori mancanti {sorted(expected - set(dst))[:5]}, in piu' {sorted(set(dst) - expected)[:5]}")
        for name in sorted(expected):
            a, b = dst[name].get_tensor(name), src[name].get_tensor(name)
            if a.dtype != b.dtype or not torch.equal(a, b):
                fail(f"{name} della copia solo testo diverso dall'originale")


def check_loaded_embeddings(model, base):
    """I pesi caricati per il training sono quelli della copia solo testo (embedding uguali a quelli del file): un
    loader che non riconosce i nomi dei tensori li inizializzerebbe a caso con un solo avviso."""
    import torch
    with ExitStack() as stack:
        files = _open_tensors(stack, base)
        name = next(n for n in files if n.endswith("embed_tokens.weight"))
        expected = files[name].get_tensor(name)
    got = model.get_input_embeddings().weight.detach().cpu()
    if not torch.equal(got, expected.to(got.dtype)):
        fail(f"gli embedding caricati non sono quelli di {name} in {base}: pesi non caricati dalla copia solo testo")


def text_only_base(model_id):
    """Cartella o id da cui addestrare: model_id se non ha la torre visiva, altrimenti la sua copia solo testo,
    creata e controllata la prima volta (di nuovo se l'originale nella cache di HuggingFace cambia revisione)."""
    from huggingface_hub import constants, snapshot_download
    from transformers import AutoConfig, AutoModelForCausalLM, AutoTokenizer
    if getattr(AutoConfig.from_pretrained(model_id), "vision_config", None) is None:
        return model_id
    local = Path(model_id).is_dir()
    source = model_id if local else snapshot_download(model_id)
    name = f"{Path(model_id).name}-{hashlib.sha1(str(Path(model_id).resolve()).encode()).hexdigest()[:8]}" if local \
        else model_id.replace("/", "--")
    copy = Path(constants.HF_HOME) / "pocket-travel-text-only" / name
    if (copy / DONE).exists() and (copy / DONE).read_text(encoding="utf-8") == str(source):
        return str(copy)
    from safetensors.torch import save_file
    shutil.rmtree(copy, ignore_errors=True)  # copia interrotta o di un'altra revisione: nessun file vecchio resta
    # Il modello caricato da transformers da' solo la config solo testo (classe e text_config): i suoi pesi no, perche'
    # dtype="auto" porta in bf16 anche i tensori che il checkpoint tiene in float32 (A_log e norme di linear_attn
    # dei Qwen3.5). I tensori si copiano quindi dai file originali, con nomi e dtype invariati.
    model = AutoModelForCausalLM.from_pretrained(source, dtype="auto")
    if left := vision_names(n for n, _ in model.named_parameters()):
        sys.exit(f"AutoModelForCausalLM carica anche la torre visiva di {model_id} (es. {left[0]}): serve una versione "
                 "di transformers che lo carichi solo testo (Qwen3_5ForCausalLM per i Qwen3.5)")
    model.config.architectures = [type(model).__name__]  # come save_pretrained: convert_hf_to_gguf.py la legge
    model.config.save_pretrained(copy)
    model.generation_config.save_pretrained(copy)
    del model
    AutoTokenizer.from_pretrained(source).save_pretrained(copy)
    with ExitStack() as stack:
        files = _open_tensors(stack, source)
        save_file({n: files[n].get_tensor(n) for n in text_names(files)}, str(copy / "model.safetensors"),
                  metadata={"format": "pt"})
    check_same_text_weights(source, copy)
    _, info = AutoModelForCausalLM.from_pretrained(copy, dtype="auto", output_loading_info=True)
    if info["missing_keys"] or info["unexpected_keys"]:
        fail(f"transformers non carica la copia solo testo: mancano {sorted(info['missing_keys'])[:5]}, "
             f"in piu' {sorted(info['unexpected_keys'])[:5]}")
    (copy / DONE).write_text(str(source), encoding="utf-8")
    return str(copy)
