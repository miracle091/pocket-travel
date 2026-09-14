#!/usr/bin/env bash
# Elenco del lotto pilota (piano A2, deciso il 2026-09-14) — sorgente unica condivisa da
# build-pilot-regions.sh (esecuzione locale, tutte le regioni) e dal workflow publish-regions.yml
# (esecuzione selettiva via input workflow_dispatch), per evitare due copie che possono
# disallinearsi.
#
# Gli Stati Uniti sono territorio non contiguo (48 stati + Alaska + Hawaii separati da migliaia
# di km di oceano/Canada/Messico): un unico bbox rettangolare finirebbe per includere anche
# Canada/Messico/oceano aperto, quindi sono pubblicati come 3 region separate — ogni region e'
# gia' un'unita' geografica arbitraria nello schema esistente (RegionManifestEntry), non
# necessariamente una nazione intera, quindi questo non richiede alcuna modifica allo schema.
#
# I bbox sono approssimazioni rettangolari (mapSource supporta solo bbox, non poligoni) —
# includono territorio confinante/mare, coerente con qualunque approccio a bounding-box.
#
# Formato per riga: regionId|displayName|minLon|minLat|maxLon|maxLat|wikivoyagePageTitle
PILOT_REGIONS=(
  "san-marino|San Marino|12.40|43.89|12.52|43.99|San_Marino"
  "italia|Italia|6.60|35.29|18.60|47.10|Italy"
  "giappone|Giappone|122.90|20.30|153.99|45.60|Japan"
  "stati-uniti|Stati Uniti (contigui)|-125.00|24.50|-66.90|49.40|United_States_of_America"
  "stati-uniti-alaska|Stati Uniti - Alaska|-170.00|51.00|-129.90|71.60|Alaska"
  "stati-uniti-hawaii|Stati Uniti - Hawaii|-160.30|18.90|-154.70|22.30|Hawaii"
)
