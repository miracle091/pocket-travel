#!/usr/bin/env python3
"""DPO con LoRA su un modello gia' addestrato con train_lora.py (cartella merged), sui punti deboli del test esteso.

Uso (stessa GPU e stesso ambiente di train_lora.py; HIP_VISIBLE_DEVICES sulla sola GPU discreta):
  python dpo_lora.py --base <run> --dataset pocket_travel_sft.v10.it.jsonl --out <cartella> [--merge]

Coppie (risposta preferita, risposta scartata) dalle righe del dataset SFT, fuori dalle regioni di test (TEST_REGIONS) e
da quelle del test esteso:
  neg_city   rifiuto di una citta' (categoria assente) contro la prima frase del contesto: l'errore tipico dei modelli
             e' rispondere con una frase di un'altra sezione invece di rifiutare;
  pos_city   risposta di una citta' contro il rifiuto della stessa categoria: bilancia neg_city, cosi' il modello non
             impara a rifiutare tutte le domande sulle citta';
  pos_near   risposta con il blocco "Punti di interesse entro..." (VICINO) contro il rifiuto;
  neg_near   rifiuto VICINO contro la prima riga del blocco.
La loss DPO e' scritta qui (log-sigmoide di beta per la differenza dei log-rapporti) e non usa il DPOTrainer di TRL, che
nell'ambiente di training non si importa: il modello di riferimento e' lo stesso modello con la LoRA spenta. Le
log-probabilita' si sommano solo sui token della risposta, nello stesso formato di prompt di train_lora.py.
"""
import argparse
import json
import math
import random
import re
import sys
from pathlib import Path

from eval_common import SFT_DIR, TEST_REGIONS, chat_prompt_and_answer, extended_file
from text_only import text_only_base

GROUPS = ("neg_city", "pos_city", "pos_near", "neg_near")


def context_of(question):
    """Testo tra "CONTESTO:" e "DOMANDA:" del messaggio utente."""
    match = re.search(r"CONTESTO:\s*(.*?)\n\nDOMANDA:", question, re.S)
    return match.group(1) if match else ""


def first_sentence(context):
    """Prima frase del contesto che non sia un titolo ("▸ ...") o una riga vuota, al piu' 200 caratteri."""
    for line in context.splitlines():
        line = line.strip().lstrip("•").strip()
        if not line or line.startswith("▸"):
            continue
        sentence = re.split(r"(?<=[.!?])\s", line, maxsplit=1)[0]
        return sentence[:200]
    return ""


def group_of(row):
    city = row["region"].startswith("citta:")
    near = row["category"] == "VICINO"
    if near:
        return f"{row['kind']}_near"
    if city:
        return f"{row['kind']}_city"
    return None


def dpo_margin(policy_chosen, policy_rejected, reference_chosen, reference_rejected):
    """Differenza dei log-rapporti tra modello e riferimento: positiva quando il modello, rispetto al riferimento,
    preferisce la risposta preferita. Vale per numeri e per tensori."""
    return (policy_chosen - reference_chosen) - (policy_rejected - reference_rejected)


def dpo_loss(margin, beta):
    """Loss DPO, -log sigmoide(beta * margine), scritta come softplus(-beta * margine) per la stabilita' numerica.
    Numero per un numero, tensore per un tensore."""
    if isinstance(margin, (int, float)):
        x = -beta * margin
        return max(x, 0.0) + math.log1p(math.exp(-abs(x)))
    import torch.nn.functional as F
    return F.softplus(-beta * margin)


def leftover_scale(pending, accum):
    """Fattore per i gradienti di un passo con meno di `accum` coppie: la loss e' gia' divisa per accum, e il passo
    deve valere la media delle coppie presenti."""
    return accum / pending


def build_pairs(rows, excluded_regions, per_group, seed=42):
    """{gruppo: [(messaggi del prompt, preferita, scartata)]}, al piu' per_group[gruppo] coppie per gruppo."""
    rows = [r for r in rows if r["region"] not in excluded_regions and r["region"] not in TEST_REGIONS]
    refusals = {}
    for r in rows:
        if r["kind"] == "neg":
            refusals.setdefault(r["category"], r["messages"][1]["content"])
    fallback = next(iter(refusals.values()), "")
    pairs = {g: [] for g in GROUPS}
    for r in rows:
        group = group_of(r)
        if group not in pairs:
            continue
        prompt, answer = r["messages"][:1], r["messages"][1]["content"]
        if r["kind"] == "neg":
            rejected = first_sentence(context_of(prompt[0]["content"]))
            chosen = answer
        else:
            rejected = refusals.get(r["category"], fallback)
            chosen = answer
        if chosen and rejected and chosen != rejected:
            pairs[group].append((prompt, chosen, rejected))
    rng = random.Random(seed)
    for group, items in pairs.items():
        rng.shuffle(items)
        del items[per_group.get(group, len(items)):]
    return pairs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="cartella di un run di train_lora.py (run.json e lora/)")
    ap.add_argument("--dataset", default="pocket_travel_sft.v10.it.jsonl", help="file dentro data/sft/")
    ap.add_argument("--out", required=True)
    ap.add_argument("--beta", type=float, default=0.1)
    ap.add_argument("--lr", type=float, default=2e-5)
    ap.add_argument("--epochs", type=int, default=1)
    ap.add_argument("--accum", type=int, default=16, help="coppie per passo dell'ottimizzatore")
    ap.add_argument("--max-len", type=int, default=1024)
    ap.add_argument("--per-group", default="neg_city=800,pos_city=800,pos_near=400,neg_near=200",
                    help="coppie al piu' per gruppo")
    ap.add_argument("--merge", action="store_true", help="salva anche i pesi con la LoRA fusa (per convert_gguf.py)")
    a = ap.parse_args()

    import gpu_lock
    from status import Progress, phase
    gpu_lock.gpu_job("dpo_lora.py")
    try:
        import unsloth  # noqa: F401  patch di compatibilita' Windows/ROCm, come in train_lora.py
    except ImportError:
        pass
    import torch
    import torch.nn.functional as F
    from peft import LoraConfig, PeftModel, get_peft_model
    from transformers import AutoConfig, AutoModelForCausalLM, AutoModelForImageTextToText, AutoTokenizer

    per_group = {k: int(v) for k, v in (item.split("=") for item in a.per_group.split(","))}
    rows = [json.loads(line) for line in (SFT_DIR / a.dataset).open(encoding="utf-8")]
    excluded = {json.loads(line)["region"] for line in (SFT_DIR / extended_file(a.dataset)).open(encoding="utf-8")}
    pairs = build_pairs(rows, excluded, per_group)
    print("coppie:", {g: len(p) for g, p in pairs.items()})
    data = [p for items in pairs.values() for p in items]
    random.Random(42).shuffle(data)

    bf16 = torch.cuda.is_bf16_supported()
    dtype = torch.bfloat16 if bf16 else torch.float16
    # Modello di partenza: il modello base del run con la sua LoRA fusa. Non la cartella merged: Unsloth 2026.9.7 (quello
    # dei modelli v10) la salva per Qwen3.5 con nomi annidati (model.language_model.language_model.language_model.*), che
    # transformers scarta senza errori, lasciando pesi casuali.
    run = json.loads((Path(a.base) / "run.json").read_text(encoding="utf-8"))
    # Run senza vision ne' thinking (text_only.py): la LoRA e' della copia solo testo, non del modello vision-language
    base_model = text_only_base(run["model"]) if run.get("text_only_chatml") else run["model"]
    phase("caricamento modello", f"{base_model} + {a.base}/lora")
    tok = AutoTokenizer.from_pretrained(Path(a.base) / "lora")
    tok = getattr(tok, "tokenizer", tok)
    # Qwen3.5 e' multimodale: i pesi sono model.language_model.*, che AutoModelForCausalLM non riconosce
    # (come in eval_behavior.py). La LoRA va solo sul modello del testo, non sulla parte visiva.
    config = AutoConfig.from_pretrained(base_model)
    auto = AutoModelForImageTextToText if hasattr(config, "vision_config") else AutoModelForCausalLM
    model, info = auto.from_pretrained(base_model, config=config, dtype=dtype, output_loading_info=True)
    if info["missing_keys"]:
        sys.exit(f"Pesi non trovati in {base_model}: {sorted(info['missing_keys'])[:5]}")
    model = PeftModel.from_pretrained(model, Path(a.base) / "lora").merge_and_unload().cuda()
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(r=16, lora_alpha=32, lora_dropout=0.0, task_type="CAUSAL_LM",
                                             target_modules=r"^(?!.*visual).*\.(q_proj|k_proj|v_proj|o_proj|gate_proj|up_proj|down_proj)$"))

    def encode(prompt_messages, answer):
        prompt, _ = chat_prompt_and_answer(tok, prompt_messages)
        prompt_ids = tok(prompt, add_special_tokens=False)["input_ids"]
        answer_ids = tok(answer + (tok.eos_token or ""), add_special_tokens=False)["input_ids"]
        return prompt_ids + answer_ids, len(prompt_ids)

    def answer_logps(ids_list, starts):
        """Somma delle log-probabilita' dei token della risposta, per ogni sequenza (batch con padding a destra)."""
        width = max(len(ids) for ids in ids_list)
        pad = tok.pad_token_id if tok.pad_token_id is not None else 0
        input_ids = torch.tensor([ids + [pad] * (width - len(ids)) for ids in ids_list], device="cuda")
        attention = torch.tensor([[1] * len(ids) + [0] * (width - len(ids)) for ids in ids_list], device="cuda")
        mask = torch.zeros_like(input_ids, dtype=torch.bool)
        for i, (ids, start) in enumerate(zip(ids_list, starts)):
            mask[i, start:len(ids)] = True
        # logits solo dalla posizione prima della risposta (logits_to_keep), come in train_lora.py
        keep = width - min(starts) + 1
        logits = model(input_ids=input_ids, attention_mask=attention, logits_to_keep=keep).logits
        if logits.shape[1] != keep:
            logits = logits[:, width - keep:, :]
        logits = logits[:, :-1, :]
        input_ids, mask = input_ids[:, width - keep:], mask[:, width - keep:]
        target_mask = mask[:, 1:]
        # solo le posizioni della risposta: il vocabolario di Qwen3.5 (~248k) su tutte le posizioni non sta in VRAM
        picked = F.log_softmax(logits[target_mask].float(), dim=-1).gather(1, input_ids[:, 1:][target_mask][:, None])[:, 0]
        rows_of = target_mask.nonzero(as_tuple=True)[0]
        return torch.zeros(len(ids_list), device="cuda").index_add(0, rows_of, picked)

    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=a.lr)
    total = a.epochs * math.ceil(len(data) / a.accum)
    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, lambda step: max(0.0, 1 - step / max(total, 1)))
    phase("DPO", f"{len(data)} coppie, {total} passi, beta {a.beta}, lr {a.lr}")
    progress = Progress("DPO", total, "passo", every=60)
    model.train()
    step, losses, wins = 0, [], []

    def optimizer_step(pending):
        nonlocal step
        if pending < a.accum:
            for p in model.parameters():
                if p.grad is not None:
                    p.grad *= leftover_scale(pending, a.accum)
        optimizer.step()
        scheduler.step()
        optimizer.zero_grad()
        step += 1
        recent = slice(-a.accum * 10, None)
        progress.update(step, f"loss {sum(losses[recent]) / len(losses[recent]):.3f} · "
                              f"preferita sopra {100 * sum(wins[recent]) / len(wins[recent]):.0f}%".replace(".", ","))

    for _ in range(a.epochs):
        pending = 0  # coppie accumulate dall'ultimo passo dell'ottimizzatore
        for prompt_messages, chosen, rejected in data:
            (chosen_ids, start), (rejected_ids, _) = encode(prompt_messages, chosen), encode(prompt_messages, rejected)
            if max(len(chosen_ids), len(rejected_ids)) > a.max_len:
                continue
            policy = answer_logps([chosen_ids, rejected_ids], [start, start])
            with torch.no_grad(), model.disable_adapter():
                reference = answer_logps([chosen_ids, rejected_ids], [start, start])
            margin = dpo_margin(policy[0], policy[1], reference[0], reference[1])
            loss = dpo_loss(margin, a.beta)
            (loss / a.accum).backward()
            losses.append(loss.item())
            wins.append(float(margin.item() > 0))
            pending += 1
            if pending == a.accum:
                optimizer_step(pending)
                pending = 0
        if pending:  # coppie rimaste a fine epoca: un passo a parte, senza trascinarle nell'epoca successiva
            optimizer_step(pending)
    print("peak VRAM GiB:", round(torch.cuda.max_memory_allocated() / 2**30, 2))
    Path(a.out).mkdir(parents=True, exist_ok=True)
    (Path(a.out) / "run.json").write_text(json.dumps({"base": a.base, "dataset": a.dataset, "beta": a.beta, "lr": a.lr,
                                                      "epochs": a.epochs, "pairs": {g: len(p) for g, p in pairs.items()}},
                                                     indent=2), encoding="utf-8")
    phase("salvataggio LoRA", a.out + "/lora")
    model.save_pretrained(a.out + "/lora")
    tok.save_pretrained(a.out + "/lora")
    if a.merge:
        phase("merge", a.out + "/merged")
        model.merge_and_unload().save_pretrained(a.out + "/merged")
        tok.save_pretrained(a.out + "/merged")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
