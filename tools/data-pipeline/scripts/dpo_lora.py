#!/usr/bin/env python3
"""DPO con LoRA su un modello gia' addestrato con train_lora.py (cartella merged), sui punti deboli del test esteso.

Uso (stesso ambiente di train_lora.py; GPU discreta scelta come in train_lora.py):
  python dpo_lora.py --base <run> --dataset pocket_travel_sft.v11.it.jsonl --out <cartella> [--merge]

Coppie (risposta preferita, risposta scartata) dalle righe del dataset SFT, fuori dalle regioni di test (TEST_REGIONS) e
da quelle del test esteso:
  neg_city   rifiuto di una citta' (categoria assente) contro la prima frase del contesto: l'errore tipico dei modelli
             e' rispondere con una frase di un'altra sezione invece di rifiutare;
  pos_city   risposta di una citta' contro il rifiuto della stessa categoria: bilancia neg_city, cosi' il modello non
             impara a rifiutare tutte le domande sulle citta';
  pos_near   risposta con il blocco "Punti di interesse entro..." (VICINO) contro il rifiuto;
  neg_near   rifiuto VICINO contro la prima riga del blocco.
La loss DPO e' scritta qui (log-sigmoide di beta per la differenza dei log-rapporti) e non usa il DPOTrainer di TRL:
il modello di riferimento e' lo stesso modello con la LoRA spenta. Le
log-probabilita' si sommano solo sui token della risposta, nello stesso formato di prompt di train_lora.py.

Con --loss kto --onpolicy <file di collect_onpolicy.py>, invece delle coppie, KTO (arXiv 2402.01306, come il KTOTrainer
di TRL) sugli errori del modello stesso: per ogni prompt con almeno una risposta sbagliata, le risposte sbagliate
(al piu' --undesirable-per-prompt, diverse tra loro) sono indesiderate, la risposta del dataset e una risposta giusta del
modello, se diversa, desiderate; --refusal-anchors N aggiunge come desiderate le risposte del dataset di N prompt da
rifiutare che il modello rifiuta gia', perche' gli errori sono per lo piu' prompt a cui rispondere. I pesi delle due classi le portano a 1:1. Il riferimento z0 (stima del KL) e' la media,
sul gruppo di --accum esempi, del log-rapporto di ogni prompt con la risposta dell'esempio dopo, senza gradiente e mai
sotto zero. lr consigliata da TRL 5e-7 - 5e-6:
  python dpo_lora.py --base <run> --dataset pocket_travel_sft.v11.it.jsonl --loss kto --onpolicy <file> --lr 5e-6 \\
                     --out <cartella> --merge
"""
import argparse
import json
import math
import random
import re
import sys
from pathlib import Path

from eval_common import SFT_DIR, TEST_REGIONS, chat_prompt_and_answer, context_of, extended_file, read_jsonl_lenient
from text_only import text_only_base

GROUPS = ("neg_city", "pos_city", "pos_near", "neg_near")


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


def kto_loss(logratio, z0, desirable, beta, desirable_weight=1.0, undesirable_weight=1.0):
    """Loss KTO di un esempio: peso * (1 - sigmoide(beta * (logratio - z0))) se desiderato, con (z0 - logratio) se
    indesiderato. Numero per un numero, tensore per un tensore."""
    x = beta * (logratio - z0) if desirable else beta * (z0 - logratio)
    weight = desirable_weight if desirable else undesirable_weight
    if isinstance(x, (int, float)):  # 1 - sigmoide(x) = sigmoide(-x), senza overflow per |x| grandi
        return weight * (math.exp(-x) / (1 + math.exp(-x)) if x >= 0 else 1 / (1 + math.exp(x)))
    import torch
    return weight * (1 - torch.sigmoid(x))


def build_kto_examples(results, max_bad=2, refusal_anchors=0, seed=42):
    """[(messaggi del prompt, risposta, desiderata)] dai risultati di collect_onpolicy.py: per ogni prompt con almeno
    una risposta "bad", al piu' max_bad risposte sbagliate diverse, la risposta del dataset e una giusta del modello se
    diversa da quella del dataset. Poi, come desiderate, le risposte del dataset di al piu' refusal_anchors prompt da
    rifiutare (kind "neg") senza risposte sbagliate, scelti a caso con seed: senza di loro gli esempi vengono solo dagli
    errori, per lo piu' prompt a cui rispondere, e il modello impara a rispondere anche dove prima rifiutava."""
    examples, anchors = [], []
    for r in results:
        prompt = [{"role": "user", "content": r["prompt"]}]
        bad = list(dict.fromkeys(s["got"] for s in r["samples"] if s["label"] == "bad" and s["got"]))[:max_bad]
        if not bad:
            if r.get("kind") == "neg":
                anchors.append((prompt, r["want"], True))
            continue
        good = next((s["got"] for s in r["samples"] if s["label"] == "good" and s["got"] != r["want"]), None)
        examples += [(prompt, x, False) for x in bad]
        examples += [(prompt, x, True) for x in (r["want"], good) if x]
    return examples + random.Random(seed).sample(anchors, min(refusal_anchors, len(anchors)))


def kto_weights(examples):
    """(peso desiderate, peso indesiderate) che portano le due classi a 1:1, come consiglia TRL."""
    desirable = sum(e[2] for e in examples)
    undesirable = len(examples) - desirable
    return (undesirable / desirable if desirable else 1.0), 1.0


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
            rejected = first_sentence(context_of(r))
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
    ap.add_argument("--dataset", default="pocket_travel_sft.v11.it.jsonl", help="file dentro data/sft/")
    ap.add_argument("--out", required=True)
    ap.add_argument("--beta", type=float, default=0.1)
    ap.add_argument("--lr", type=float, default=2e-5)
    ap.add_argument("--epochs", type=int, default=1)
    ap.add_argument("--accum", type=int, default=16, help="coppie per passo dell'ottimizzatore")
    ap.add_argument("--max-len", type=int, default=1024)
    ap.add_argument("--per-group", default="neg_city=800,pos_city=800,pos_near=400,neg_near=200",
                    help="coppie al piu' per gruppo")
    ap.add_argument("--merge", action="store_true", help="salva anche i pesi con la LoRA fusa (per convert_gguf.py)")
    ap.add_argument("--loss", choices=("dpo", "kto"), default="dpo")
    ap.add_argument("--onpolicy", type=Path, help="con --loss kto: file di collect_onpolicy.py")
    ap.add_argument("--undesirable-per-prompt", type=int, default=2, help="con --loss kto: risposte sbagliate per prompt")
    ap.add_argument("--refusal-anchors", type=int, default=0,
                    help="con --loss kto: rifiuti gia' giusti del modello aggiunti come desiderate")
    a = ap.parse_args()
    if (a.loss == "kto") != bool(a.onpolicy):
        ap.error("--onpolicy serve con --loss kto, e solo con quella")

    import gpu_lock
    from status import Progress, phase
    gpu_lock.gpu_job("dpo_lora.py")
    gpu_lock.pick_discrete_gpu("dpo_lora")  # dopo il lucchetto e prima di torch e Unsloth
    try:
        import unsloth  # noqa: F401  patch di compatibilita' Windows/ROCm, come in train_lora.py
    except ImportError:
        pass
    import torch
    import torch.nn.functional as F
    from peft import LoraConfig, PeftModel, get_peft_model
    from transformers import AutoConfig, AutoModelForCausalLM, AutoModelForImageTextToText, AutoTokenizer

    if a.loss == "kto":
        data = build_kto_examples(read_jsonl_lenient(a.onpolicy), a.undesirable_per_prompt, a.refusal_anchors)
        desirable_weight, undesirable_weight = kto_weights(data)
        counts = {"desiderate": sum(e[2] for e in data), "indesiderate": sum(not e[2] for e in data)}
        print("esempi KTO:", counts, f"peso desiderate {desirable_weight:.3f}")
    else:
        per_group = {k: int(v) for k, v in (item.split("=") for item in a.per_group.split(","))}
        rows = [json.loads(line) for line in (SFT_DIR / a.dataset).open(encoding="utf-8")]
        excluded = {json.loads(line)["region"] for line in (SFT_DIR / extended_file(a.dataset)).open(encoding="utf-8")}
        pairs = build_pairs(rows, excluded, per_group)
        counts = {g: len(p) for g, p in pairs.items()}
        print("coppie:", counts)
        data = [p for items in pairs.values() for p in items]
    random.Random(42).shuffle(data)

    # bf16 nativo solo con capability >= 8, come train_lora.py: su Turing is_bf16_supported() e' vero ma emulato (lento)
    bf16 = torch.cuda.is_bf16_supported() and torch.cuda.get_device_capability()[0] >= 8
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
    name = a.loss.upper()
    phase(name, f"{len(data)} {'esempi' if a.loss == 'kto' else 'coppie'}, {total} passi, beta {a.beta}, lr {a.lr}")
    progress = Progress(name, total, "passo", every=60)
    model.train()
    step, losses, wins = 0, [], []
    wins_label = "dal lato giusto di z0" if a.loss == "kto" else "preferita sopra"

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
                              f"{wins_label} {100 * sum(wins[recent]) / len(wins[recent]):.0f}%".replace(".", ","))

    def logratio(ids, start):
        """Log-rapporto della risposta tra modello e riferimento (LoRA spenta); il gradiente passa solo dal modello."""
        policy = answer_logps([ids], [start])[0]
        with torch.no_grad(), model.disable_adapter():
            reference = answer_logps([ids], [start])[0]
        return policy - reference

    def kto_epoch():
        encoded = [(encode(prompt, completion), prompt, completion, desirable) for prompt, completion, desirable in data]
        encoded = [e for e in encoded if len(e[0][0]) <= a.max_len]
        for i in range(0, len(encoded), a.accum):
            chunk = encoded[i:i + a.accum]
            # z0: ogni prompt del gruppo con la risposta dell'esempio dopo, senza gradiente, come il KL di TRL
            z0 = 0.0
            if len(chunk) > 1:
                with torch.no_grad():
                    kl = [logratio(*ids) for ids in (encode(chunk[j][1], chunk[(j + 1) % len(chunk)][2])
                                                    for j in range(len(chunk))) if len(ids[0]) <= a.max_len]
                z0 = max(torch.stack(kl).mean().item(), 0.0) if kl else 0.0
            for (ids, start), _, _, desirable in chunk:
                r = logratio(ids, start)
                loss = kto_loss(r, z0, desirable, a.beta, desirable_weight, undesirable_weight)
                (loss / a.accum).backward()
                losses.append(loss.item())
                wins.append(float((r.item() > z0) == desirable))
            optimizer_step(len(chunk))

    for _ in range(a.epochs):
        if a.loss == "kto":
            kto_epoch()
            continue
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
    record = {"base": a.base, "dataset": a.dataset, "loss": a.loss, "beta": a.beta, "lr": a.lr, "epochs": a.epochs,
              ("examples" if a.loss == "kto" else "pairs"): counts}
    if a.loss == "kto":
        record.update(onpolicy=str(a.onpolicy), undesirable_per_prompt=a.undesirable_per_prompt,
                      refusal_anchors=a.refusal_anchors)
    (Path(a.out) / "run.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
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
