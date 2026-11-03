#!/usr/bin/env bash
# Toglie le cache di Actions con il prefisso <prefisso> create prima di <chiave nuova>, appena salvata: una cache nuova
# per run riempirebbe il limite di 10 GB del repository e farebbe sfrattare modelli, dump e Gradle. La nuova contiene
# gia' il lavoro delle vecchie (frasi tradotte, popolazione delle citta').
# Subito dopo il salvataggio l'elenco puo' non mostrare ancora la chiave nuova: si rilegge fino a 6 volte a 10 secondi
# l'una, poi un avviso ed esce 0 (nulla tolto, ci pensa la run dopo). Una cache che non si riesce a togliere (per
# esempio gia' tolta da un altro shard) e' un avviso e non ferma le altre.
# Uso: trim-caches.sh <prefisso> <chiave nuova>. Ambiente: GITHUB_REPOSITORY, GH_TOKEN (con actions: write).
set -euo pipefail
source .github/scripts/env.sh
PREFIX="$1"
NEW_KEY="$2"
LISTED=""
for attempt in 1 2 3 4 5 6; do
  LISTED="$(gh cache list --repo "$GITHUB_REPOSITORY" --key "$PREFIX" --sort created_at --order desc --limit 100 --json key --jq '.[].key')" || LISTED=""
  if grep -Fxq -- "$NEW_KEY" <<< "$LISTED"; then break; fi
  if [ "$attempt" -lt 6 ]; then sleep 10; fi
done
if ! grep -Fxq -- "$NEW_KEY" <<< "$LISTED"; then
  echo "::warning::cache $NEW_KEY non ancora in elenco dopo 6 letture: nessuna cache vecchia tolta"
  exit 0
fi
awk -v new="$NEW_KEY" 'found { print } $0 == new { found = 1 }' <<< "$LISTED" \
  | while read -r old; do gh cache delete "$old" --repo "$GITHUB_REPOSITORY" || echo "::warning::cache $old non tolta"; done
