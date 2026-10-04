#!/usr/bin/env bash
# Genera guides.db, il pacchetto guide unico per tutte le regioni di pilot-regions.sh (sezioni
# Wikivoyage + numeri di emergenza, via il tool Kotlin generateGuides), e il suo frammento
# manifest.json (voce "guides"). L'app lo scarica una volta per tutte le nazioni, separato dai
# pacchetti per regione (mappa, POI, routing) di build-region.sh, e lo aggiorna da solo: pesa
# meno di un MB compresso.
#
# Uso: build-guides.sh <version> <assetBaseUrl> <outputDir> [publishedManifestUrl] [it|en]
#
# Con "en" (default "it"): guida inglese dalle pagine di Wikivoyage EN, asset guides-en--<version>--
# guides.db.xz e voce "guidesEn" del manifest (l'app la scarica quando e' in inglese). Stessi
# guides.db.xz/manifest-fragment.json in <outputDir>: si usa una cartella diversa per lingua.
#
# Scrive sempre <outputDir>/manifest-fragment.json. <outputDir>/guides.db e guides.db.xz esistono
# solo se il contenuto e' cambiato rispetto al guides.db pubblicato (guides.db.xz va caricato come
# asset guides--<version>--guides.db.xz sotto <assetBaseUrl> - unico asset pubblicato, stesso
# trattamento di poi.db/preview.pmtiles in build-region.sh: guides.db 6,3 MB -> 1,5 MB con xz, vedi
# xz_entry in lib.sh); altrimenti il frammento riusa la voce gia' pubblicata, stessa versione, e
# l'app non riscarica nulla.
#
# Una pagina Wikivoyage che non si riesce a scaricare in questa run non fa sparire la guida di
# quella regione: generateGuides ricopia le sue sezioni dal guides.db pubblicato.
#
# Nello stesso file la tabella diplomatic_missions (ambasciate e consolati di tutto il mondo, da
# Wikidata via wikidata_missions.py; l'app piu' vecchia la ignora). Se Wikidata non risponde si
# ricopia la tabella dal guides.db pubblicato, e in mancanza anche di quella si pubblica senza,
# con un avviso: le guide non falliscono mai per questo. Wikidata cambia ogni giorno: le sole
# missioni cambiate non producono un nuovo guides.db se quelle pubblicate hanno meno di 14 giorni
# (data nella tabella guides_meta); una modifica alle guide lo pubblica subito, con missioni fresche.
#
# Sezioni tradotte dall'altra lingua (sezione 3a): con TRANSLATE_CACHE_DIR e i modelli attivi (vedi translate_overlay in
# lib.sh) le categorie assenti o molto piu' povere di quelle dell'altra lingua (translate_sections.needs_translation) sono
# sostituite da quelle tradotte (translate_guides.py), con translated = 1 in guide_sections e l'url della pagina
# d'origine. La passata "en" va lanciata prima di quella italiana, con GUIDES_PLAIN_COPY=<file> (dove lascia la guida
# inglese non arricchita) e poi GUIDES_SOURCE_DB=<lo stesso file> per l'italiana; GUIDES_TRANSLATE_SECONDS e' il tempo
# massimo di traduzione della passata. Senza o se qualcosa non riesce, guide come prima.
#
# Richiede: curl, jq (solo se si passa publishedManifestUrl), xz, python3, gradle wrapper dalla root del repo.
set -euo pipefail

if [ "$#" -lt 3 ] || [ "$#" -gt 5 ]; then
  echo "Uso: $0 <version> <assetBaseUrl> <outputDir> [publishedManifestUrl] [it|en]" >&2
  exit 1
fi

VERSION="$1"
ASSET_BASE_URL="${2%/}"
OUTPUT_DIR="$3"
PUBLISHED_MANIFEST_URL="${4:-}"
LANG_CODE="${5:-it}"
case "$LANG_CODE" in
  it) MANIFEST_KEY="guides"; ASSET_PREFIX="guides" ;;
  en) MANIFEST_KEY="guidesEn"; ASSET_PREFIX="guides-en" ;;
  *) echo "Lingua non supportata: $LANG_CODE" >&2; exit 1 ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"
# shellcheck source=./pilot-regions.sh
source "$SCRIPT_DIR/pilot-regions.sh"

mkdir -p "$OUTPUT_DIR"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

# --- 1. Pagine Wikivoyage di tutte le regioni ---------------------------------------------------
REGIONS_TSV="$WORKDIR/regions.tsv"
: > "$REGIONS_TSV"
REGIONS_IT_TSV="$WORKDIR/regions-it.tsv"
: > "$REGIONS_IT_TSV"
FAILED=0
for spec in "${PILOT_REGIONS[@]}"; do
  IFS='|' read -r regionId _ _ _ _ _ wikiTitle _ <<< "$spec"
  dump="$WORKDIR/$regionId.txt"
  if [ "$LANG_CODE" = "en" ]; then
    # Pagina inglese per le sezioni, quella italiana solo per i fatti rapidi (vedi englishQuickFactsSection).
    dumpIt="$WORKDIR/$regionId.it.txt"
    if sourceUrl="$(fetch_wikivoyage_en_dump "$wikiTitle" "$dump")"; then
      sourceUrlIt="$(fetch_wikivoyage_dump "$wikiTitle" "$dumpIt")" || { : > "$dumpIt"; sourceUrlIt=""; }
      # La stessa pagina italiana, per la guida italiana "semplice" che serve a tradurre l'inglese povero (sezione 3a).
      printf '%s\t%s\t%s\n' "$regionId" "$(winpath "$dumpIt")" "$sourceUrlIt" >> "$REGIONS_IT_TSV"
      printf '%s\t%s\t%s\t%s\n' "$regionId" "$(winpath "$dump")" "$sourceUrl" "$(winpath "$dumpIt")" >> "$REGIONS_TSV"
    else
      echo "-- $regionId: pagina Wikivoyage EN $wikiTitle non scaricata, tengo la guida gia' pubblicata" >&2
      printf '%s\t\t\n' "$regionId" >> "$REGIONS_TSV"
      FAILED=$((FAILED + 1))
    fi
    continue
  fi
  if sourceUrl="$(fetch_wikivoyage_dump "$wikiTitle" "$dump")"; then
    # Pagina italiana molto corta (una vera pagina paese e' 20-130 KB; la Siberia, solo titoli, 4
    # KB): si scarica anche quella inglese, e generateGuides la usa se l'italiana non da' sezioni.
    # Solo sotto soglia, per non raddoppiare le richieste a Wikimedia.
    dumpEn="$WORKDIR/$regionId.en.txt"
    if [[ "$sourceUrl" == https://it.* ]] && [ "$(wc -c < "$dump")" -lt 8000 ] \
      && sourceUrlEn="$(fetch_wikivoyage_en_dump "$wikiTitle" "$dumpEn")"; then
      printf '%s\t%s\t%s\t%s\t%s\n' "$regionId" "$(winpath "$dump")" "$sourceUrl" "$(winpath "$dumpEn")" "$sourceUrlEn" >> "$REGIONS_TSV"
    else
      printf '%s\t%s\t%s\n' "$regionId" "$(winpath "$dump")" "$sourceUrl" >> "$REGIONS_TSV"
    fi
  else
    echo "-- $regionId: pagina Wikivoyage $wikiTitle non scaricata, tengo la guida gia' pubblicata" >&2
    printf '%s\t\t\n' "$regionId" >> "$REGIONS_TSV"
    FAILED=$((FAILED + 1))
  fi
done
if [ "$FAILED" -eq "${#PILOT_REGIONS[@]}" ]; then
  echo "ERRORE: nessuna pagina Wikivoyage scaricata" >&2
  exit 1
fi
echo "-- pagine Wikivoyage: $(( ${#PILOT_REGIONS[@]} - FAILED ))/${#PILOT_REGIONS[@]} scaricate"

# --- 2. guides.db pubblicato (per il confronto e per le regioni non scaricate) ------------------
PUBLISHED_DB=""
PUBLISHED_URL=""
PUBLISHED_VERSION=""
PUBLISHED_XZ_JSON=""
# Guide pubblicate non lette (errore di rete) mentre alcune pagine Wikivoyage mancano: le regioni di
# quelle pagine uscirebbero senza guida. Meglio fermarsi e lasciare le guide gia' pubblicate.
published_unreadable() {
  if [ "$FAILED" -gt 0 ]; then
    echo "ERRORE: $1 e $FAILED pagine Wikivoyage non scaricate: non pubblico guide incomplete" >&2
    exit 1
  fi
}
if [ -n "$PUBLISHED_MANIFEST_URL" ] && command -v jq >/dev/null 2>&1; then
  if ! curl -sSf --max-time 120 --speed-limit 1000 --speed-time 30 --retry 5 --retry-all-errors --retry-delay 5 -o "$WORKDIR/published-manifest.json" "$PUBLISHED_MANIFEST_URL" 2>/dev/null; then
    published_unreadable "manifest pubblicato non scaricato"
  else
    PUBLISHED_URL="$(jq -r --arg k "$MANIFEST_KEY" '.[$k].file.url // ""' "$WORKDIR/published-manifest.json")"
    PUBLISHED_VERSION="$(jq -r --arg k "$MANIFEST_KEY" '.[$k].version // ""' "$WORKDIR/published-manifest.json")"
    PUBLISHED_XZ_JSON="$(jq -c --arg k "$MANIFEST_KEY" '.[$k].fileXz // empty' "$WORKDIR/published-manifest.json")"
    if [ -n "$PUBLISHED_URL" ]; then
      # guides.file.url punta al .xz pubblicato (stesso url di fileXz, vedi sezione 4): lo si
      # decomprime dopo il download per riottenere il database vero, letto piu' sotto da
      # generateGuides. Se punta ancora a un guides.db grezzo (manifest nello schema precedente,
      # prima di questo .xz) si usa cosi' com'e'.
      DOWNLOAD_TARGET="$WORKDIR/published-guides.db"
      [[ "$PUBLISHED_URL" == *.xz ]] && DOWNLOAD_TARGET="$WORKDIR/published-guides.db.xz"
      if curl -sSfL --max-time 900 --speed-limit 1000 --speed-time 60 --retry 5 --retry-all-errors --retry-delay 5 -o "$DOWNLOAD_TARGET" "$PUBLISHED_URL"; then
        if [[ "$PUBLISHED_URL" == *.xz ]]; then
          xz -dc "$DOWNLOAD_TARGET" > "$WORKDIR/published-guides.db" && PUBLISHED_DB="$WORKDIR/published-guides.db"
        else
          PUBLISHED_DB="$DOWNLOAD_TARGET"
        fi
      fi
      [ -n "$PUBLISHED_DB" ] || published_unreadable "guide pubblicate non lette ($PUBLISHED_URL)"
    fi
  fi
fi

# --- 2b. Missioni diplomatiche da Wikidata ------------------------------------------------------
# Un unico TSV per le due lingue: la run IT lo scarica, quella EN (cartella gemella, stesso job) riusa il
# file se e' recente. Sta accanto alla cartella di output, non in un /tmp condiviso tra job e run diverse.
MISSIONS_TSV="${MISSIONS_TSV:-$(dirname "$OUTPUT_DIR")/pocket-travel-wikidata-missions.tsv}"
if [ -z "$(find "$MISSIONS_TSV" -mmin -120 -size +0 2>/dev/null)" ]; then
  rm -f "$MISSIONS_TSV"
  python3 "$SCRIPT_DIR/wikidata_missions.py" --out "$MISSIONS_TSV" --user-agent "$PIPELINE_USER_AGENT" \
    || echo "AVVISO: missioni diplomatiche da Wikidata non scaricate, riuso quelle pubblicate se ci sono" >&2
fi

# --- 3. guides.db ---------------------------------------------------------------------------------
GUIDES_DB="$OUTPUT_DIR/guides.db"
rm -f "$GUIDES_DB"
cd "$REPO_ROOT"
GUIDES_ARGS="\"$(winpath "$REGIONS_TSV")\" \"$(winpath "$GUIDES_DB")\""
# --- 3a. Sezioni povere tradotte dall'altra lingua ------------------------------------------------
# Solo con la traduzione attiva (translate_overlay in lib.sh). La guida "semplice" della lingua (senza guide pubblicate,
# cosi' non si confronta con quelle gia' arricchite) si confronta con quella dell'altra lingua, sempre non arricchita:
# per l'italiano la inglese semplice della passata "en" (GUIDES_SOURCE_DB, la passata inglese gira per prima), per
# l'inglese la italiana costruita qui dalle stesse pagine italiane scaricate per i fatti rapidi (regions-it.tsv).
# Tempo massimo di questa passata: GUIDES_TRANSLATE_SECONDS. Se qualcosa non riesce si pubblica la guida com'e'.
if [ -n "${TRANSLATE_CACHE_DIR:-}" ]; then
  PLAIN_DB="$WORKDIR/plain-guides.db"
  PLAIN_ARGS="\"$(winpath "$REGIONS_TSV")\" \"$(winpath "$PLAIN_DB")\""
  [ "$LANG_CODE" = "en" ] && PLAIN_ARGS="--lang en $PLAIN_ARGS"
  if (cd "$REPO_ROOT" && ./gradlew -q :tools:data-pipeline:content:generateGuides --args="$PLAIN_ARGS" >/dev/null); then
    [ -n "${GUIDES_PLAIN_COPY:-}" ] && cp "$PLAIN_DB" "$GUIDES_PLAIN_COPY"
    SOURCE_DB=""
    if [ "$LANG_CODE" = "en" ]; then
      SOURCE_DB="$WORKDIR/plain-guides-it.db"
      SOURCE_LANG="it"
      (cd "$REPO_ROOT" && ./gradlew -q :tools:data-pipeline:content:generateGuides \
        --args="\"$(winpath "$REGIONS_IT_TSV")\" \"$(winpath "$SOURCE_DB")\"" >/dev/null) || SOURCE_DB=""
    else
      SOURCE_DB="${GUIDES_SOURCE_DB:-}"
      SOURCE_LANG="en"
    fi
    if [ -n "$SOURCE_DB" ] && translate_overlay guides "$PLAIN_DB" "$SOURCE_DB" "$WORKDIR/translated.jsonl" "$SOURCE_LANG" \
      "${GUIDES_TRANSLATE_SECONDS:-3600}"; then
      GUIDES_ARGS="--translated \"$(winpath "$WORKDIR/translated.jsonl")\" $GUIDES_ARGS"
    fi
  else
    echo "::warning::guida semplice non generata, niente traduzioni in questa run" >&2
  fi
fi
[ -s "$MISSIONS_TSV" ] && GUIDES_ARGS="--missions \"$(winpath "$MISSIONS_TSV")\" $GUIDES_ARGS"
[ "$LANG_CODE" = "en" ] && GUIDES_ARGS="--lang en $GUIDES_ARGS"
[ -n "$PUBLISHED_DB" ] && GUIDES_ARGS="$GUIDES_ARGS \"$(winpath "$PUBLISHED_DB")\""
./gradlew -q :tools:data-pipeline:content:generateGuides --args="$GUIDES_ARGS"

# --- 4. Frammento manifest ------------------------------------------------------------------------
# guides.db si pubblica solo compresso (guides--<version>--guides.db.xz, come poi.db/preview.pmtiles
# in build-region.sh): "file" e "fileXz" condividono lo stesso url, "file" descrive pero' il
# contenuto decompresso (nome/dimensione/sha256 che l'app ricontrolla dopo il download).
GUIDES_XZ_URL="${ASSET_BASE_URL}/${ASSET_PREFIX}--${VERSION}--guides.db.xz"
if [ -f "$GUIDES_DB" ]; then
  FRAGMENT_DB="$GUIDES_DB"
  FRAGMENT_URL="$GUIDES_XZ_URL"
  FRAGMENT_VERSION="$VERSION"
else
  echo "-- guide invariate: riuso la voce pubblicata (versione $PUBLISHED_VERSION)"
  FRAGMENT_DB="$PUBLISHED_DB"
  FRAGMENT_URL="$PUBLISHED_URL"
  FRAGMENT_VERSION="$PUBLISHED_VERSION"
fi
./gradlew -q :tools:data-pipeline:content:generateManifest \
  --args="--guides \"$(winpath "$FRAGMENT_DB")\" \"$FRAGMENT_URL\" \"$FRAGMENT_VERSION\" \"$(winpath "$REGIONS_TSV")\" \"$(winpath "$OUTPUT_DIR/manifest-fragment.json")\""

if [ -f "$GUIDES_DB" ]; then
  # Contenuto nuovo: comprime e aggiunge la voce fileXz (vedi xz_entry in lib.sh).
  GUIDES_XZ_JSON="$(xz_entry "$GUIDES_DB" "$GUIDES_XZ_URL")"
  jq -c --argjson xz "$GUIDES_XZ_JSON" '.guides.fileXz = $xz' "$OUTPUT_DIR/manifest-fragment.json" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$OUTPUT_DIR/manifest-fragment.json"
elif [ -n "$PUBLISHED_XZ_JSON" ] && [ "$PUBLISHED_XZ_JSON" != "null" ]; then
  # Guide invariate: si riporta avanti la voce fileXz gia' pubblicata invece di ricomprimere.
  jq -c --argjson xz "$PUBLISHED_XZ_JSON" '.guides.fileXz = $xz' "$OUTPUT_DIR/manifest-fragment.json" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$OUTPUT_DIR/manifest-fragment.json"
fi
if [ "$MANIFEST_KEY" != "guides" ]; then
  # generateManifest scrive sempre "guides": per l'inglese la voce diventa "guidesEn".
  jq -c --arg k "$MANIFEST_KEY" '{manifestVersion, regions: (.regions // []), ($k): .guides}' "$OUTPUT_DIR/manifest-fragment.json" > "$WORKDIR/fragment.json"
  mv "$WORKDIR/fragment.json" "$OUTPUT_DIR/manifest-fragment.json"
fi

echo "== guide fatte: $OUTPUT_DIR/manifest-fragment.json =="
