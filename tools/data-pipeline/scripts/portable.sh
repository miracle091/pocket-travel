#!/usr/bin/env bash
# Differenze di Git Bash su Windows rispetto a Linux, per gli script della pipeline e dei workflow (lo carica lib.sh):
# - python3 non esiste (l'installer di Windows mette solo python) o e' l'alias del Microsoft Store, che non esegue
#   nulla: python3 diventa python;
# - jq scrive le righe con \r\n, e "$(jq -r ...)" terrebbe un \r finale: jq -b scrive solo \n.
# Le funzioni sono esportate, quindi valgono anche negli script lanciati da questi. Su Linux non fa nulla.
# Uso: source tools/data-pipeline/scripts/portable.sh
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    if ! python3 -c '' >/dev/null 2>&1 && command -v python >/dev/null 2>&1; then
      python3() { python "$@"; }
      export -f python3
    fi
    jq() { command jq -b "$@"; }
    export -f jq
    ;;
esac
