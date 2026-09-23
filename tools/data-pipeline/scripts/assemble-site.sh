#!/usr/bin/env bash
# Assembla il sito da pubblicare (site/) unendo i frammenti manifest appena generati con il
# manifest.json gia' pubblicato online (se esiste). Serve solo a unire il JSON: guides.db, poi.db e
# i .rd5 non toccati in questa run non vanno ri-copiati da nessuna parte, perche' non
# vivono piu' sul sito Pages ma sugli asset della release "region-data" (vedi
# tools/data-pipeline/scripts/build-region.sh e .github/workflows/publish-regions.yml) - a
# differenza di actions/deploy-pages, che sostituisce l'intero sito ad ogni pubblicazione, gli
# asset di una release restano raggiungibili da soli finche' non vengono cancellati esplicitamente.
# (Prima della migrazione a Releases, questo script li ri-scaricava e ri-copiava nel nuovo sito ad
# ogni run: con la copertura mondiale di pilot-regions.sh, 254 regioni, il sito cumulativo aveva
# superato il limite di 1GB di GitHub Pages.)
#
# Uso: assemble-site.sh <siteDir> <publishedManifestUrl> <fragmentFile1> [fragmentFile2 ...]
# Richiede jq (per la sezione "Ultimi aggiornamenti" della pagina, derivata dalle versioni dei
# pacchetti del manifest unito) oltre alle dipendenze gia' richieste da mergeManifests (gradle wrapper).
set -euo pipefail

SITE_DIR="$1"; shift
PUBLISHED_MANIFEST_URL="$1"; shift
FRAGMENT_FILES=("$@")
[ "${#FRAGMENT_FILES[@]}" -gt 0 ] || { echo "Uso: $0 <siteDir> <publishedManifestUrl> <fragment1.json> [...]" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"

mkdir -p "$SITE_DIR"
PREV_MANIFEST="$(mktemp)"
MANIFEST_INPUTS=()

if curl -sSf -o "$PREV_MANIFEST" "$PUBLISHED_MANIFEST_URL" 2>/dev/null; then
  echo "-- manifest gia' pubblicato trovato, unisco (le regioni di questa run vincono)"
  MANIFEST_INPUTS+=("$PREV_MANIFEST")
else
  echo "-- nessun manifest pubblicato trovato (prima pubblicazione, o non ancora online): nessun merge"
fi

MANIFEST_INPUTS+=("${FRAGMENT_FILES[@]}")

# Continente e codice paese di ogni regione (campi continent e flagCode di PILOT_REGIONS), scritti
# dal merge nei campi "continent" e "countryCode" di tutte le regioni del manifest, anche quelle non
# ricostruite in questa run: l'app raggruppa l'elenco per continente e rende cliccabili i paesi
# sulla mappa del mondo, e non ha altra fonte per saperlo.
source "$SCRIPT_DIR/pilot-regions.sh"
CONTINENTS_TSV="$(mktemp)"
for spec in "${PILOT_REGIONS[@]}"; do
  IFS='|' read -r regionId _ _ _ _ _ _ flagCode _ _ continent <<< "$spec"
  printf '%s\t%s\t%s\n' "$regionId" "$continent" "$flagCode" >> "$CONTINENTS_TSV"
done

ARGS_STR="--continents \"$(winpath "$CONTINENTS_TSV")\" \"$(winpath "$SITE_DIR/manifest.json")\""
for f in "${MANIFEST_INPUTS[@]}"; do
  ARGS_STR="$ARGS_STR \"$(winpath "$f")\""
done

cd "$REPO_ROOT"
./gradlew -q :tools:data-pipeline:content:mergeManifests --args="$ARGS_STR"
rm -f "$PREV_MANIFEST" "$CONTINENTS_TSV"

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

# Nome nazione collegato alla propria pagina Wikivoyage: riusa l'URL gia' risolto e salvato nel
# manifest da build-region.sh (edizione italiana preferita, fallback su quella inglese se manca
# il langlink - vedi il commento li'), nessuna nuova risoluzione di lingua lato sito. Facoltativo
# come jq sopra ("Ultimi aggiornamenti"): senza jq i nomi restano semplice testo, come prima.
WIKIVOYAGE_URLS=""
if command -v jq >/dev/null 2>&1; then
  WIKIVOYAGE_URLS="$(jq -r '.regions[] | select(.wikivoyageUrl != null) | [.regionId, .wikivoyageUrl] | @tsv' "$FINAL_MANIFEST")"
fi

wikivoyage_url_for() {
  echo "$WIKIVOYAGE_URLS" | awk -F'\t' -v id="$1" '$1 == id { print $2; exit }'
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
  local wikiUrl
  wikiUrl="$(wikivoyage_url_for "$regionId")"
  [ -n "$wikiUrl" ] && text="<a href=\"$wikiUrl\">$text</a>"
  if is_present "$regionId"; then
    # L'href sopra e' verso Wikivoyage (guida testuale), non verso i dati della regione: poi.db/
    # i .rd5 non vivono piu' sotto site/ (vedi il commento in testa al file) e non hanno una singola
    # pagina browsable a cui linkare - il download vero e proprio passa dagli URL in manifest.json.
    echo "<span class=\"entry\">$text $STATUS_OK_SVG</span>"
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

# Solo caratteri ASCII nei nomi qui sopra (nessun accento): un tr basta per l'id di ancora del
# menu di navigazione sticky, niente traslitterazione.
slug() { echo "$1" | tr '[:upper:] ' '[:lower:]-'; }

CONTINENT_NAV=""
CONTINENT_SECTIONS=""
for continent in "${CONTINENTS[@]}"; do
  continentId="$(slug "$continent")"
  CONTINENT_NAV="$CONTINENT_NAV<a href=\"#$continentId\">$continent</a>"

  REGION_CARDS=""
  currentGroup=""
  groupFlagImg=""
  groupSubRows=""

  # Due livelli di raggruppamento dentro questo continente, un solo passaggio su PILOT_REGIONS
  # filtrato: apre/chiude un gruppo quando groupName cambia rispetto alla riga precedente (le
  # region non contigue della stessa nazione, oggi solo le 3 USA, vanno tenute consecutive in
  # PILOT_REGIONS perche' questo funzioni).
  flush_group() {
    if [ -n "$currentGroup" ]; then
      card="<div class=\"card\"><div class=\"card-head\">$groupFlagImg<span class=\"card-title\">$currentGroup</span></div><ul class=\"subgroup\">$groupSubRows</ul></div>"
      REGION_CARDS="$(printf '%s\n%s' "$REGION_CARDS" "$card")"
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
      card="<div class=\"card\"><div class=\"card-head\">$(flag_img "$flag") $(status_html "$regionId" "$displayName")</div></div>"
      REGION_CARDS="$(printf '%s\n%s' "$REGION_CARDS" "$card")"
    fi
  done
  flush_group

  if [ -z "$REGION_CARDS" ]; then
    body="<p class=\"empty\">Nessuna nazione pubblicata ancora in questo continente.</p>"
  else
    body="<div class=\"card-grid\">$REGION_CARDS</div>"
  fi
  section="<section class=\"continent\" id=\"$continentId\"><h2>$continent</h2>$body</section>"
  CONTINENT_SECTIONS="$(printf '%s\n%s' "$CONTINENT_SECTIONS" "$section")"
done

# "Ultimi aggiornamenti": derivato dalle versioni dei pacchetti (mappa, routing, POI: la piu'
# recente) gia' presenti in ogni region del manifest unito, non serve nessuna cronologia separata da
# mantenere. Raggruppato per version (una per
# ogni run di pubblicazione, non per singola nazione) e limitato alle 10 piu' recenti cosi' la
# sezione resta di dimensione costante anche a copertura mondiale completa (254 nazioni), invece
# di crescere senza limite.
CHANGELOG_HTML=""
if command -v jq >/dev/null 2>&1; then
  while IFS=$'\t' read -r version names; do
    CHANGELOG_HTML="$CHANGELOG_HTML<dt>$version</dt><dd>$names</dd>"
  done < <(jq -r '
    [.regions[] | {displayName, version: ([.map.version, .routing.version, .poi.version] | map(select(. != null)) | max)}
      | select(.version != null and .version != "")]
    | group_by(.version)
    | map({version: .[0].version, names: (map(.displayName) | sort | join(", "))})
    | sort_by(.version)
    | reverse
    | .[:10]
    | .[]
    | [.version, .names]
    | @tsv
  ' "$FINAL_MANIFEST")
fi
[ -n "$CHANGELOG_HTML" ] || CHANGELOG_HTML="<dd class=\"empty\">Nessun aggiornamento ancora pubblicato.</dd>"

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
    max-width: 420px;
    min-height: 44px;
    padding: 10px 14px;
    font-size: 16px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--card-bg);
    color: var(--fg);
    margin: 12px 0;
  }
  .continent-nav {
    position: sticky;
    top: 0;
    z-index: 5;
    display: flex;
    gap: 8px;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    background: var(--bg);
    padding: 8px 0;
    margin: 0 0 4px;
    border-bottom: 1px solid var(--border);
  }
  .continent-nav a {
    flex: 0 0 auto;
    display: flex;
    align-items: center;
    min-height: 32px;
    padding: 4px 14px;
    border: 1px solid var(--border);
    border-radius: 999px;
    text-decoration: none;
    font-size: .9em;
    white-space: nowrap;
  }
  .changelog { margin: 12px 0; border: 1px solid var(--border); border-radius: 10px; padding: 10px 14px; background: var(--card-bg); }
  .changelog summary { cursor: pointer; font-weight: 600; padding: 4px 0; }
  .changelog-list { margin: 8px 0 0; }
  .changelog-list dt { font-weight: 600; margin-top: 10px; }
  .changelog-list dt:first-child { margin-top: 0; }
  .changelog-list dd { margin: 2px 0 0; color: var(--muted); }
  .continent { min-width: 0; padding-bottom: 20px; margin-bottom: 20px; border-bottom: 1px solid var(--border); }
  .continent:last-child { border-bottom: none; margin-bottom: 0; padding-bottom: 0; }
  /* Colonne di larghezza uniforme (niente piu' card larghe quanto il contenuto, che rendeva le
     righe irregolari): 190px basta a tenere la quasi totalita' dei nomi su una riga sola: solo i
     pochi nomi davvero lunghi (es. "Territorio Britannico dell'Oceano Indiano") vanno a capo su
     due righe invece di essere tagliati - nessuna ellissi forzata. */
  .card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 12px; margin-top: 10px; }
  .card { background: var(--card-bg); border: 1px solid var(--border); border-radius: 12px; padding: 12px 14px; min-width: 0; box-shadow: 0 1px 3px rgba(0,0,0,.06); }
  .card-head { display: flex; align-items: center; gap: 8px; }
  .card-title { font-weight: 600; min-width: 0; }
  ul { padding-left: 0; }
  li { list-style: none; margin: 4px 0; }
  .entry { display: inline-flex; align-items: center; gap: 4px; }
  .flag { border: 1px solid var(--border); border-radius: 2px; flex-shrink: 0; }
  .status { flex-shrink: 0; }
  .card .subgroup { padding-left: 0; margin: 10px 0 0; border-top: 1px solid var(--border); padding-top: 8px; }
  h2 { font-size: 1.05em; margin: 0; scroll-margin-top: 56px; }
  .empty { color: var(--muted); font-style: italic; margin: 4px 0; }
  #no-results { color: var(--muted); font-style: italic; }
</style>
<p>Questo host serve solo dati statici per l'app <a href="https://github.com/miracle091/pocket-travel">Pocket Travel</a>.</p>
<p><a href="manifest.json">manifest.json</a></p>
<details class="changelog">
  <summary>Ultimi aggiornamenti</summary>
  <dl class="changelog-list">$CHANGELOG_HTML</dl>
</details>
<input type="search" id="search" placeholder="Cerca una nazione…" aria-label="Cerca una nazione">
<p id="no-results" hidden>Nessun risultato.</p>
<nav class="continent-nav" aria-label="Vai al continente">$CONTINENT_NAV</nav>
<div class="continents">
$CONTINENT_SECTIONS
</div>
<script>
(function () {
  var input = document.getElementById('search');
  var noResults = document.getElementById('no-results');
  if (!input) return;
  var sections = document.querySelectorAll('.continent');

  function groupLabelText(card) {
    var clone = card.cloneNode(true);
    var sub = clone.querySelector('.subgroup');
    if (sub) sub.remove();
    return clone.textContent.toLowerCase();
  }

  input.addEventListener('input', function () {
    var q = input.value.trim().toLowerCase();
    var anyVisibleTotal = false;
    sections.forEach(function (section) {
      var cards = section.querySelectorAll(':scope > .card-grid > .card');
      if (cards.length === 0) {
        var placeholderVisible = !q;
        section.style.display = placeholderVisible ? '' : 'none';
        if (placeholderVisible) anyVisibleTotal = true;
        return;
      }
      var anyVisible = false;
      cards.forEach(function (card) {
        var subUl = card.querySelector('.subgroup');
        if (!subUl) {
          var match = !q || card.textContent.toLowerCase().indexOf(q) !== -1;
          card.style.display = match ? '' : 'none';
          if (match) anyVisible = true;
          return;
        }
        var groupMatch = !q || groupLabelText(card).indexOf(q) !== -1;
        var subItems = subUl.querySelectorAll('li');
        var anySubVisible = false;
        subItems.forEach(function (sub) {
          var visible = groupMatch || sub.textContent.toLowerCase().indexOf(q) !== -1;
          sub.style.display = visible ? '' : 'none';
          if (visible) anySubVisible = true;
        });
        card.style.display = anySubVisible ? '' : 'none';
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
