#!/usr/bin/env bash
# Compone app-status.json (non firmato) per i job publish-app-status e resign-app-status di publish-apk.yml: versione
# dell'app, impronta e dimensione del suo APK (apkSha256, apkSizeBytes: con questi l'app verifica l'APK che scarica
# dalla release) e impronte dei modelli IA. L'APK e' quello scaricato dalla release del tag, cioe' il file che le app
# scaricheranno davvero. publishedAt (secondi Unix) si scrive prima della firma: l'app rifiuta un valore piu' basso
# dell'ultimo accettato, quindi una vecchia coppia file + .sig non puo' nascondere un aggiornamento.
# Uso: compose-app-status.sh <versionName> <versionCode> <ai-models.json> <app-release.apk> <app-status.json>
set -euo pipefail
VERSION="$1"
VERSION_CODE="$2"
AI_MODELS_FILE="$3"
APK="$4"
OUT="$5"

[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo "::error::versionCode non valido: $VERSION_CODE" >&2; exit 1; }
[ -s "$APK" ] || { echo "::error::APK assente o vuoto: $APK" >&2; exit 1; }
APK_SHA256="$(sha256sum "$APK" | cut -d' ' -f1)"
APK_SIZE="$(wc -c < "$APK" | tr -d ' ')"

jq -n \
  --arg versionName "$VERSION" \
  --argjson versionCode "$VERSION_CODE" \
  --arg apkSha256 "$APK_SHA256" \
  --argjson apkSizeBytes "$APK_SIZE" \
  --slurpfile aiModels "$AI_MODELS_FILE" \
  --argjson publishedAt "$(date +%s)" \
  '{publishedAt: $publishedAt,
    appVersion: {versionName: $versionName, versionCode: $versionCode, apkSha256: $apkSha256, apkSizeBytes: $apkSizeBytes},
    aiModels: $aiModels[0]}' \
  > "$OUT"
