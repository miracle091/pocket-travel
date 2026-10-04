#!/usr/bin/env bash
# Verifica <file> con <file>.sig e la chiave pubblica incorporata nell'app (SyncConfig.MANIFEST_PUBLIC_KEY in
# core/sync), come fa l'app: prima di riusare un file gia' pubblicato, per non firmare un asset manomesso.
# Esce con errore se la firma manca o non e' valida. Da lanciare dalla radice del repository.
# Uso: bash .github/scripts/verify-app-status.sh <file>
set -euo pipefail

file="$1"
config="core/sync/src/main/kotlin/com/pockettravel/core/sync/SyncConfig.kt"
key="$(grep -A1 'MANIFEST_PUBLIC_KEY =' "$config" | grep -oE '"[A-Za-z0-9+/=]{40,}"' | tr -d '"')"
if [ -z "$key" ]; then
  echo "::error::MANIFEST_PUBLIC_KEY non trovata in $config" >&2
  exit 1
fi
pub="$(mktemp)"
trap 'rm -f "$pub"' EXIT
{ echo "-----BEGIN PUBLIC KEY-----"; printf '%s\n' "$key" | fold -w 64; echo "-----END PUBLIC KEY-----"; } > "$pub"
openssl dgst -sha256 -verify "$pub" -signature "$file.sig" "$file"
