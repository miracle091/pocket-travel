#!/usr/bin/env bash
# Job "build" di publish-regions.yml: genera i pacchetti delle regioni di uno shard (build-region.sh, build-cities.sh),
# li carica sulle release "region-data-rNN" / "region-data-car-rNN" e lascia in $RUNNER_TEMP/fragments i frammenti di
# catalogo, i tempi per regione (timings-<shard>.tsv), le regioni fallite (failed-<shard>.txt) e quelle rimandate per
# il budget (deferred-<shard>.txt). Scrive has_fragments=true|false in $GITHUB_OUTPUT.
# Ambiente: SHARD, SELECTED e EXTRA (output del job "plan" per lo shard), JOB_START, TIME_BUDGET_MINUTES,
# REGION_TIMEOUT_MINUTES, TRANSLATE_TOTAL_MINUTES, PAGES_BASE_URL, GITHUB_REPOSITORY, GITHUB_RUN_NUMBER,
# GITHUB_RUN_ATTEMPT, GH_TOKEN; per gli script della pipeline POI_MAX_AGE_DAYS, WIKIVOYAGE_DUMP_CACHE,
# CITY_FACTS_CACHE, RD5_RANGE_CACHE, GEOFABRIK_CACHE, TRANSLATE_MAX_SECONDS.
set -euo pipefail
source .github/scripts/env.sh
# Fuori dal workflow, gli stessi valori; le regioni da costruire sono SELECTED (regionId separati da virgola).
: "${SHARD:=0}" "${SELECTED:=}" "${EXTRA:=}" "${JOB_START:=$(date +%s)}" "${TIME_BUDGET_MINUTES:=280}" "${REGION_TIMEOUT_MINUTES:=45}"
: "${TRANSLATE_TOTAL_MINUTES:=40}" "${POI_MAX_AGE_DAYS:=6}"
export POI_MAX_AGE_DAYS
source tools/data-pipeline/scripts/regions.sh
# shellcheck source=tools/data-pipeline/scripts/lib.sh
source tools/data-pipeline/scripts/lib.sh
# Tetto comune di traduzione delle citta' per l'intero shard (translate_overlay in lib.sh).
TRANSLATE_DEADLINE=$(( $(date +%s) + TRANSLATE_TOTAL_MINUTES * 60 ))
export TRANSLATE_DEADLINE

if [ -z "$SELECTED" ] && [ -z "$EXTRA" ]; then
  echo "-- shard $SHARD vuoto, nulla da fare"
  echo "has_fragments=false" >> "$GITHUB_OUTPUT"
  exit 0
fi

OUTPUT_ROOT="$RUNNER_TEMP/regions-out"
# Pages ospita solo manifest.json + index.html + bandiere (pochi KB, mai vicino al
# limite di 1GB). poi.db e i .rd5 (il grosso dei dati) vivono invece sugli asset
# della release "region-data": a differenza di Pages, che sostituisce l'intero sito ad
# ogni deploy, gli asset di una release non spariscono mai da soli - vedi il commento in
# testa a assemble-site.sh per il problema che questo risolve (sito cumulativo sopra
# 1GB con 254 regioni).
RELEASE_BASE_URL_PREFIX="https://github.com/$GITHUB_REPOSITORY/releases/download"
PUBLISHED_MANIFEST_URL="$PAGES_BASE_URL/manifest.json"
# Data + numero e tentativo della run (YYYY.MM.DD.<run>.<tentativo>): con la sola data una
# seconda run nello stesso giorno ricaricherebbe con --clobber asset con lo stesso nome (e byte
# diversi) gia' referenziati dal `manifest.json` online. Gli script leggono la data dai nomi degli
# asset ignorando il suffisso (vedi POI_DATE in build-region.sh).
VERSION="$(date -u +%Y.%m.%d).$GITHUB_RUN_NUMBER.$GITHUB_RUN_ATTEMPT"
mkdir -p "$OUTPUT_ROOT" "$RUNNER_TEMP/fragments"

# `manifest.json` pubblicato scaricato una volta per shard e passato come file locale a
# build-region.sh e build-cities.sh (fetch_published_manifest in lib.sh),
# invece di due download per regione. Stessa semantica degli script: non scaricabile =
# errore, 404 = prima pubblicazione (si lascia l'URL, gli script vedono lo stesso 404).
PUBLISHED_MANIFEST_FILE="$RUNNER_TEMP/published-manifest.json"
PUBLISHED_MANIFEST_HTTP="$(fetch_published_manifest "$PUBLISHED_MANIFEST_URL" "$PUBLISHED_MANIFEST_FILE")"
if [ "$PUBLISHED_MANIFEST_HTTP" = "200" ]; then
  PUBLISHED_MANIFEST_URL="$PUBLISHED_MANIFEST_FILE"
elif [ "$PUBLISHED_MANIFEST_HTTP" != "404" ]; then
  echo "ERRORE: manifest.json pubblicato non scaricabile da $PUBLISHED_MANIFEST_URL (HTTP ${PUBLISHED_MANIFEST_HTTP:-000})" >&2
  exit 1
fi

# Risolta una volta per l'intero shard (non da ogni build-region.sh, vedi lib.sh): stessa
# build Protomaps per tutte le regioni di questa run, evita fino a 4 richieste HEAD
# identiche ripetute per ogni singola regione dello shard.
PROTOMAPS_DATE_OVERRIDE="$(resolve_protomaps_date)"
export PROTOMAPS_DATE_OVERRIDE
echo "-- build Protomaps: ${PROTOMAPS_DATE_OVERRIDE}.pmtiles"

# Una regione che fallisce (es. query Overpass troppo pesante per un bbox enorme) non
# deve far perdere il lavoro gia' fatto per le regioni precedenti nello stesso shard: con
# "set -e" un fallimento non protetto da if/else interromperebbe subito lo script, e le
# regioni gia' completate non verrebbero pubblicate. L'if/else qui sotto e' una delle eccezioni
# riconosciute da "set -e" (il comando testato in una condizione if non fa terminare lo
# script), quindi si continua con le regioni successive.
FAILED_REGIONS=()
# Motivo di ogni regione fallita (stesso ordine di FAILED_REGIONS), per il riepilogo e per il
# nuovo tentativo nella continuazione (failed-<shard>.txt).
FAILED_REASONS=()
IFS=',' read -ra SHARD_REGIONS <<< "$SELECTED"
# Coda della quota adattiva ("id:secondi stimati", vedi il job plan): dopo le regioni del giorno,
# ognuna solo se la sua stima ci sta ancora nel budget; quelle che non ci stanno non si rimandano.
declare -A EXTRA_ESTIMATE=()
EXTRA_REGIONS=()
if [ -n "${EXTRA:-}" ]; then
  IFS=',' read -ra EXTRA_ITEMS <<< "$EXTRA"
  for item in "${EXTRA_ITEMS[@]}"; do
    EXTRA_REGIONS+=("${item%%:*}")
    EXTRA_ESTIMATE[${item%%:*}]="${item##*:}"
  done
fi
EXTRA_DONE=()
EXTRA_SKIPPED=0
declare -A SPEC_BY_ID=()
for spec in "${ALL_REGIONS[@]}"; do SPEC_BY_ID[${spec%%|*}]="$spec"; done
# Budget dall'inizio del job (JOB_START), dump delle citta' compreso. SECONDS conta dall'inizio di
# questo script: il tempo gia' passato prima si toglie dal budget. Almeno una regione parte sempre,
# cosi' ogni continuazione fa qualche progresso.
if ! [[ "$TIME_BUDGET_MINUTES" =~ ^[0-9]+$ ]]; then
  echo "time_budget_minutes non valido: '$TIME_BUDGET_MINUTES' (minuti, intero)" >&2
  exit 1
fi
BUDGET_SECONDS=$((TIME_BUDGET_MINUTES * 60 - ($(date +%s) - JOB_START)))
DEFERRED_REGIONS=()
startedRegions=0

# Citta' delle regioni: un solo passaggio sui dump di Wikivoyage IT ed EN per l'intero shard (build-cities-dump.sh,
# di solito ~6 minuti con le richieste a Wikidata e Wikipedia), in sottofondo mentre parte la prima regione: il primo
# build-cities.sh lo aspetta (wait_cities_dump). Mai fatale: se fallisce le regioni di questa run restano senza citta'
# nuove (build-cities.sh tiene la voce gia' pubblicata).
CITIES_REGIONS_TSV="$RUNNER_TEMP/cities-regions.tsv"
: > "$CITIES_REGIONS_TSV"
for spec in "${ALL_REGIONS[@]}"; do
  IFS='|' read -r regionId _displayName _minLon _minLat _maxLon _maxLat wikiTitle _flag _groupName _groupLabel _continent <<< "$spec"
  for r in "${SHARD_REGIONS[@]}" "${EXTRA_REGIONS[@]}"; do
    [ "$r" = "$regionId" ] || continue
    printf '%s\t%s\n' "$regionId" "$wikiTitle" >> "$CITIES_REGIONS_TSV"
    break
  done
done
CITIES_JSONL_DIR="$RUNNER_TEMP/cities-jsonl"
CITIES_DUMP_LOG="$RUNNER_TEMP/cities-dump.log"
mkdir -p "$CITIES_JSONL_DIR"
(
  tools/data-pipeline/scripts/build-cities-dump.sh "$CITIES_REGIONS_TSV" "$CITIES_JSONL_DIR" \
    || echo "::warning::dump citta' non generato per questo shard, regioni senza citta' nuove"
  # Citta' in inglese (voce "citiesEn"): dump di Wikivoyage EN, stessa cartella (file .cities-en.jsonl).
  tools/data-pipeline/scripts/build-cities-dump.sh "$CITIES_REGIONS_TSV" "$CITIES_JSONL_DIR" en \
    || echo "::warning::dump citta' EN non generato per questo shard, regioni senza citta' inglesi nuove"
) > "$CITIES_DUMP_LOG" 2>&1 &
CITIES_DUMP_PID=$!
# Aspetta il dump delle citta' (solo la prima volta) e ne mostra il log.
wait_cities_dump() {
  [ -n "$CITIES_DUMP_PID" ] || return 0
  local start=$SECONDS
  wait "$CITIES_DUMP_PID" || true
  CITIES_DUMP_PID=""
  echo "-- dump delle citta' dello shard (in sottofondo, atteso $((SECONDS - start)) s):"
  cat "$CITIES_DUMP_LOG"
}

# Prima le regioni del giorno (nell'ordine di regions.sh), poi la coda della quota adattiva.
ORDERED_REGIONS=()
for spec in "${ALL_REGIONS[@]}"; do
  id="${spec%%|*}"
  for r in "${SHARD_REGIONS[@]}"; do [ "$r" = "$id" ] && ORDERED_REGIONS+=("$id") && break; done
done
ORDERED_REGIONS+=("${EXTRA_REGIONS[@]}")
for regionId in "${ORDERED_REGIONS[@]}"; do
  spec="${SPEC_BY_ID[$regionId]:-}"
  [ -n "$spec" ] || continue
  IFS='|' read -r regionId displayName minLon minLat maxLon maxLat _wikiTitle _flag _groupName _groupLabel _continent <<< "$spec"
  if [ -n "${EXTRA_ESTIMATE[$regionId]:-}" ]; then
    if [ $((SECONDS + EXTRA_ESTIMATE[$regionId])) -gt "$BUDGET_SECONDS" ]; then
      EXTRA_SKIPPED=$((EXTRA_SKIPPED + 1))
      continue
    fi
    EXTRA_DONE+=("$regionId")
  elif [ "$startedRegions" -gt 0 ] && [ "$SECONDS" -ge "$BUDGET_SECONDS" ]; then
    DEFERRED_REGIONS+=("$regionId")
    continue
  fi
  startedRegions=$((startedRegions + 1))

  releaseTag="$(region_release_tag "$regionId")"
  RELEASE_BASE_URL="$RELEASE_BASE_URL_PREFIX/$releaseTag"
  echo "::group::$regionId"
  regionStart=$SECONDS
  regionOut="$OUTPUT_ROOT/$regionId"
  # timeout segnala l'intero gruppo di processi (anche java, python e curl lanciati dallo script).
  if timeout --kill-after=2m "${REGION_TIMEOUT_MINUTES}m" \
    tools/data-pipeline/scripts/build-region.sh "$regionId" "$displayName" "$VERSION" \
    "$minLon" "$minLat" "$maxLon" "$maxLat" "$RELEASE_BASE_URL" "$regionOut" \
    "$PUBLISHED_MANIFEST_URL"; then
    # L'attesa del dump non conta nel tempo della regione (region-timings.tsv pesa gli shard delle prossime run).
    waitStart=$SECONDS
    wait_cities_dump
    regionStart=$((regionStart + SECONDS - waitStart))
    # Citta' (facoltative, mai fatali): aggiunge la voce "cities" al frammento e, se il
    # dump e' stato estratto per questa regione (vedi CITIES_JSONL_DIR sopra) e produce
    # sezioni, lascia cities.db.xz da caricare.
    tools/data-pipeline/scripts/build-cities.sh "$regionId" "$VERSION" \
      "$RELEASE_BASE_URL" "$regionOut" "$CITIES_JSONL_DIR" \
      "$PUBLISHED_MANIFEST_URL" || echo "::warning::citta' di $regionId non generate"
    tools/data-pipeline/scripts/build-cities.sh "$regionId" "$VERSION" \
      "$RELEASE_BASE_URL" "$regionOut" "$CITIES_JSONL_DIR" \
      "$PUBLISHED_MANIFEST_URL" en || echo "::warning::citta' EN di $regionId non generate"
    if [ -f "$regionOut/.skipped" ]; then
      # Controllo di necessita' in build-region.sh: tile .rd5 attese invariate rispetto
      # al `manifest.json` gia' pubblicato, il frammento e' quello di sempre, niente da
      # ricaricare sulla release (vedi build-region.sh, sezione "2bis").
      echo "-- $regionId invariata: riuso il frammento pubblicato, nessun asset da ricaricare"
    else
      # poi.db.xz, poi-extra.db.xz + segmenti .rd5 (zero o piu' a seconda di quante tile intersecano il
      # bbox) caricati come asset della release, rinominati con un prefisso
      # regionId--version-- per essere univoci nell'intera release (che accumula tutte
      # le regioni). --clobber: un nuovo tentativo di upload qui sotto deve poter
      # sovrascrivere l'asset caricato a meta' dal tentativo fallito (VERSION e' unica per
      # run e tentativo, quindi non tocca mai asset di altre run).
      # In modalita' incrementale (.incremental, vedi build-region.sh sezione 2bis) si
      # caricano solo le tile cambiate, piu' poi.db.xz/poi-extra.db.xz se erano scaduti e sono cambiati,
      # e cities.db.xz se build-cities.sh ne ha prodotto uno nuovo.
      uploads=()
      # I POI, la mappa d'insieme e le citta' si caricano solo compressi (vedi xz_entry
      # in lib.sh, build_preview in build-region.sh).
      for xz in poi.db.xz poi-extra.db.xz preview.pmtiles.xz cities.db.xz cities-en.db.xz; do
        [ -f "$regionOut/$xz" ] || continue
        xzAsset="$regionOut/${regionId}--${VERSION}--$xz"
        cp "$regionOut/$xz" "$xzAsset"
        uploads+=("$xzAsset")
      done
      for rd5 in "$regionOut"/*.rd5; do
        [ -e "$rd5" ] || continue
        rd5Asset="$regionOut/${regionId}--${VERSION}--$(basename "$rd5")"
        cp "$rd5" "$rd5Asset"
        uploads+=("$rd5Asset")
      done
      # Variante "solo auto" (build-region.sh, cartella car/): sulla release region-data-car-rNN.
      carUploads=()
      for rd5 in "$regionOut"/car/*.rd5; do
        [ -e "$rd5" ] || continue
        carAsset="$regionOut/car/${regionId}--${VERSION}--car-$(basename "$rd5")"
        cp "$rd5" "$carAsset"
        carUploads+=("$carAsset")
      done
      # Fino a 3 tentativi: l'API a volte risponde "release not found" su una release che
      # esiste (tanzania, run del 2026-09-24). --clobber rende sicuro il ricaricamento.
      # Nessun file (es. la regione ha perso solo i POI extra): cambia solo il manifest.
      uploadOk=true
      [ "${#uploads[@]}" -eq 0 ] || uploadOk=false
      for attempt in 1 2 3; do
        [ "${#uploads[@]}" -gt 0 ] || break
        if gh release upload "$releaseTag" "${uploads[@]}" --repo "$GITHUB_REPOSITORY" --clobber; then
          uploadOk=true
          break
        fi
        if [ "$attempt" -lt 3 ]; then
          echo "-- upload di $regionId fallito (tentativo $attempt/3), riprovo tra $((attempt * 30))s"
          sleep $((attempt * 30))
        fi
      done
      carReleaseTag="${releaseTag/region-data-r/region-data-car-r}"
      for attempt in 1 2 3; do
        [ "$uploadOk" = true ] && [ "${#carUploads[@]}" -gt 0 ] || break
        if gh release upload "$carReleaseTag" "${carUploads[@]}" --repo "$GITHUB_REPOSITORY" --clobber; then
          break
        fi
        if [ "$attempt" -lt 3 ]; then
          echo "-- upload della rete stradale solo auto di $regionId fallito (tentativo $attempt/3), riprovo tra $((attempt * 30))s"
          sleep $((attempt * 30))
        else
          uploadOk=false
        fi
      done
      if [ "$uploadOk" != true ]; then
        echo "::warning::upload asset di $regionId fallito, salto e continuo con le prossime"
        FAILED_REGIONS+=("$regionId")
        FAILED_REASONS+=("upload degli asset fallito")
        rm -rf "$regionOut"
        echo "::endgroup::"
        continue
      fi
    fi
    # Nome unico per regione: piu' regioni condividerebbero altrimenti lo stesso nome di
    # file "manifest-fragment.json" una volta appiattite nella stessa cartella artifact.
    cp "$regionOut/manifest-fragment.json" "$RUNNER_TEMP/fragments/${regionId}.json"
    # Copia durevole subito, oltre all'artifact di fine passo: se il job muore dopo (annullato,
    # runner perso) il job "publish" di una run successiva la recupera (selectFragments).
    # Non fatale: senza, resta il percorso normale dell'artifact.
    cp "$regionOut/manifest-fragment.json" "$regionOut/${VERSION}--${regionId}.json"
    gh release upload region-fragments "$regionOut/${VERSION}--${regionId}.json" --repo "$GITHUB_REPOSITORY" --clobber \
      || echo "::warning::frammento di $regionId non salvato su region-fragments (resta l'artifact di fine passo)"
    # Durata della regione, per bilanciare gli shard delle prossime run (region-timings.tsv).
    # Solo le build complete: non le regioni saltate perche' invariate ne' gli aggiornamenti
    # incrementali, che abbasserebbero la misura (una settimana dopo la regione puo' dover
    # ripartire da zero).
    if [ ! -f "$regionOut/.skipped" ] && [ ! -f "$regionOut/.incremental" ]; then
      printf '%s\t%d\n' "$regionId" "$((SECONDS - regionStart))" >> "$RUNNER_TEMP/fragments/timings-$SHARD.tsv"
    fi
    # File della regione gia' caricati: via dal disco (una copia rinominata per asset li
    # raddoppierebbe), cosi' resta posto per le cache di Geofabrik e dei .rd5 dello shard.
    rm -rf "$regionOut"
  else
    rc=$?
    if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
      echo "::warning::regione $regionId oltre ${REGION_TIMEOUT_MINUTES} min, interrotta: salto e continuo con le prossime"
      FAILED_REASONS+=("oltre ${REGION_TIMEOUT_MINUTES} min")
    else
      echo "::warning::regione $regionId fallita, salto e continuo con le prossime"
      FAILED_REASONS+=("build-region.sh uscito con $rc")
    fi
    FAILED_REGIONS+=("$regionId")
    rm -rf "$regionOut"
  fi
  echo "::endgroup::"
done

wait_cities_dump
# Regioni fallite con il motivo: la prima continuazione le riprova una volta (job "publish").
for idx in "${!FAILED_REGIONS[@]}"; do
  printf '%s\t%s\n' "${FAILED_REGIONS[$idx]}" "${FAILED_REASONS[$idx]}" >> "$RUNNER_TEMP/fragments/failed-$SHARD.txt"
done
# Regioni rimandate: le rilancia il job "publish" (continuazione automatica).
if [ "${#EXTRA_REGIONS[@]}" -gt 0 ]; then
  echo "-- quota adattiva: ${#EXTRA_DONE[@]} regioni in piu' avviate (${EXTRA_DONE[*]:-nessuna}), $EXTRA_SKIPPED non entrate nel budget"
fi
if [ "${#DEFERRED_REGIONS[@]}" -gt 0 ]; then
  printf '%s\n' "${DEFERRED_REGIONS[@]}" > "$RUNNER_TEMP/fragments/deferred-$SHARD.txt"
  echo "::warning::budget di ${TIME_BUDGET_MINUTES} min superato: ${#DEFERRED_REGIONS[@]} regioni rimandate alla continuazione: ${DEFERRED_REGIONS[*]}"
fi

# Riepilogo nella pagina del job, per non dover cercare i problemi nel log.
{
  echo "### Shard $SHARD"
  if [ "${#FAILED_REGIONS[@]}" -gt 0 ]; then
    echo "Regioni fallite (non pubblicate, da rilanciare a parte): ${FAILED_REGIONS[*]}"
    echo
  elif [ "${#DEFERRED_REGIONS[@]}" -eq 0 ]; then
    echo "Nessun problema."
  fi
  if [ "${#DEFERRED_REGIONS[@]}" -gt 0 ]; then
    echo "Regioni rimandate (budget di ${TIME_BUDGET_MINUTES} min superato, rilanciate dalla continuazione): ${DEFERRED_REGIONS[*]}"
  fi
} >> "$GITHUB_STEP_SUMMARY"

fragmentCount="$(find "$RUNNER_TEMP/fragments" -maxdepth 1 -name '*.json' | wc -l)"
if [ "$fragmentCount" -eq 0 ]; then
  # Con regioni rimandate o fallite l'artifact serve comunque, per la continuazione.
  if [ "${#DEFERRED_REGIONS[@]}" -gt 0 ] || [ "${#FAILED_REGIONS[@]}" -gt 0 ]; then
    echo "has_fragments=true" >> "$GITHUB_OUTPUT"
  else
    echo "has_fragments=false" >> "$GITHUB_OUTPUT"
  fi
  echo "Nessuna regione completata con successo in questo shard: $SELECTED" >&2
  exit 1
fi
if [ "${#FAILED_REGIONS[@]}" -gt 0 ]; then
  echo "::warning::regioni fallite in questo shard (non pubblicate, da rilanciare a parte): ${FAILED_REGIONS[*]}"
fi
echo "has_fragments=true" >> "$GITHUB_OUTPUT"
