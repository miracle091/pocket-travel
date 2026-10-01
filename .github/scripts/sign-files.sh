#!/usr/bin/env bash
# Firma i file indicati con ECDSA P-256 / SHA-256 (java.security la supporta da Android API 26, a
# differenza di Ed25519): accanto a ogni <file> scrive <file>.sig, firma DER di openssl. L'app
# scarica <file> e <file>.sig e verifica con la chiave pubblica incorporata.
# La chiave privata (PEM) arriva dalla variabile d'ambiente MANIFEST_SIGNING_KEY (secret del
# repository, da passare solo al passo che firma): vive su disco, con permessi 0600, solo per la
# durata dello script. Senza chiave lo script fallisce: pubblicare file non firmati romperebbe
# tutti gli utenti non appena l'app richiede la firma. Dopo ogni firma la verifica con la chiave
# pubblica derivata, come controllo di coerenza. Come generare la chiave: tools/data-pipeline/README.md.
# Uso: bash .github/scripts/sign-files.sh <file> [<file> ...]
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "Uso: $0 <file> [<file> ...]" >&2
  exit 1
fi
if [ -z "${MANIFEST_SIGNING_KEY:-}" ]; then
  echo "::error::Il secret MANIFEST_SIGNING_KEY manca: non pubblico file senza firma. Generalo e impostalo come descritto in tools/data-pipeline/README.md" >&2
  exit 1
fi

dir="${RUNNER_TEMP:-$(mktemp -d)}"
key="$dir/manifest-signing-key.pem"
pub="$dir/manifest-signing-key.pub.pem"
trap 'rm -f "$key" "$pub"' EXIT
umask 077
printf '%s\n' "$MANIFEST_SIGNING_KEY" > "$key"
chmod 600 "$key"

# Solo P-256: con un'altra curva l'app non verificherebbe nessuna firma.
if ! openssl ec -in "$key" -noout -text 2> /dev/null | grep -q 'prime256v1'; then
  echo "::error::MANIFEST_SIGNING_KEY non e' una chiave EC prime256v1 (P-256) in formato PEM" >&2
  exit 1
fi
openssl ec -in "$key" -pubout -out "$pub" 2> /dev/null

for file in "$@"; do
  if [ ! -s "$file" ]; then
    echo "::error::$file non trovato o vuoto: non lo firmo" >&2
    exit 1
  fi
  openssl dgst -sha256 -sign "$key" -out "$file.sig" "$file"
  openssl dgst -sha256 -verify "$pub" -signature "$file.sig" "$file" > /dev/null
  echo "-- firmato $file ($(wc -c < "$file.sig" | tr -d ' ') byte di firma DER)"
done
