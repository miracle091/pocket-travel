#!/usr/bin/env bash
# Toglie dalle release "region-data*", "world-map", "address-cells-*" e "transit-feeds" gli asset che il catalogo appena
# pubblicato non referenzia piu'. Gli asset si chiamano regionId--VERSION--file: ogni rigenerazione ne aggiunge di nuovi
# senza togliere i vecchi, e ogni release e' limitata a 1000 asset.
# Periodo di grazia di una generazione: restano anche gli asset referenziati dal catalogo precedente (le app che l'hanno
# appena letto scaricano ancora quegli URL), e quelli caricati nelle ultime 24 ore (possono essere di un'altra run ancora
# in corso).
# Uso: cleanup-assets.sh <sito> <precedenti>: <sito> con manifest.json, address-grid.json e transit.json appena
# pubblicati; <precedenti> con previous-manifest.json, previous-address-grid.json e previous-transit.json (catalogo
# prima della run), o first-publication alla prima pubblicazione.
# Ambiente: GITHUB_REPOSITORY, GH_TOKEN.
set -euo pipefail
source .github/scripts/env.sh
source .github/scripts/retry.sh
SITE_DIR="$1"
PREVIOUS_DIR="$2"
MANIFEST="$SITE_DIR/manifest.json"
PREVIOUS_MANIFEST="$PREVIOUS_DIR/previous-manifest.json"
if [ ! -s "$PREVIOUS_MANIFEST" ]; then
  if [ ! -e "$PREVIOUS_DIR/first-publication" ]; then
    echo "::error::manifest.json precedente mancante senza che sia una prima pubblicazione: non cancello nulla e fermo il passo" >&2
    exit 1
  fi
  echo "::warning::manifest.json precedente non disponibile: non cancello nulla in questa run"
  exit 0
fi
# Le regioni perse per un merge incompleto le ferma gia' il controllo prima del deploy.
# Gli URL delle celle dei civici vivono in address-grid.json, non in manifest.json (che ha
# solo il puntatore "addressGrid"): senza questi due file la pulizia cancellerebbe subito
# gli asset di ogni cella non toccata dalla run di oggi (quasi tutte, vedi la quota
# giornaliera nel job "address-grid").
REFERENCED="$RUNNER_TEMP/referenced.txt"
SCAN_FILES=("$MANIFEST" "$PREVIOUS_MANIFEST")
for f in "$SITE_DIR/address-grid.json" "$PREVIOUS_DIR/previous-address-grid.json" "$SITE_DIR/transit.json" "$PREVIOUS_DIR/previous-transit.json"; do
  if [ -s "$f" ]; then SCAN_FILES+=("$f"); fi
done
cat "${SCAN_FILES[@]}" \
  | grep -oE 'https://github\.com/[^"]+/releases/download/[^"/]+/[^"]+' \
  | sed -E 's#.*/releases/download/##' | sort -u > "$REFERENCED"
if [ ! -s "$REFERENCED" ]; then
  echo "Il catalogo non referenzia alcun asset di release: non cancello nulla" >&2
  exit 1
fi
CUTOFF="$(date -u -d '-24 hours' +%Y-%m-%dT%H:%M:%SZ)"
for tag in $(retry gh release list --repo "$GITHUB_REPOSITORY" --limit 200 --json tagName --jq '.[].tagName' | grep -E '^(region-data|world-map|address-cells-|transit-feeds$)'); do
  deleted=0
  failed=0
  total=0
  # Elenco completo a pagine di 100 (quello dentro la release puo' essere troncato), scritto prima su
  # file e con retry: letto direttamente dall'API, un errore darebbe un elenco vuoto (total=0, kept=0)
  # e una release legacy verrebbe cancellata con i suoi asset. Elenco non leggibile: si salta il tag.
  if ! releaseId="$(retry gh api "repos/$GITHUB_REPOSITORY/releases/tags/$tag" --jq '.id')"; then
    echo "::warning::$tag: release non leggibile dopo i tentativi, la salto fino alla prossima pulizia"
    continue
  fi
  ASSETS_FILE="$RUNNER_TEMP/assets-$tag.tsv"
  list_assets() {
    gh api --paginate "repos/$GITHUB_REPOSITORY/releases/$releaseId/assets?per_page=100" --jq '.[] | [.name, .created_at, .id] | @tsv' > "$ASSETS_FILE"
  }
  if ! retry list_assets; then
    echo "::warning::$tag: elenco degli asset non leggibile dopo i tentativi, salto la release fino alla prossima pulizia"
    continue
  fi
  while IFS=$'\t' read -r name createdAt assetId; do
    [ -n "$name" ] || continue
    total=$((total + 1))
    if ! grep -qxF "$tag/$name" "$REFERENCED" && [[ "$createdAt" < "$CUTOFF" ]]; then
      # Per id e non per nome (una release puo' avere piu' asset con lo stesso nome). Con retry e, se
      # fallisce ancora (es. HTTP 502), solo un avviso: l'asset resta e lo toglie la pulizia della
      # run successiva, non deve far fallire il job dopo il deploy.
      if retry gh api -X DELETE "repos/$GITHUB_REPOSITORY/releases/assets/$assetId" --silent; then
        deleted=$((deleted + 1))
      else
        failed=$((failed + 1))
        echo "::warning::$tag: asset $name non rimosso dopo i tentativi, resta fino alla prossima pulizia"
      fi
    fi
  done < "$ASSETS_FILE"
  kept=$((total - deleted))
  echo "-- $tag: $deleted asset non referenziati rimossi ($failed non rimovibili), $kept restano"
  # Vecchie release per continente (prima di region-data-rNN), vuote dopo che le loro regioni si sono
  # rigenerate: si tolgono, cosi' non restano release vuote ne' serve farlo a mano.
  if [ "$kept" -eq 0 ] && [[ "$tag" == region-data-* ]] && ! [[ "$tag" =~ ^region-data-((car-)?r[0-9]{2}|guide)$ ]]; then
    if retry gh release delete "$tag" --repo "$GITHUB_REPOSITORY" --yes --cleanup-tag; then
      echo "-- $tag: release vuota rimossa"
    else
      echo "::warning::$tag: release vuota non rimossa, ci riprova la prossima pulizia"
    fi
    continue
  fi
  # Durante una rigenerazione completa i nuovi asset si sommano ai vecchi: sopra i 500
  # stabili una release rischia di superare il limite di 1000 (vedi REGION_RELEASES e region_release_tag).
  if [ "$kept" -gt 500 ]; then
    echo "::warning::$tag ha $kept asset: rischia il limite di 1000 durante una rigenerazione completa, aumenta REGION_RELEASES in regions.sh (ADDRESS_CELL_RELEASES per le celle dei civici)"
  fi
done
