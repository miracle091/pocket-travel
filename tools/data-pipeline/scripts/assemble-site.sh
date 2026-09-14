#!/usr/bin/env bash
# Assembla il sito da pubblicare (site/) unendo i frammenti manifest appena generati con il
# manifest.json gia' pubblicato online (se esiste), e porta avanti nel nuovo sito i content.db
# delle regioni NON toccate in questa run. actions/deploy-pages sostituisce l'intero sito ad
# ogni pubblicazione (non e' un rsync incrementale): senza questo passo, una pubblicazione
# parziale (piano A3: "il workflow accetta in input l'elenco delle nazioni da rigenerare")
# farebbe sparire il download delle regioni non rigenerate, anche se il manifest unito le
# elenca ancora.
#
# Uso: assemble-site.sh <siteDir> <publishedManifestUrl> <fragmentFile1> [fragmentFile2 ...]
set -euo pipefail

SITE_DIR="$1"; shift
PUBLISHED_MANIFEST_URL="$1"; shift
FRAGMENT_FILES=("$@")
[ "${#FRAGMENT_FILES[@]}" -gt 0 ] || { echo "Uso: $0 <siteDir> <publishedManifestUrl> <fragment1.json> [...]" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

mkdir -p "$SITE_DIR"
PREV_MANIFEST="$(mktemp)"
MANIFEST_INPUTS=()

if curl -sSf -o "$PREV_MANIFEST" "$PUBLISHED_MANIFEST_URL" 2>/dev/null; then
  echo "-- manifest gia' pubblicato trovato, unisco (le regioni di questa run vincono)"
  MANIFEST_INPUTS+=("$PREV_MANIFEST")

  RUN_REGION_IDS=()
  for f in "${FRAGMENT_FILES[@]}"; do
    rid="$(grep -o '"regionId"[[:space:]]*:[[:space:]]*"[^"]*"' "$f" | head -1 | sed 's/.*:[[:space:]]*"//;s/"$//')"
    RUN_REGION_IDS+=("$rid")
  done

  is_untouched() {
    local candidate="$1"
    for rid in "${RUN_REGION_IDS[@]}"; do
      [ "$rid" = "$candidate" ] && return 1
    done
    return 0
  }

  grep -o '"url"[[:space:]]*:[[:space:]]*"[^"]*"' "$PREV_MANIFEST" | sed 's/.*:[[:space:]]*"//;s/"$//' | sort -u | while read -r url; do
    case "$url" in
      */regions/*/*/*)
        regionId="$(echo "$url" | sed -E 's#.*/regions/([^/]+)/.*#\1#')"
        if is_untouched "$regionId"; then
          relPath="$(echo "$url" | sed -E 's#.*/(regions/.*)#\1#')"
          dest="$SITE_DIR/$relPath"
          mkdir -p "$(dirname "$dest")"
          echo "-- porto avanti $relPath (regione non toccata in questa run)"
          curl -sSf -o "$dest" "$url"
        fi
        ;;
    esac
  done
else
  echo "-- nessun manifest pubblicato trovato (prima pubblicazione, o non ancora online): nessun merge"
fi

MANIFEST_INPUTS+=("${FRAGMENT_FILES[@]}")

ARGS_STR="\"$(winpath "$SITE_DIR/manifest.json")\""
for f in "${MANIFEST_INPUTS[@]}"; do
  ARGS_STR="$ARGS_STR \"$(winpath "$f")\""
done

cd "$REPO_ROOT"
./gradlew -q :tools:data-pipeline:content:mergeManifests --args="$ARGS_STR"
rm -f "$PREV_MANIFEST"
