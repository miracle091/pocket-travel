#!/usr/bin/env bash
# Toglie le cache di Actions con il prefisso <prefisso> create prima di <chiave nuova>, appena salvata: una cache nuova
# per run riempirebbe il limite di 10 GB del repository e farebbe sfrattare modelli, dump e Gradle. La nuova contiene
# gia' il lavoro delle vecchie (frasi tradotte, popolazione delle citta').
# Uso: trim-caches.sh <prefisso> <chiave nuova>. Ambiente: GITHUB_REPOSITORY, GH_TOKEN (con actions: write).
set -euo pipefail
source .github/scripts/env.sh
PREFIX="$1"
NEW_KEY="$2"
gh cache list --repo "$GITHUB_REPOSITORY" --key "$PREFIX" --sort created_at --order desc --limit 100 --json key --jq '.[].key' \
  | awk -v new="$NEW_KEY" 'found { print } $0 == new { found = 1 }' \
  | while read -r key; do gh cache delete "$key" --repo "$GITHUB_REPOSITORY"; done
