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
# serve) — solo per verifica manuale, l'app non chiama mai questo URL. Elenca TUTTO il lotto
# pilota (non solo le regioni di questa run/gia' pubblicate), cosi' si vede a colpo d'occhio
# anche quali nazioni sono attualmente non disponibili perche' la loro generazione e' fallita
# (es. Stati Uniti su un bbox troppo grande per Overpass) - fonte unica pilot-regions.sh, cosi'
# la pagina resta sincronizzata con l'elenco reale senza doverlo duplicare qui.
# shellcheck source=./pilot-regions.sh
source "$SCRIPT_DIR/pilot-regions.sh"

# Bandiere come SVG vettoriali (scripts/assets/flags/, vendorizzate da flag-icons - vedi
# assets/flags/README.md e LICENSE), non emoji: gli emoji bandiera non si vedono su Windows (il
# font di sistema non li renderizza, mostra solo il codice testuale) - un <img> verso un vero
# file SVG e' vettoriale ed e' identico su ogni piattaforma. Copertura completa ISO 3166-1
# alpha-2 gia' pronta all'uso per qualunque nazione futura, non solo il lotto pilota attuale.
mkdir -p "$SITE_DIR/assets/flags"
cp "$SCRIPT_DIR"/assets/flags/*.svg "$SITE_DIR/assets/flags/"

# Icone di stato disponibile/non disponibile, anche queste SVG inline invece di ✅/❌ (stesso
# motivo delle bandiere: niente emoji).
STATUS_OK_SVG='<svg class="status" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M3 8.5l3.2 3.2L13 4.5" fill="none" stroke="#1a7f37" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>'
STATUS_FAIL_SVG='<svg class="status" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="#b42318" stroke-width="2" stroke-linecap="round"/></svg>'

FINAL_MANIFEST="$SITE_DIR/manifest.json"
PRESENT_REGION_IDS="$(grep -o '"regionId"[[:space:]]*:[[:space:]]*"[^"]*"' "$FINAL_MANIFEST" | sed 's/.*:[[:space:]]*"//;s/"$//' | sort -u)"

is_present() {
  echo "$PRESENT_REGION_IDS" | grep -qxF "$1"
}

flag_img() {
  echo "<img class=\"flag\" src=\"assets/flags/$1.svg\" width=\"28\" height=\"21\" alt=\"\">"
}

# Solo l'icona (spunta verde/croce rossa) come segnale di stato per una nazione singola, senza
# testo ne' separatore: il nome basta, l'icona da sola dice se e' disponibile o no. Per una
# sotto-regione dentro un gruppo (terzo argomento "true", es. Alaska dentro "Stati Uniti
# d'America") si tiene invece il trattino "—", perche' li' l'elenco e' piu' denso (piu' voci
# una sotto l'altra nello stesso gruppo) e il separatore aiuta a leggerle.
status_html() {
  local regionId="$1" label="$2" withDash="${3:-false}"
  local text="$label"
  [ "$withDash" = "true" ] && text="$label —"
  if is_present "$regionId"; then
    echo "<a href=\"regions/$regionId/\">$text $STATUS_OK_SVG</a>"
  else
    echo "$text $STATUS_FAIL_SVG"
  fi
}

# L'elenco dei continenti e' esplicito e completo (non dedotto da PILOT_REGIONS): la pagina mostra
# fin da subito tutti i continenti, anche quelli senza ancora nessuna nazione pubblicata, cosi' si
# vede a colpo d'occhio la copertura mondiale prevista (piano A2: "tutte le nazioni"), non solo il
# lotto pilota attuale.
#
# "Territori disabitati" e' una settima voce a parte (non un continente geografico vero) per i
# territori senza popolazione permanente (basi scientifiche, riserve naturali, isolotti disabitati)
# — tenuti fuori dai raggruppamenti per continente cosi' non si mescolano con le nazioni con
# popolazione e turismo reale.
CONTINENTS=("Europa" "Asia" "Africa" "Nord America" "Sud America" "Oceania" "Territori disabitati")

CONTINENT_SECTIONS=""
for continent in "${CONTINENTS[@]}"; do
  REGION_ROWS=""
  currentGroup=""
  groupFlagImg=""
  groupSubRows=""

  # Due livelli di raggruppamento dentro questo continente, un solo passaggio su PILOT_REGIONS
  # filtrato: apre/chiude un gruppo quando groupName cambia rispetto alla riga precedente (le
  # region non contigue della stessa nazione, oggi solo le 3 USA, vanno tenute consecutive in
  # PILOT_REGIONS perche' questo funzioni).
  flush_group() {
    if [ -n "$currentGroup" ]; then
      row="<li>$groupFlagImg $currentGroup<ul class=\"subgroup\">$groupSubRows</ul></li>"
      REGION_ROWS="$(printf '%s\n%s' "$REGION_ROWS" "$row")"
    fi
    currentGroup=""
    groupFlagImg=""
    groupSubRows=""
  }

  for spec in "${PILOT_REGIONS[@]}"; do
    IFS='|' read -r regionId displayName _ _ _ _ _ flag groupName groupLabel regionContinent <<< "$spec"
    [ "$regionContinent" = "$continent" ] || continue
    if [ -n "$groupName" ]; then
      if [ "$groupName" != "$currentGroup" ]; then
        flush_group
        currentGroup="$groupName"
        groupFlagImg="$(flag_img "$flag")"
      fi
      groupSubRows="$groupSubRows<li>$(status_html "$regionId" "$groupLabel" true)</li>"
    else
      flush_group
      row="<li>$(flag_img "$flag") $(status_html "$regionId" "$displayName")</li>"
      REGION_ROWS="$(printf '%s\n%s' "$REGION_ROWS" "$row")"
    fi
  done
  flush_group

  if [ -z "$REGION_ROWS" ]; then
    body="<p class=\"empty\">Nessuna nazione pubblicata ancora in questo continente.</p>"
  else
    body="<ul>$REGION_ROWS</ul>"
  fi
  section="<h2>$continent</h2>$body"
  CONTINENT_SECTIONS="$(printf '%s\n%s' "$CONTINENT_SECTIONS" "$section")"
done

cat > "$SITE_DIR/index.html" <<HTML
<!doctype html>
<meta charset="utf-8">
<title>Pocket Travel — dati regioni</title>
<style>
  ul { padding-left: 0; }
  li { list-style: none; margin: 4px 0; }
  .flag { vertical-align: middle; border: 1px solid rgba(0,0,0,.15); border-radius: 2px; margin-right: 4px; }
  .status { vertical-align: middle; margin-right: 2px; }
  .subgroup { padding-left: 32px; margin: 4px 0; }
  h2 { font-size: 1.05em; margin: 20px 0 6px; border-bottom: 1px solid rgba(0,0,0,.15); padding-bottom: 2px; }
  .empty { color: #767676; font-style: italic; margin: 4px 0; }
</style>
<p>Questo host serve solo dati statici per l'app <a href="https://github.com/miracle091/pocket-travel">Pocket Travel</a>.</p>
<p>Ultimo aggiornamento: $(date -u +%Y.%m.%d)</p>
<p><a href="manifest.json">manifest.json</a></p>
$CONTINENT_SECTIONS
HTML
