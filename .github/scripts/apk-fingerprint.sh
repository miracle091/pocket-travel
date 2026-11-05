#!/usr/bin/env bash
# Scrive sha256 e dimensione di un APK come {"apkSha256", "apkSizeBytes"}, per compose-app-status.sh. Gira nel job di
# publish-apk.yml che compila l'APK, sul file compilato: l'impronta firmata in app-status.json descrive quel file, non
# l'asset della release, che chi ha accesso in scrittura al repository potrebbe sostituire.
# Uso: apk-fingerprint.sh <app-release.apk> <apk-fingerprint.json>
set -euo pipefail
APK="$1"
OUT="$2"

[ -s "$APK" ] || { echo "::error::APK assente o vuoto: $APK" >&2; exit 1; }
jq -n \
  --arg apkSha256 "$(sha256sum "$APK" | cut -d' ' -f1)" \
  --argjson apkSizeBytes "$(wc -c < "$APK" | tr -d ' ')" \
  '{apkSha256: $apkSha256, apkSizeBytes: $apkSizeBytes}' > "$OUT"
