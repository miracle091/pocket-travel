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

# Duplicato intenzionale di build-region.sh/download_with_progress (stesso contenuto): script
# separati, invocati come step distinti del workflow, non sourced insieme - vedi il commento
# in build-region.sh per il motivo (curl -sS silenzioso lascia i log di GitHub Actions vuoti
# per tutta la durata di un content.db portato avanti da una pubblicazione precedente).
download_with_progress() {
  local out="$1" label="$2"
  shift 2
  curl "$@" &
  local pid=$!
  local last_kb=-1
  local elapsed=0
  while kill -0 "$pid" 2>/dev/null; do
    sleep 2
    elapsed=$((elapsed + 2))
    local size_kb=0
    [ -f "$out" ] && size_kb=$(( $(wc -c < "$out" | tr -d ' ') / 1024 ))
    if [ "$size_kb" -ne "$last_kb" ] || [ $((elapsed % 30)) -eq 0 ]; then
      echo "-- $label: ${size_kb} KB scaricati (${elapsed}s trascorsi)..."
      last_kb=$size_kb
    fi
  done
  wait "$pid"
}

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
          download_with_progress "$dest" "$relPath" -sSf -o "$dest" "$url"
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

# Pagina minimale per la radice del sito Pages: senza questa, GET / da 404 (nessun file la
# serve) — solo per verifica manuale, l'app non chiama mai questo URL.
FINAL_MANIFEST="$SITE_DIR/manifest.json"
REGION_ROWS="$(grep -o '"regionId"[[:space:]]*:[[:space:]]*"[^"]*"' "$FINAL_MANIFEST" | sed 's/.*:[[:space:]]*"//;s/"$//' | sort -u | while read -r rid; do
  echo "<li><a href=\"regions/$rid/\">$rid</a></li>"
done)"
cat > "$SITE_DIR/index.html" <<HTML
<!doctype html>
<meta charset="utf-8">
<title>Pocket Travel — dati regioni</title>
<p>Questo host serve solo dati statici per l'app Pocket Travel, non e' pensato per la navigazione.</p>
<p><a href="manifest.json">manifest.json</a></p>
<ul>
$REGION_ROWS
</ul>
HTML
