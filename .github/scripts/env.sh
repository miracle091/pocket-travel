#!/usr/bin/env bash
# Ambiente comune degli script dei workflow (.github/scripts), sia in GitHub Actions sia su un PC (Linux o Git Bash su
# Windows).
# Fuori da GitHub Actions (GITHUB_ACTIONS diverso da "true"):
# - le variabili del runner hanno un valore di prova se mancano: RUNNER_TEMP (cartella temporanea nuova),
#   GITHUB_OUTPUT e GITHUB_STEP_SUMMARY (file in RUNNER_TEMP), GITHUB_REPOSITORY (dal remote "origin"),
#   GITHUB_REPOSITORY_OWNER, GITHUB_RUN_NUMBER (0), GITHUB_RUN_ATTEMPT (1), PAGES_BASE_URL (il sito su Pages);
# - si eseguono solo i comandi gh in sola lettura (release view/list/download, cache list, run list/view, repo view,
#   auth status, api senza corpo e senza un metodo diverso da GET); tutti gli altri sono solo stampati, cosi' una prova
#   non tocca i dati pubblicati. PUBLISH=1 li esegue davvero. Le richieste a Pages restano vere.
# I processi lanciati da Python (cache_cleanup.py) non vedono questa funzione: quello script ha il suo --dry-run.
# Uso, in testa a ogni script, dalla root del repository: source .github/scripts/env.sh
# shellcheck source=tools/data-pipeline/scripts/portable.sh
source tools/data-pipeline/scripts/portable.sh

if [ "${GITHUB_ACTIONS:-}" != "true" ]; then
  if [ -z "${GITHUB_REPOSITORY:-}" ]; then
    GITHUB_REPOSITORY="$(git config --get remote.origin.url 2>/dev/null | sed -E 's#^.*github\.com[:/]##; s#\.git$##' || true)"
  fi
  : "${GITHUB_REPOSITORY:?remote origin non leggibile: impostare GITHUB_REPOSITORY=<owner>/<repo>}"
  : "${RUNNER_TEMP:=$(mktemp -d)}"
  : "${GITHUB_OUTPUT:=$RUNNER_TEMP/github-output.txt}"
  : "${GITHUB_STEP_SUMMARY:=$RUNNER_TEMP/step-summary.md}"
  : "${GITHUB_REPOSITORY_OWNER:=${GITHUB_REPOSITORY%%/*}}"
  : "${GITHUB_RUN_NUMBER:=0}"
  : "${GITHUB_RUN_ATTEMPT:=1}"
  : "${PAGES_BASE_URL:=https://${GITHUB_REPOSITORY_OWNER}.github.io/${GITHUB_REPOSITORY#*/}}"
  export GITHUB_REPOSITORY RUNNER_TEMP GITHUB_OUTPUT GITHUB_STEP_SUMMARY GITHUB_REPOSITORY_OWNER GITHUB_RUN_NUMBER \
    GITHUB_RUN_ATTEMPT PAGES_BASE_URL
  echo "-- prova locale ($GITHUB_REPOSITORY): file in $RUNNER_TEMP" >&2

  if [ "${PUBLISH:-}" != "1" ]; then
    echo "-- i comandi gh che scrivono sul repository sono solo stampati (PUBLISH=1 per eseguirli)" >&2
    gh() {
      local reads=false arg prev="" method="" body=false
      case "${1:-} ${2:-}" in
        "release view" | "release list" | "release download" | "cache list" | "run list" | "run view" \
          | "repo view" | "auth status") reads=true ;;
        "api "*)
          # gh api scrive con -X/--method diverso da GET, o con un corpo (-f, -F, --field, --raw-field, --input) senza
          # -X GET.
          for arg in "${@:2}"; do
            case "$prev" in -X | --method) method="${arg^^}" ;; esac
            case "$arg" in
              --method=*) method="${arg#--method=}"; method="${method^^}" ;;
              -X?*) method="${arg#-X}"; method="${method^^}" ;;
              -f | -F | -f?* | -F?* | --field | --field=* | --raw-field | --raw-field=* | --input | --input=*) body=true ;;
            esac
            prev="$arg"
          done
          if [ "$method" = "GET" ] || { [ -z "$method" ] && [ "$body" = false ]; }; then reads=true; fi
          ;;
      esac
      if [ "$reads" != true ]; then
        echo "-- prova locale, non eseguito: gh $*" >&2
        return 0
      fi
      command gh "$@"
    }
    export -f gh
  fi
fi
