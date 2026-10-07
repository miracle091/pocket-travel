#!/usr/bin/env bash
# Funzioni per i passi dei workflow che dipendono dalla rete (API e release di GitHub): un errore
# passeggero (timeout, 5xx, "release not found" su una release che esiste) non deve far fallire il job.
# Uso: source .github/scripts/retry.sh, poi retry [-n tentativi] comando argomenti... (anche una
# funzione bash) o ensure_release <tag> <titolo> <note>.

# Riprova il comando fino a <tentativi> volte (default 3), con attesa crescente (20s, 40s...).
retry() {
  local attempts=3 attempt
  if [ "$1" = "-n" ]; then
    attempts="$2"
    shift 2
  fi
  for attempt in $(seq 1 "$attempts"); do
    "$@" && return 0
    [ "$attempt" -lt "$attempts" ] || break
    echo "::warning::$1: tentativo $attempt/$attempts fallito, riprovo tra $((attempt * 20))s" >&2
    sleep $((attempt * 20))
  done
  return 1
}

# Crea la release <tag> se non c'e'. Tutto dentro retry: con un errore passeggero di "gh release view"
# si tenterebbe di creare una release che esiste, e il create fallirebbe; al tentativo dopo la view riesce.
# --latest=false: sono release di dati (percorsi, guide, app-status...), il badge "Latest" spetta solo
# all'ultima versione dell'app (publish-apk.yml); senza, GitHub lo sposta su ogni release appena creata.
ensure_release() {
  local tag="$1" title="$2" notes="$3"
  # shellcheck disable=SC2329 # chiamata da retry, qui sotto
  _ensure_release_once() {
    gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1 ||
      gh release create "$tag" --repo "$GITHUB_REPOSITORY" --title "$title" --notes "$notes" --latest=false
  }
  retry _ensure_release_once
}
