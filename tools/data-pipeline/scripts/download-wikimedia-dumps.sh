#!/usr/bin/env bash
# Scarica i MediaWiki Content File Exports (contenuto attuale, uno al mese il 1°) di Wikivoyage IT/EN e Wikipedia IT/EN
# usati da generate_sft.py e generate_eval_set*.py --dump-dir: 4 wiki, 19 parti, ~47 GB compressi per export.
#
# Uso: download-wikimedia-dumps.sh <cartella> [data]
#   data: MM-AAAA o AAAA-MM (separati da -, / o .), oppure AAAA-MM-GG (normalize_dump_date in lib.sh). Senza data:
#   l'ultimo export completo (con SHA256SUMS) comune alle quattro wiki.
# Le parti finiscono in <cartella>/<AAAA-MM-GG>/, con lo SHA256SUMS di ogni wiki (<wiki>.SHA256SUMS). Una parte gia'
# presente con lo sha256 giusto non si riscarica, una interrotta riprende da dove era arrivata.
# Senza data, solo dopo che l'export nuovo e' completo e verificato, cancella le cartelle degli export piu' vecchi in
# <cartella>, comprese le cache <wiki>-<data>.pages.json di wiki_dump.py. Con una data esplicita non cancella niente.
#
# Richiede: curl, sha256sum, GNU date.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "Uso: $0 <cartella> [MM-AAAA | AAAA-MM | AAAA-MM-GG]" >&2
  exit 1
fi
ROOT="$1"
WIKIS=(itwikivoyage enwikivoyage itwiki enwiki)

if [ -n "${2:-}" ]; then
  DATE="$(normalize_dump_date "$2")"
  PRUNE=""
else
  # l'export comune e' il piu' vecchio tra gli ultimi completi delle quattro wiki: il 1° del mese una wiki grande puo'
  # essere ancora in corso; se a quella data un'altra wiki non e' completa, lo SHA256SUMS qui sotto manca e si esce
  DATE=""
  for wiki in "${WIKIS[@]}"; do
    d="$(latest_wikimedia_export "$wiki")" || { echo "$wiki: nessun export completo" >&2; exit 1; }
    if [ -z "$DATE" ] || [[ "$d" < "$DATE" ]]; then DATE="$d"; fi
  done
  PRUNE=1
fi
DIR="$ROOT/$DATE"
mkdir -p "$DIR"
echo "export del $DATE in $DIR"

for wiki in "${WIKIS[@]}"; do
  url="$WIKIMEDIA_EXPORTS_URL/$wiki/$DATE/xml/bzip2"
  sums="$(wikimedia_curl "$url/SHA256SUMS")" || { echo "$wiki: SHA256SUMS del $DATE non trovato" >&2; exit 1; }
  grep -qE "^[0-9a-f]{64}  $wiki-$DATE-p[0-9]+p[0-9]+[.]xml[.]bz2$" <<< "$sums" \
    || { echo "$wiki: SHA256SUMS del $DATE senza parti" >&2; exit 1; }
  printf '%s\n' "$sums" > "$DIR/$wiki.SHA256SUMS"
  part_re="^$wiki-$DATE-p[0-9]+p[0-9]+[.]xml[.]bz2$"
  while read -r sha file; do
    [ -n "$file" ] || continue
    # i nomi vengono da un file remoto: solo parti di questa wiki e di questa data, mai un percorso
    [[ "$sha" =~ ^[0-9a-f]{64}$ && "$file" =~ $part_re ]] \
      || { echo "$wiki: riga di SHA256SUMS non valida: $file" >&2; exit 1; }
    verified() { printf '%s  %s\n' "$sha" "$DIR/$file" | sha256sum -c - >/dev/null 2>&1; }
    size() { if [ -f "$DIR/$file" ]; then wc -c < "$DIR/$file"; else echo 0; fi; }
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
      verified && break
      before="$(size)"
      echo "$file: download (tentativo $attempt)"
      # -C -: riprende un download interrotto; --max-time per parte, le piu' grandi superano i 3 GB
      wikimedia_curl --max-time 14400 -C - -o "$DIR/$file" "$url/$file" || sleep 30
      # non e' cresciuto e non torna: un file intero con lo sha256 sbagliato non si riprende, si riscarica da capo
      if ! verified && [ "$(size)" = "$before" ]; then rm -f "$DIR/$file"; fi
    done
    verified || { echo "$file: sha256 sbagliato dopo 10 tentativi" >&2; exit 1; }
    echo "$file: ok"
  done <<< "$sums"
done
echo "export del $DATE completo e verificato"

if [ -n "$PRUNE" ]; then
  for old in "$ROOT"/*/; do
    old="${old%/}"
    d="$(basename "$old")"
    # solo le cartelle che crea questo script (nome AAAA-MM-GG e almeno un <wiki>.SHA256SUMS dentro)
    [[ "$d" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] && compgen -G "$old/*.SHA256SUMS" >/dev/null || continue
    if [[ "$d" < "$DATE" ]]; then
      echo "cancello l'export vecchio $old"
      rm -rf "$old"
    fi
  done
fi
