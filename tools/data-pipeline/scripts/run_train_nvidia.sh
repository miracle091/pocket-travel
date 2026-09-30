#!/usr/bin/env bash
# Training su GPU NVIDIA (Linux/WSL) tramite train_auto.py (sceglie GPU e backend, Unsloth se installato).
# Testato solo su Windows nativo con GTX 1660 SUPER (6 GB, Turing): Unsloth fp32, Unsloth --4bit e peft;
# su Linux/WSL non ancora provato.
# Uso: [UNSLOTH_VENV=<venv>] [CUDA_DEVICE=0] ./run_train_nvidia.sh [opzioni di train_lora.py]
#      es. ./run_train_nvidia.sh --max-steps 30
#          ./run_train_nvidia.sh --4bit --model <modello piu' grande>   (QLoRA: meno VRAM)
# Setup una tantum nel venv: pip install unsloth  (PyTorch CUDA incluso)
# Su Windows nativo lancia direttamente train_auto.py (carica da solo l'ambiente MSVC).
set -euo pipefail

command -v nvidia-smi >/dev/null || { echo "nvidia-smi non trovato: driver NVIDIA assenti?" >&2; exit 1; }
PY="${UNSLOTH_VENV:+$UNSLOTH_VENV/bin/}python"

# HF_TOKEN, se serve (modelli gated), va gia' impostato nell'ambiente.
exec "$PY" "$(dirname "$0")/train_auto.py" ${CUDA_DEVICE:+--gpu "$CUDA_DEVICE"} "$@"
