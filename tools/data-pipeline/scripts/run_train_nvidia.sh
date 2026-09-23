#!/usr/bin/env bash
# Training su GPU NVIDIA (Linux/WSL) con i kernel Unsloth. NON TESTATO: manca una GPU NVIDIA.
# Uso: [UNSLOTH_VENV=<venv>] [CUDA_DEVICE=0] ./run_train_nvidia.sh [opzioni di train_lora.py]
#      es. ./run_train_nvidia.sh --max-steps 30
#          ./run_train_nvidia.sh --4bit --model <modello piu' grande>   (QLoRA: meno VRAM)
# Setup una tantum nel venv: pip install unsloth  (PyTorch CUDA incluso)
# Su Windows nativo con NVIDIA usa invece train_auto.py (carica da solo l'ambiente MSVC).
set -euo pipefail

command -v nvidia-smi >/dev/null || { echo "nvidia-smi non trovato: driver NVIDIA assenti?" >&2; exit 1; }
PY="${UNSLOTH_VENV:+$UNSLOTH_VENV/bin/}python"

export CUDA_VISIBLE_DEVICES="${CUDA_DEVICE:-0}"
# HF_TOKEN, se serve (modelli gated), va gia' impostato nell'ambiente.
exec "$PY" "$(dirname "$0")/train_lora.py" --unsloth "$@"
