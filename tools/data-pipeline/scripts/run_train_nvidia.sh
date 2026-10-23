#!/usr/bin/env bash
# Training su GPU NVIDIA (Linux/WSL) tramite train_auto.py (sceglie GPU e backend, Unsloth se installato).
# Testato con GTX 1660 SUPER (6 GB, Turing): su Windows nativo Unsloth fp32, Unsloth --4bit e peft;
# su WSL2 (Ubuntu, Python 3.12, torch cu130, constraints-train.txt) Unsloth fp32 sul Qwen3.5-0.8B: 2 passi piu'
# controllo a campione, ~86 s/passo, 1,9 GiB di VRAM, 4,8 GB di RAM (CUDA_DEVICE solo con --dry-run).
# Su WSL il desktop di Windows occupa parte della VRAM: se supera il 10% di default, serve --max-vram-held 25.
# Il lucchetto della GPU (gpu_lock.py) e' quello di Windows anche da WSL: i lavori dei due lati si aspettano.
# Uso: [UNSLOTH_VENV=<venv>] [CUDA_DEVICE=0] ./run_train_nvidia.sh [opzioni di train_lora.py]
#      es. ./run_train_nvidia.sh --max-steps 2   (prova veloce)
#          ./run_train_nvidia.sh --model Qwen/Qwen3.5-0.8B --dataset pocket_travel_sft.v10.it.jsonl \
#              --epochs 1 --batch 2 --merge --out <cartella>   (ricetta dei modelli pubblicati)
#          ./run_train_nvidia.sh --4bit --model <modello piu' grande>   (QLoRA: meno VRAM)
# Setup una tantum nel venv, dalla radice del repository (PyTorch CUDA incluso):
#   pip install -c tools/data-pipeline/constraints-train.txt unsloth unsloth-zoo trl peft datasets bitsandbytes wandb
#   (venv di uv, senza pip: uv pip install --python <venv>/bin/python -c ... con gli stessi pacchetti)
# Su Windows nativo lancia direttamente train_auto.py (carica da solo l'ambiente MSVC).
set -euo pipefail

command -v nvidia-smi >/dev/null || { echo "nvidia-smi non trovato: driver NVIDIA assenti?" >&2; exit 1; }
# python3: Ubuntu non ha il comando python fuori da un venv
PY="${UNSLOTH_VENV:+$UNSLOTH_VENV/bin/}python3"

# HF_TOKEN, se serve (modelli gated), va gia' impostato nell'ambiente.
exec "$PY" "$(dirname "$0")/train_auto.py" ${CUDA_DEVICE:+--gpu "$CUDA_DEVICE"} "$@"
