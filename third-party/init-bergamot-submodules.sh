#!/bin/bash
# Scarica i sottomoduli annidati di bergamot-translator che il build di :core:translate compila (gli altri, come
# fbgemm, nccl e i test, non servono e pesano centinaia di MB). Si lancia dopo "git submodule update --init"
# e in CI dopo il checkout. Il path lungo di alcuni file richiede core.longpaths su Windows.
set -euo pipefail
cd "$(dirname "$0")/bergamot-translator"
git -c core.longpaths=true submodule update --init --depth 1 3rd_party/marian-dev 3rd_party/ssplit-cpp
cd 3rd_party/marian-dev
git -c core.longpaths=true submodule update --init --depth 1 \
  src/3rd_party/sentencepiece src/3rd_party/intgemm src/3rd_party/ruy src/3rd_party/simd_utils src/3rd_party/onnxjs
git -c core.longpaths=true -C src/3rd_party/ruy submodule update --init --depth 1 third_party/cpuinfo
git -c core.longpaths=true -C src/3rd_party/onnxjs submodule update --init --depth 1 deps/eigen
