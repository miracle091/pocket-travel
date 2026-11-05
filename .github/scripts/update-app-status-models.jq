# Filtro del job update-app-status-models di publish-apk.yml: su app-status.json pubblicato cambia solo publishedAt e
# le impronte (sha256, sizeBytes) dei modelli in $updates[0]; appVersion, impronta dell'APK compresa, resta com'e'.
# Uso: jq --slurpfile updates <updates.json> --argjson publishedAt <secondi> -f update-app-status-models.jq <app-status.json>
.publishedAt = $publishedAt
| .aiModels |= map(. as $m
    | (first($updates[0][] | select(.modelId == $m.modelId)) // null) as $u
    | if $u == null then . else .sha256 = $u.sha256 | .sizeBytes = $u.sizeBytes end)
