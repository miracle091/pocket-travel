#!/usr/bin/env bash
# Un solo passaggio sul dump di Wikivoyage IT
# per TUTTE le regioni passate: risolve la pagina IT di ciascuna (stesso approccio a
# langlink di fetch_wikivoyage_dump, vedi resolve_it_wikivoyage_title in lib.sh) e scrive
# <outDir>/<regionId>.cities.jsonl con le pagine {{QuickbarCity}} che la citano (Stato/Stato
# federato/Regione/Territorio), lette poi da build-cities.sh (una regione alla volta) per generare
# cities.db.
#
# Mai fatale per il chiamante (vedi publish-regions.yml): se il dump (l'ultimo export del 1° del mese, una cartella
# di parti) non si scarica o non passa la verifica sha256 (fetch_wikivoyage_dump_parts in lib.sh), o se nessuna pagina
# IT si risolve, esce 1
# senza scrivere nulla in <outDir> - le regioni di questo run restano senza citta' nuove
# (build-cities.sh tiene la voce "cities" gia' pubblicata, se c'e').
#
# Uso: build-cities-dump.sh <regioni.tsv> <outDir> [it|en]
# regioni.tsv: righe "regionId<TAB>wikiTitle" (titolo EN Wikivoyage, da regions.sh).
# Con "en": dump di Wikivoyage EN, <outDir>/<regionId>.cities-en.jsonl (extract-cities-dump-en.py, le
# citta' si trovano risalendo {{isPartOf}} fino al titolo EN della regione, senza pagine IT).
#
# Richiede: curl, python3, gli stessi di lib.sh (nessuna dipendenza in piu').
set -euo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "Uso: $0 <regioni.tsv> <outDir> [it|en]" >&2
  exit 1
fi

REGIONS_TSV="$1"
OUT_DIR="$2"
LANG_CODE="${3:-it}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./lib.sh
source "$SCRIPT_DIR/lib.sh"

mkdir -p "$OUT_DIR"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

if [ "$LANG_CODE" = "en" ]; then
  DUMP="$WORKDIR/dump-en"
  echo "-- cities (en): scarico il dump di Wikivoyage EN..."
  if ! fetch_wikivoyage_dump_parts en "$DUMP"; then
    echo "::warning::cities (en): dump Wikivoyage EN non scaricato/verificato"
    exit 1
  fi
  echo "-- cities (en): estraggo le pagine citta' dal dump..."
  python3 "$SCRIPT_DIR/extract-cities-dump-en.py" "$DUMP" "$REGIONS_TSV" "$OUT_DIR"
  exit 0
fi

echo "-- citta': risolvo la pagina IT Wikivoyage di ogni regione..."
RESOLVED_TSV="$WORKDIR/resolved.tsv"
: > "$RESOLVED_TSV"
while IFS=$'\t' read -r regionId wikiTitle; do
  [ -n "$regionId" ] || continue
  itTitle="$(resolve_it_wikivoyage_title "$wikiTitle")"
  [ -n "$itTitle" ] && printf '%s\t%s\n' "$regionId" "$itTitle" >> "$RESOLVED_TSV"
done < "$REGIONS_TSV"

if [ ! -s "$RESOLVED_TSV" ]; then
  echo "::warning::citta': nessuna pagina IT Wikivoyage risolta per queste regioni"
  exit 1
fi

DUMP="$WORKDIR/dump-it"
echo "-- citta': scarico il dump di Wikivoyage IT..."
if ! fetch_wikivoyage_dump_parts it "$DUMP"; then
  echo "::warning::citta': dump Wikivoyage IT non scaricato/verificato"
  exit 1
fi

echo "-- citta': estraggo le pagine citta' dal dump..."
python3 "$SCRIPT_DIR/extract-cities-dump.py" "$DUMP" "$RESOLVED_TSV" "$OUT_DIR"
