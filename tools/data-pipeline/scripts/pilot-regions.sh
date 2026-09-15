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
# flagCode e' il codice ISO 3166-1 alpha-2 in minuscolo della nazione (le 3 region statunitensi
# condividono lo stesso "us", non sono nazioni a se' - vedi nota sopra) - usato nella pagina
# indice statica del sito pubblicato (assemble-site.sh) per risolvere il file
# scripts/assets/flags/<flagCode>.svg (bandiera vettoriale, non emoji: gli emoji bandiera non si
# vedono su Windows, il font di sistema non li renderizza).
#
# groupName/groupLabel (opzionali, vuoti per le nazioni normali) servono solo alla pagina indice
# statica (assemble-site.sh): quando piu' region condividono lo stesso groupName (qui le 3 region
# statunitensi non contigue, vedi nota sopra), la pagina le raccoglie sotto un'unica voce
# "groupName" con groupLabel come sotto-voce, invece di 3 righe separate ciascuna con "Stati
# Uniti" ripetuto nel nome — non tocca ne' regionId ne' displayName (quello resta il nome mostrato
# nell'app, RegionListScreen, e non deve cambiare per un dettaglio di questa sola pagina).
#
# continent raggruppa ulteriormente la pagina indice sotto un'intestazione per continente - le
# righe vanno tenute ordinate per continente (qui gia' cosi': Europa, poi Asia, poi Nord America)
# perche' assemble-site.sh apre/chiude ogni sezione con un solo passaggio, senza riordinare.
# Hawaii e' geograficamente nel Pacifico ma resta "Nord America" qui: e' la stessa voce/gruppo
# "Stati Uniti d'America" delle altre 2 region USA, non ha senso spezzare un gruppo a meta' tra
# due continenti.
#
# Formato per riga:
#   regionId|displayName|minLon|minLat|maxLon|maxLat|wikivoyagePageTitle|flagCode|groupName|groupLabel|continent
PILOT_REGIONS=(
  "san-marino|San Marino|12.40|43.89|12.52|43.99|San_Marino|sm|||Europa"
  "italia|Italia|6.60|35.29|18.60|47.10|Italy|it|||Europa"
  "giappone|Giappone|122.90|20.30|153.99|45.60|Japan|jp|||Asia"
  "stati-uniti|Stati Uniti (contigui)|-125.00|24.50|-66.90|49.40|United_States_of_America|us|Stati Uniti d'America|Contigui (48 stati)|Nord America"
  "stati-uniti-alaska|Stati Uniti - Alaska|-170.00|51.00|-129.90|71.60|Alaska|us|Stati Uniti d'America|Alaska|Nord America"
  "stati-uniti-hawaii|Stati Uniti - Hawaii|-160.30|18.90|-154.70|22.30|Hawaii|us|Stati Uniti d'America|Hawaii|Nord America"
)
