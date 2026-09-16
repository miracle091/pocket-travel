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
  [ "$withDash" = "true" ] && text="— $label"
  if is_present "$regionId"; then
    echo "<a class=\"entry\" href=\"regions/$regionId/\">$text $STATUS_OK_SVG</a>"
  else
    echo "<span class=\"entry\">$text $STATUS_FAIL_SVG</span>"
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
      row="<li><span class=\"row\">$groupFlagImg $currentGroup</span><ul class=\"subgroup\">$groupSubRows</ul></li>"
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
      row="<li><span class=\"row\">$(flag_img "$flag") $(status_html "$regionId" "$displayName")</span></li>"
      REGION_ROWS="$(printf '%s\n%s' "$REGION_ROWS" "$row")"
    fi
  done
  flush_group

  if [ -z "$REGION_ROWS" ]; then
    body="<p class=\"empty\">Nessuna nazione pubblicata ancora in questo continente.</p>"
  else
    body="<ul>$REGION_ROWS</ul>"
  fi
  section="<section class=\"continent\"><h2>$continent</h2>$body</section>"
  CONTINENT_SECTIONS="$(printf '%s\n%s' "$CONTINENT_SECTIONS" "$section")"
done

cat > "$SITE_DIR/index.html" <<HTML
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pocket Travel — dati regioni</title>
<style>
  :root {
    color-scheme: light dark;
    --bg: #fff;
    --fg: #1a1a1a;
    --muted: #666;
    --border: rgba(0,0,0,.15);
    --link: #0a5fc4;
    --card-bg: #f7f7f8;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #14161a;
      --fg: #e7e7e7;
      --muted: #9a9a9a;
      --border: rgba(255,255,255,.15);
      --link: #6cb2ff;
      --card-bg: #1d2025;
    }
  }
  * { box-sizing: border-box; }
  body {
    background: var(--bg);
    color: var(--fg);
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    max-width: 960px;
    margin: 0 auto;
    padding: 16px 20px 48px;
    line-height: 1.4;
  }
  a { color: var(--link); }
  input[type="search"] {
    display: block;
    width: 100%;
    max-width: 360px;
    padding: 8px 10px;
    font-size: 1em;
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--card-bg);
    color: var(--fg);
    margin: 12px 0;
  }
  .continents { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 8px 32px; }
  .continent { min-width: 0; }
  ul { padding-left: 0; }
  li { list-style: none; margin: 4px 0; }
  .row { display: flex; align-items: center; gap: 4px; }
  .entry { display: inline-flex; align-items: center; gap: 4px; }
  .flag { border: 1px solid var(--border); border-radius: 2px; flex-shrink: 0; }
  .status { flex-shrink: 0; }
  .subgroup { padding-left: 32px; margin: 4px 0; }
  h2 { font-size: 1.05em; margin: 20px 0 6px; border-bottom: 1px solid var(--border); padding-bottom: 2px; }
  .empty { color: var(--muted); font-style: italic; margin: 4px 0; }
  #no-results { color: var(--muted); font-style: italic; }
</style>
<p>Questo host serve solo dati statici per l'app <a href="https://github.com/miracle091/pocket-travel">Pocket Travel</a>.</p>
<p>Ultimo aggiornamento: $(date -u +%Y.%m.%d)</p>
<p><a href="manifest.json">manifest.json</a></p>
<input type="search" id="search" placeholder="Cerca una nazione…" aria-label="Cerca una nazione">
<p id="no-results" hidden>Nessun risultato.</p>
<div class="continents">
$CONTINENT_SECTIONS
</div>
<script>
(function () {
  var input = document.getElementById('search');
  var noResults = document.getElementById('no-results');
  if (!input) return;
  var sections = document.querySelectorAll('.continent');

  function groupLabelText(li) {
    var clone = li.cloneNode(true);
    var sub = clone.querySelector('.subgroup');
    if (sub) sub.remove();
    return clone.textContent.toLowerCase();
  }

  input.addEventListener('input', function () {
    var q = input.value.trim().toLowerCase();
    var anyVisibleTotal = false;
    sections.forEach(function (section) {
      var topItems = section.querySelectorAll(':scope > ul > li');
      if (topItems.length === 0) {
        var placeholderVisible = !q;
        section.style.display = placeholderVisible ? '' : 'none';
        if (placeholderVisible) anyVisibleTotal = true;
        return;
      }
      var anyVisible = false;
      topItems.forEach(function (li) {
        var subUl = li.querySelector('.subgroup');
        if (!subUl) {
          var match = !q || li.textContent.toLowerCase().indexOf(q) !== -1;
          li.style.display = match ? '' : 'none';
          if (match) anyVisible = true;
          return;
        }
        var groupMatch = !q || groupLabelText(li).indexOf(q) !== -1;
        var subItems = subUl.querySelectorAll('li');
        var anySubVisible = false;
        subItems.forEach(function (sub) {
          var visible = groupMatch || sub.textContent.toLowerCase().indexOf(q) !== -1;
          sub.style.display = visible ? '' : 'none';
          if (visible) anySubVisible = true;
        });
        li.style.display = anySubVisible ? '' : 'none';
        if (anySubVisible) anyVisible = true;
      });
      section.style.display = anyVisible ? '' : 'none';
      if (anyVisible) anyVisibleTotal = true;
    });
    noResults.hidden = anyVisibleTotal || !q;
  });
})();
</script>
HTML
