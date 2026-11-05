#!/usr/bin/env bash
# Compone app-status.json (non firmato) per i job publish-app-status e resign-app-status di publish-apk.yml: versione
# dell'app, impronta e dimensione del suo APK (apkSha256, apkSizeBytes: con questi l'app verifica l'APK che scarica
# dalla release) e impronte dei modelli IA. L'impronta dell'APK arriva da apk-fingerprint.sh nel job che lo compila, o
# dal file online con firma valida; un file {} la omette, e l'app offre solo il link a GitHub. publishedAt (secondi
# Unix) si scrive prima della firma: l'app rifiuta un valore piu' basso dell'ultimo accettato, quindi una vecchia
# coppia file + .sig non puo' nascondere un aggiornamento.
# Uso: compose-app-status.sh <versionName> <versionCode> <ai-models.json> <apk-fingerprint.json> <app-status.json>
set -euo pipefail
VERSION="$1"
VERSION_CODE="$2"
AI_MODELS_FILE="$3"
APK_FINGERPRINT_FILE="$4"
OUT="$5"

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo "::error::versionCode non valido: $VERSION_CODE" >&2; exit 1; }
# -s: il file deve avere un solo valore JSON, quello che il jq sotto legge come $apk[0].
jq -e -s 'length == 1 and (.[0] | . == {} or (keys == ["apkSha256", "apkSizeBytes"]
    and (.apkSha256 | type == "string" and test("^[0-9a-f]{64}$"))
    and (.apkSizeBytes | type == "number" and . > 0 and floor == .)))' "$APK_FINGERPRINT_FILE" > /dev/null \
  || { echo "::error::impronta dell'APK non valida: $APK_FINGERPRINT_FILE" >&2; exit 1; }

jq -n \
  --arg versionName "$VERSION" \
  --argjson versionCode "$VERSION_CODE" \
  --slurpfile apk "$APK_FINGERPRINT_FILE" \
  --slurpfile aiModels "$AI_MODELS_FILE" \
  --argjson publishedAt "$(date +%s)" \
  '{publishedAt: $publishedAt,
    appVersion: (($apk[0] | if . == {} then {} else {apkSha256, apkSizeBytes} end)
      + {versionName: $versionName, versionCode: $versionCode}),
    aiModels: $aiModels[0]}' \
  > "$OUT"
