#!/usr/bin/env bash
# Firma app-status.json (app-status.json.sig accanto, sign-files.sh) e lo carica sulla release "app-status", solo se il
# suo versionCode non e' minore di quello pubblicato. Lo usano i job di publish-apk.yml che scrivono app-status.json, nel
# solo passo che ha MANIFEST_SIGNING_KEY: il file si compone in un passo prima, senza la chiave.
# Uso: sign-and-upload-app-status.sh <app-status.json>. Ambiente: GH_TOKEN, MANIFEST_SIGNING_KEY, GITHUB_REPOSITORY.
set -euo pipefail
source .github/scripts/retry.sh
APP_STATUS_FILE="$1"

bash .github/scripts/sign-files.sh "$APP_STATUS_FILE"

# Mai indietro, riletto subito prima di scrivere: due job in gruppi diversi possono finire in ordine inverso, e un
# controllo fatto all'inizio del job puo' essere superato. Letto senza verificare la firma: un file manomesso puo' solo
# bloccare la pubblicazione. Uguale e' ammesso: rilancio sullo stesso commit, impronte dei modelli aggiornate.
VERSION_CODE="$(jq -r '.appVersion.versionCode' "$APP_STATUS_FILE")"
PUBLISHED="$(retry gh release download app-status -p app-status.json -O - --repo "$GITHUB_REPOSITORY" | jq -r '.appVersion.versionCode // 0')"
if [ "$VERSION_CODE" -lt "$PUBLISHED" ]; then
  echo "::error::versionCode $VERSION_CODE e' minore di quello pubblicato ($PUBLISHED): non scrivo app-status.json" >&2
  exit 1
fi

retry gh release upload app-status "$APP_STATUS_FILE" "$APP_STATUS_FILE.sig" --repo "$GITHUB_REPOSITORY" --clobber
