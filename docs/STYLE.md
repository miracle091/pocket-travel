# Stile dei testi

Regole per tutti i testi di Pocket Travel: interfaccia dell'app (italiano e inglese), README, CHANGELOG, `docs/`,
wiki, model card e commenti nel codice. L'obiettivo e' un testo **chiaro e formale**: preciso, completo, senza
toni colloquiali.

## Regole comuni

- Frasi complete e brevi, una informazione per frase; il soggetto e il verbo all'inizio.
- Un solo termine per ogni concetto, sempre lo stesso (vedi "Terminologia"); niente sinonimi per variare.
- Nessuna espressione colloquiale, esclamazione, emoji, ironia o abbreviazione informale ("ok", "tipo", "roba",
  "ecc." nei testi dell'interfaccia).
- Numeri, unita' e date nel formato della lingua: in italiano "2,8 milioni", "4–6 settimane", "3 ott 2026"; in
  inglese "2.8 million", "4 to 6 weeks", "Oct 3, 2026".
- Nessun punto finale nei testi brevi dell'interfaccia di una sola frase (etichette, pulsanti, titoli, descrizioni
  sotto un titolo, avvisi di una riga); il punto resta tra le frasi di un testo che ne ha piu' d'una.
- Maiuscola solo all'inizio della frase e nei nomi propri (sentence case, anche in inglese): "Fonti ufficiali",
  "Official sources", non "Official Sources".

## Interfaccia in italiano

- Pulsanti e istruzioni brevi all'imperativo di seconda persona singolare, come le app di sistema Android in
  italiano: "Scarica", "Scegli il paese", "Apri la guida".
- Etichette, titoli e stati come sostantivi o forme impersonali: "Paese di partenza", "Download in corso",
  "Nessun certificato richiesto nei nostri dati".
- Spiegazioni e avvisi in forma impersonale, senza rivolgersi direttamente a chi legge quando non serve:
  "La guida si aggiorna da sola" invece di "Non devi aggiornare la guida".
- Apostrofi e accenti corretti ("perché", "è", "qual è"); in `strings.xml` l'apostrofo si scrive `\'`.

## Interfaccia in inglese

- Stesso contenuto e stesso registro dell'italiano, non una traduzione parola per parola.
- Pulsanti all'imperativo ("Download", "Choose the country"), etichette come sostantivi.
- Inglese britannico o americano: americano ("color", "center"), in modo uniforme.

## Documentazione (README, CHANGELOG, docs, wiki, model card)

- Registro formale e impersonale: "Si lancia con", "Il comando genera"; seconda persona solo nelle istruzioni
  passo per passo.
- Prima il risultato o la regola, poi il perche'; niente racconto cronologico di come ci si e' arrivati.
- Nomi di file, comandi e codici tra backtick; collegamenti con testo descrittivo, mai "qui".

## Commenti nel codice

- In italiano, come il resto del codice, al presente e in forma impersonale: cosa fa il codice e perche', non
  la storia delle modifiche ("prima faceva...") se non serve a capire una scelta ancora valida.
- Brevi: una o due righe vicino al codice che spiegano; KDoc/docstring per le funzioni pubbliche.
- Nessun riferimento a file che chi legge il repository non puo' aprire (piani privati, percorsi locali).

## Testi da non modificare

Alcuni testi sono anche il formato su cui sono addestrati i modelli dell'assistente: cambiarli peggiorerebbe le
risposte finche' i modelli non vengono riaddestrati. Restano come sono, salvo errori veri:

- i prompt in `PromptTemplates.kt` e `OnlinePromptTemplates.kt`;
- le etichette del contesto in `TravelAssistant.kt` ("Nota personale", "Personal note");
- il riassunto delle vaccinazioni in `VaccinationSummary.kt`;
- le etichette dei fatti rapidi generate dalla pipeline (`GenerateGuideContent.kt`, `GenerateEmergencyNumbers.kt`)
  e i testi dei dataset in `tools/data-pipeline/scripts/`.

## Terminologia

| Concetto | Italiano | Inglese |
|---|---|---|
| Paese o territorio scaricabile | nazione (elenco), regione (pacchetto) | country, region |
| Testo di Wikivoyage di un paese | guida | guide |
| Testo di Wikivoyage di una citta' | guida della citta' | city guide |
| Riquadro con un contenuto | scheda | card |
| Assistente con il modello locale | assistente | assistant |
| Modello scaricabile | modello | model |
| Calcolo del percorso | percorso | route |
| Pacchetto dei percorsi | percorsi | routing |
