#!/usr/bin/env python3
"""LoRA SFT sul dataset di generate_sft_dataset.py (pilota: SmolLM2-135M).

Due backend, stessi iperparametri:
  (default)   transformers + peft, PyTorch ROCm puro
  --unsloth   kernel Unsloth (Triton); su Windows serve l'ambiente MSVC: usa train_auto.py
Sceglie da se' la GPU discreta (l'iGPU compare come device 0) dopo aver preso il lucchetto della GPU, e rispetta
HIP_VISIBLE_DEVICES/CUDA_VISIBLE_DEVICES gia' impostate (train_auto.py le imposta):
  python train_lora.py --max-steps 30
Pesi in bf16; --4bit (QLoRA, AMD e NVIDIA) solo per GPU con poca VRAM: Unsloth la sconsiglia per i Qwen3.5
(differenze di quantizzazione piu' alte del normale). Con --merge la LoRA si fonde nel modello base a piena precisione.

Scelte che contano per la qualita':
- loss solo sulla risposta dell'assistente (etichette del prompt = -100): il prompt e' ~80% dei token
  ed e' testo Wikivoyage da non imparare a memoria;
- split train/test per regione: lo stesso CONTESTO compare in piu' righe, con uno split casuale
  quasi tutto il test e' gia' nel train e la eval loss non dice nulla;
- righe piu' lunghe di --max-len scartate, non troncate (troncare taglierebbe la risposta);
- niente vision ne' thinking (text_only.py): i modelli vision-language si addestrano da una copia solo testo, e il
  chat template diventa il ChatML che manda l'app, senza blocco <think>; prima del training controlli brevi (copia con
  gli stessi tensori di testo dell'originale e caricata da transformers senza chiavi mancanti, embedding caricati
  uguali a quelli della copia, nessun parametro vision, testo di training uguale al ChatML dell'app) fermano lo
  script se qualcosa non torna;
- loss pesata per categoria (WeightedTrainer): SICUREZZA/SALUTE/DOGANE/TRASPORTI hanno 3-9x piu' righe
  delle altre (Viaggiare Sicuri le copre tutte e quattro); senza pesatura il modello vedrebbe soprattutto
  quelle. Il peso di ogni riga e' media_conteggio_categoria / conteggio_della_sua_categoria (nel train
  set), cosi' ogni categoria pesa in media 1 nella loss a prescindere da quante righe ha.
- --dataset sceglie il file dentro data/sft/ (default pocket_travel_sft.v10.it.jsonl di generate_sft.py,
  pubblicabile, senza Viaggiare Sicuri; un file .with-vs, generato con generate_sft_dataset.py --vs, e' SOLO
  per uso locale/personale). L'ATTRIBUTION gemella (stesso nome, ATTRIBUTION al posto di pocket_travel_sft
  e .tsv al posto di .jsonl) viene copiata come ATTRIBUTION.tsv in <out>/lora (e <out>/merged con --merge), cosi' upload_hf.py verifica le fonti
  usate per QUESTO training, non l'ultimo dataset generato.
"""
import argparse
import gc
import hashlib
import json
import math
import os
import random
import shutil
import subprocess
import sys
from collections import Counter
from pathlib import Path

from eval_common import TEST_REGIONS, chat_prompt_and_answer, question_of
import gpu_lock
from status import Progress, phase
from text_only import CHATML, check_chat_format, check_loaded_embeddings, check_no_vision, text_only_base

SFT_DIR = Path(__file__).resolve().parent.parent / "data" / "sft"


def last_complete_checkpoint(out):
    """Il checkpoint-N piu' recente con trainer_state.json (il Trainer lo scrive per ultimo): una cartella lasciata a
    meta' da un'interruzione durante il salvataggio non si riprende. None se non ce ne sono."""
    done = [c for c in Path(out).glob("checkpoint-*") if c.name[11:].isdigit() and (c / "trainer_state.json").is_file()]
    return str(max(done, key=lambda c: int(c.name[11:]))) if done else None


def non_finite_metric(logs):
    """La prima metrica del log del Trainer (loss, grad_norm, eval_loss) che non e' un numero finito, o None."""
    for key in ("loss", "grad_norm", "eval_loss"):
        value = logs.get(key)
        if value is not None and not math.isfinite(float(value)):
            return key
    return None


LORA = dict(r=16, lora_alpha=32, lora_dropout=0.0,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"])

ap = argparse.ArgumentParser()
ap.add_argument("--model", default="HuggingFaceTB/SmolLM2-135M-Instruct")
ap.add_argument("--unsloth", action="store_true", help="usa i kernel Unsloth")
ap.add_argument("--4bit", dest="four_bit", action="store_true",
                help="QLoRA 4-bit (solo con --unsloth; sconsigliata per i Qwen3.5, per GPU con poca VRAM)")
ap.add_argument("--max-steps", type=int, default=-1, help="-1 = usa --epochs")
ap.add_argument("--epochs", type=float, default=2)
ap.add_argument("--max-len", type=int, default=1024)
ap.add_argument("--lr", type=float, default=2e-4, help="learning rate (schedule cosine, 5 passi di warmup)")
ap.add_argument("--lora-r", type=int, default=LORA["r"], help="rango della LoRA")
ap.add_argument("--lora-alpha", type=int, default=LORA["lora_alpha"], help="alpha della LoRA")
ap.add_argument("--batch", type=int, default=4, help="esempi per passo sulla GPU (16 effettivi: cala se manca VRAM)")
ap.add_argument("--no-group-by-length", action="store_true",
                help="batch in ordine casuale invece che per lunghezza (come i training fino al 4B del 2026-09-26)")
ap.add_argument("--merge", action="store_true", help="salva anche i pesi con il LoRA fuso (per la conversione); lancia anche l'eval automatico (run_eval.py)")
ap.add_argument("--dataset", default="pocket_travel_sft.v10.it.jsonl",
                help="file dentro data/sft/ (default: il v10 italiano, pubblicabile; pocket_travel_sft.v10.en.jsonl per l'inglese)")
ap.add_argument("--out", default=str(SFT_DIR / "run-smollm2-135m"))
a = ap.parse_args()
gpu_lock.gpu_job("train_lora.py")
gpu_lock.pick_discrete_gpu("train_lora")  # dopo il lucchetto e prima di torch e Unsloth

# Unsloth 2026.10.8 compila ogni blocco del modello come una regione di torch.compile: sul Qwen3.5 con ROCm su Windows
# la prima loss esce enorme e dal secondo passo loss 0 e gradiente NaN (peft puro e senza compilazione: gradiente ~1).
# Unsloth la legge al caricamento del modello: impostata qui vale anche senza train_auto.py. Una variabile gia'
# impostata resta, per riprovare la compilazione con una versione nuova.
os.environ.setdefault("UNSLOTH_BLOCK_COMPILE", "0")
try:
    import unsloth  # noqa: F401  patch di compatibilita' Windows/ROCm (torch senza distributed), serve anche senza --unsloth
except ImportError:
    pass  # senza Unsloth resta solo il backend peft
import torch
from datasets import load_dataset
from peft import LoraConfig, get_peft_model
from transformers import (AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, Trainer,
                          TrainerCallback, TrainingArguments)

# bf16 nativo (stessa regola per NVIDIA e AMD): capability >= 8 (Ampere+; su ROCm gfx9+). Su Turing/Pascal is_bf16_supported() e' vero ma emulato (lento): meglio fp16
BF16 = torch.cuda.is_available() and torch.cuda.is_bf16_supported() and torch.cuda.get_device_capability()[0] >= 8

if a.four_bit and not a.unsloth:
    ap.error("--4bit richiede --unsloth")
LORA.update(r=a.lora_r, lora_alpha=a.lora_alpha)
# GTX 16xx (Turing senza tensor core): con Unsloth fp32 e' piu' veloce di fp16
# (misurato su GTX 1660 SUPER, Qwen3-0.6B: 52-55 s/passo in fp32 contro 98-100 in fp16, stessa VRAM di picco).
# Solo con Unsloth senza --4bit: fp32 con --4bit o col backend peft non e' stato misurato, li' resta fp16
FP32 = not BF16 and not a.four_bit and a.unsloth and torch.cuda.is_available() and "GTX 16" in torch.cuda.get_device_name()
DTYPE = torch.bfloat16 if BF16 else torch.float32 if FP32 else torch.float16

phase("base solo testo", a.model)
base = text_only_base(a.model)
phase("caricamento modello", f"{base} ({'Unsloth' if a.unsloth else 'peft'}, {'bf16' if BF16 else 'fp32' if FP32 else 'fp16'})")
if a.unsloth:
    from unsloth import FastLanguageModel
    model, tok = FastLanguageModel.from_pretrained(
        base, max_seq_length=a.max_len, load_in_4bit=a.four_bit, dtype=DTYPE)
    model = FastLanguageModel.get_peft_model(
        model, **LORA, use_gradient_checkpointing="unsloth", random_state=42)
    tok = getattr(tok, "tokenizer", tok)  # con un processor (modello vision-language) serve solo il tokenizer
else:
    tok = AutoTokenizer.from_pretrained(base)
    model = get_peft_model(AutoModelForCausalLM.from_pretrained(base, dtype=DTYPE),
                           LoraConfig(**LORA, task_type="CAUSAL_LM"))
tok.chat_template = CHATML
check_no_vision(model)
if Path(base).is_dir():
    check_loaded_embeddings(model, base)
# adapter_config.json e README della LoRA: l'id del modello, non la cartella locale della copia solo testo
model.peft_config["default"].base_model_name_or_path = a.model

class FreeCacheAfterEval(TrainerCallback):
    # Dopo l'eval la cache di PyTorch resta occupata: il loss fuso di Unsloth vede poca VRAM libera
    # e si rifiuta di partire ("No or negligible GPU memory") al primo step di training successivo.
    def on_evaluate(self, *args, **kwargs):
        torch.cuda.empty_cache()

class StatusLine(TrainerCallback):
    """Una riga leggibile al posto delle barre tqdm: passo, %, s/passo, tempo mancante, loss, epoca.
    La velocita' si misura dal secondo passo: il primo include compilazione dei kernel e avvio."""
    def on_train_begin(self, args, state, control, **kwargs):
        phase("training", f"{state.max_steps} passi, batch effettivo {args.per_device_train_batch_size * args.gradient_accumulation_steps}")
        self.p, self.loss, self.eval_loss = Progress("training", state.max_steps, "passo", every=60), None, None
        self.started = False
    def on_step_end(self, args, state, control, **kwargs):
        if not self.started:  # primo passo di questo processo (1, o quello dopo il checkpoint ripreso)
            self.p.mark_start(state.global_step)
            self.started = True
        self.p.update(state.global_step, self.extra(state))
    def on_log(self, args, state, control, logs=None, **kwargs):
        logs = logs or {}
        self.loss = logs.get("loss", self.loss)
        if "eval_loss" in logs:
            self.eval_loss = logs["eval_loss"]
            self.p.update(state.global_step, self.extra(state) + f" · valutazione intermedia in {logs.get('eval_runtime', 0):.0f}s", force=True)
    def extra(self, state):
        parts = [f"epoca {state.epoch:.2f}".replace(".", ",")] if state.epoch is not None else []
        if self.loss is not None:
            parts.append(f"loss {float(self.loss):.3f}".replace(".", ","))
        if self.eval_loss is not None:
            parts.append(f"eval {float(self.eval_loss):.3f}".replace(".", ","))
        return " · ".join(parts)

class StopOnNonFinite(TrainerCallback):
    """Ferma lo script alla prima loss o gradiente NaN/inf: da li' i pesi non si recuperano, e senza controllo il
    training andrebbe avanti per ore. Uscita con errore, senza salvare LoRA ne' merge."""
    def on_log(self, args, state, control, logs=None, **kwargs):
        key = non_finite_metric(logs or {})
        if key:
            sys.exit(f"{key} non finito al passo {state.global_step}: training fermato. I checkpoint in {args.output_dir} "
                     "hanno pesi non validi: cancella la cartella prima di rilanciare.")

class WeightedCollator:
    """Toglie 'weight' (scalare per riga) prima del collator standard, che sa impaginare solo i
    campi del tokenizer, poi lo riattacca come tensore: evita di far passare un campo extra dentro
    tokenizer.pad(), pensato per input_ids/attention_mask/labels."""
    def __init__(self, base):
        self.base = base
    def __call__(self, features):
        weight = torch.tensor([f.pop("weight") for f in features], dtype=torch.float32)
        batch = self.base(features)
        batch["weight"] = weight
        return batch

class WeightedTrainer(Trainer):
    """Loss pesata per categoria: bypassa il loss interno del modello (che farebbe una media sui
    token di tutto il batch, senza distinzione di riga) e lo ricalcola per riga, poi fa la media
    pesata sulle righe. Con Unsloth si perde il kernel di loss fuso (serve avere i logits)."""
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        # compute_loss non usa num_items_in_batch: senza questo il Trainer non divide il loss per
        # gradient_accumulation_steps (i modelli Qwen accettano **kwargs) e gradienti e loss mostrato
        # risultano 16/batch volte piu' grandi (docstring di Trainer.compute_loss)
        self.model_accepts_loss_kwargs = False

    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        weight = inputs.pop("weight")
        labels = inputs.pop("labels")
        # Logits solo dalla posizione prima del primo token di risposta del batch (logits_to_keep): il prompt e'
        # ~80% dei token e la testa di output (vocabolario Qwen3.5 ~248k) ne e' una parte grossa del calcolo.
        width = labels.shape[1]
        first = int(labels.ne(-100).int().argmax(1).min().clamp(min=1))
        keep = width - first + 1
        logits = model(**inputs, logits_to_keep=keep).logits
        if logits.shape[1] != keep:  # modello che ignora logits_to_keep: stessa finestra dai logits completi
            logits = logits[:, width - keep:, :]
        shift_labels = labels[:, width - keep + 1:]
        mask = shift_labels.ne(-100)
        # Cross-entropy solo sui token della risposta (~20%): il prompt e' mascherato, calcolarla su tutte
        # le posizioni (vocabolario Qwen3.5 ~248k) e poi azzerarla costerebbe ~5 volte il necessario, piu' una
        # copia intera dei logits (.contiguous()). Stesso risultato: loss per token sommata nella sua riga.
        per_token = torch.nn.functional.cross_entropy(logits[:, :-1, :][mask], shift_labels[mask], reduction="none")
        rows = mask.nonzero(as_tuple=True)[0]  # riga di ogni token di risposta, nello stesso ordine di [mask]
        per_example = (torch.zeros(len(labels), device=per_token.device, dtype=per_token.dtype)
                       .index_add(0, rows, per_token) / mask.sum(1).clamp(min=1))
        loss = (per_example * weight).sum() / weight.sum()
        return (loss, {"logits": logits}) if return_outputs else loss

def encode(r):
    prompt, answer = chat_prompt_and_answer(tok, r["messages"])
    prompt_ids = tok(prompt, add_special_tokens=False)["input_ids"]
    answer_ids = tok(answer, add_special_tokens=False)["input_ids"]
    return {"input_ids": prompt_ids + answer_ids, "attention_mask": [1] * (len(prompt_ids) + len(answer_ids)),
            "labels": [-100] * len(prompt_ids) + answer_ids}

phase("preparazione dataset", a.dataset)
dataset_path = SFT_DIR / a.dataset
# ATTRIBUTION gemella del dataset (pocket_travel_sft.v10.it.jsonl -> ATTRIBUTION.v10.it.tsv, come li scrive
# generate_sft.py; lo stesso con il suffisso ".with-vs"): copiata nella cartella dei pesi salvati, cosi' upload_hf.py puo'
# verificare le fonti usate per QUESTO training, non solo l'ultimo dataset generato in data/sft/.
attribution_path = SFT_DIR / a.dataset.replace("pocket_travel_sft", "ATTRIBUTION").replace(".jsonl", ".tsv")
ds = load_dataset("json", data_files=str(dataset_path), split="train")
check_chat_format(tok, ds.shuffle(seed=42).select(range(min(50, len(ds)))))
ds = ds.map(encode).filter(lambda r: len(r["input_ids"]) <= a.max_len)
held_out = TEST_REGIONS
train_ds = ds.filter(lambda r: r["region"] not in held_out)
test_ds = ds.filter(lambda r: r["region"] in held_out)

# peso per categoria = conteggio medio / conteggio della categoria (nel train set): ogni categoria
# pesa in media 1 nella loss, a prescindere da quante righe ha (SICUREZZA/SALUTE/DOGANE/TRASPORTI
# ne hanno 3-9x piu' delle altre, coperte anche da Viaggiare Sicuri)
cat_count = Counter(train_ds["category"])
cat_weight = {c: len(train_ds) / (len(cat_count) * n) for c, n in cat_count.items()}
print("pesi per categoria:", {c: round(w, 2) for c, w in sorted(cat_weight.items())})
add_weight = lambda r: {"weight": cat_weight[r["category"]]}
train_ds, test_ds = train_ds.map(add_weight), test_ds.map(add_weight)

cols = ["input_ids", "attention_mask", "labels", "weight"]
print(f"righe: train={len(train_ds)} test={len(test_ds)} (regioni di test: {sorted(held_out)})")

# Esempi di lunghezza simile nello stesso batch: meno padding (i contesti vanno da poche righe a
# --max-len token). transformers 5 usa train_sampling_strategy, le versioni 4.x group_by_length.
length_grouping = {} if a.no_group_by_length else (
    {"train_sampling_strategy": "group_by_length"} if "train_sampling_strategy" in TrainingArguments.__dataclass_fields__
    else {"group_by_length": True})
trainer = WeightedTrainer(
    model=model, train_dataset=train_ds.select_columns(cols), eval_dataset=test_ds.select_columns(cols),
    data_collator=WeightedCollator(DataCollatorForSeq2Seq(tok, padding=True, label_pad_token_id=-100)),
    callbacks=[FreeCacheAfterEval(), StatusLine(), StopOnNonFinite()],
    args=TrainingArguments(
        bf16=BF16, fp16=not BF16 and not FP32, per_device_train_batch_size=a.batch, gradient_accumulation_steps=16 // a.batch,
        # eval con lo stesso batch del training: il default (8) con vocabolari grandi (Qwen3.5 ~248k token)
        # supera la VRAM e Windows riversa in RAM di sistema (picco 17 GB su 12 con Qwen3.5-0.8B)
        per_device_eval_batch_size=a.batch,
        prediction_loss_only=True,  # della valutazione intermedia serve solo eval_loss, non i logits delle righe
        learning_rate=a.lr, lr_scheduler_type="cosine", warmup_steps=5,
        num_train_epochs=a.epochs, max_steps=a.max_steps,
        # eval ogni 200 passi (~20 s l'una con logits_to_keep): serve solo a vedere l'andamento di eval_loss
        logging_steps=5, eval_strategy="steps", eval_steps=200,
        # checkpoint ogni 100 passi (solo l'ultimo): un training interrotto riparte da li' rilanciando
        # lo stesso comando, invece che da zero
        output_dir=a.out, save_strategy="steps", save_steps=100, save_total_limit=1, report_to="none", seed=42,
        **length_grouping,
        disable_tqdm=True,  # sostituite da StatusLine: le barre tqdm nei log su file diventano illeggibili
        remove_unused_columns=False))  # altrimenti Trainer toglie 'weight': non e' un argomento di model.forward
# Si riprende un checkpoint solo se e' dello stesso training: con un altro modello, dataset o
# iperparametri il Trainer riprenderebbe lo stato (passi, scheduler) del training vecchio.
run_key = {"model": a.model, "dataset": a.dataset, "dataset_sha256": hashlib.sha256(dataset_path.read_bytes()).hexdigest(),
           "max_len": a.max_len, "batch": a.batch, "group_by_length": not a.no_group_by_length, "epochs": a.epochs, "max_steps": a.max_steps, "four_bit": a.four_bit,
           "lr": a.lr, "lora_r": a.lora_r, "lora_alpha": a.lora_alpha, "text_only_chatml": True}
run_key_path = Path(a.out) / "run.json"
last_checkpoint = last_complete_checkpoint(a.out)
if last_checkpoint:
    if not run_key_path.exists() or json.loads(run_key_path.read_text(encoding="utf-8")) != run_key:
        sys.exit(f"{last_checkpoint} e' di un training diverso (modello, dataset o parametri): cancellalo o usa un altro --out")
    phase("ripresa", last_checkpoint)
Path(a.out).mkdir(parents=True, exist_ok=True)
run_key_path.write_text(json.dumps(run_key, indent=2), encoding="utf-8")
trainer.train(resume_from_checkpoint=last_checkpoint)
print("peak VRAM GiB:", round(torch.cuda.max_memory_allocated() / 2**30, 2))

phase("salvataggio LoRA", a.out + "/lora")
model.save_pretrained(a.out + "/lora")
tok.save_pretrained(a.out + "/lora")
for checkpoint in Path(a.out).glob("checkpoint-*"):  # servivano solo alla ripresa: LoRA finale salvato
    shutil.rmtree(checkpoint)

def copy_attribution(dst):
    if attribution_path.exists():
        shutil.copy(attribution_path, Path(dst) / "ATTRIBUTION.tsv")
    else:
        print(f"ATTENZIONE: {attribution_path} non trovato, ATTRIBUTION.tsv non copiato in {dst} "
              "(upload_hf.py non potra' verificare le fonti di questo training)", file=sys.stderr)
copy_attribution(a.out + "/lora")

# Controllo a occhio sul test: 2 positivi e 2 negativi (il comportamento, non solo la loss)
phase("controllo a campione", "2 positivi e 2 negativi del test")
model.eval()
for kind in ("pos", "pos", "neg", "neg"):
    r = random.Random().choice([x for x in test_ds if x["kind"] == kind])
    ids = tok(chat_prompt_and_answer(tok, r["messages"][:1])[0], add_special_tokens=False,
              return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(**ids, max_new_tokens=120, do_sample=False)
    print(f"\n[{kind}] {question_of(r).strip()}")
    print("  atteso:", r["messages"][1]["content"][:160])
    print("  ottenuto:", tok.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True)[:160])
print("LoRA salvato in", a.out + "/lora")
# Il merge modifica il modello sul posto: va dopo il controllo a occhio
def merge_full_precision(base, lora_dir, out, dtype):
    """Fonde la LoRA in una copia del modello base a piena precisione, caricata su CPU. Serve con --4bit:
    merge_and_unload sul modello a 4 bit riquantizza i pesi fusi in nf4 e salva un modello bitsandbytes,
    che convert_hf_to_gguf.py non converte ("Quant method is not yet supported: 'bitsandbytes'")."""
    from peft import PeftModel
    full, info = AutoModelForCausalLM.from_pretrained(base, dtype=dtype, output_loading_info=True)
    if info["missing_keys"]:
        sys.exit(f"Pesi non trovati in {base}: {sorted(info['missing_keys'])[:5]}")
    PeftModel.from_pretrained(full, lora_dir).merge_and_unload().save_pretrained(out)

if a.merge:
    phase("merge", a.out + "/merged")
    if a.four_bit:
        merge_full_precision(base, a.out + "/lora", a.out + "/merged", DTYPE)
    else:
        model.merge_and_unload().save_pretrained(a.out + "/merged")
    tok.save_pretrained(a.out + "/merged")
    copy_attribution(a.out + "/merged")
# Libera la VRAM prima dell'eval (processo figlio) e dell'uscita: con ROCm su Windows il processo
# a volte resta appeso in chiusura e il driver continua a riservargli la memoria occupata (visto:
# 5,8 GB tenuti dal training del 2B finito, il 4B successivo e' finito in memoria condivisa, 12x piu' lento)
del model, trainer
gc.collect()
torch.cuda.empty_cache()
if a.merge:
    phase("eval automatico", "run_eval.py sul modello fuso")
    # --max-vram-held 100: la VRAM ancora tenuta da questo processo (contesto GPU) non e' di "altri"
    result = subprocess.run([sys.executable, str(Path(__file__).parent / "run_eval.py"), a.out + "/merged",
                             "--max-vram-held", "100"])
    if result.returncode != 0:
        print("-- eval automatico fallito (training comunque completato)", file=sys.stderr)
# Uscita senza la chiusura di Python (atexit, distruttori di torch/HIP), dove con ROCm su Windows il
# processo a volte resta appeso: chi lo lancia (train_auto.py, una catena di training) aspetterebbe per sempre.
sys.stdout.flush()
sys.stderr.flush()
os._exit(0)
