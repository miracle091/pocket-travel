<p align="center">
  <img src="docs/images/icon.svg" width="120" alt="Icona di Pocket Travel: una tasca con una bussola">
</p>

<h1 align="center">Pocket Travel</h1>

<p align="center">
  Una bussola in tasca per ogni viaggio.<br>
  Guida, mappa, assistente IA e documenti, anche senza internet.
</p>

<p align="center">
  <a href="https://github.com/miracle091/pocket-travel/releases/tag/v0.9.0">Scarica l'app</a> ·
  <a href="CHANGELOG.md">Novità</a> ·
  versione <strong>0.9.0</strong>
</p>

<p align="center">
  <img src="docs/images/regions.png" width="200" alt="Nazioni scaricate e le altre divise per continente, con la ricerca in cima e il pulsante per la mappa del mondo">
  <img src="docs/images/guide.png" width="200" alt="Guida di San Marino con i numeri di emergenza, le ambasciate del proprio paese e i fatti rapidi">
  <img src="docs/images/map.png" width="200" alt="Mappa offline di San Marino con i punti di interesse e il pulsante dei filtri">
  <img src="docs/images/filters.png" width="200" alt="Filtri della mappa divisi in gruppi, ognuno con i suoi interruttori">
  <img src="docs/images/navigator.png" width="200" alt="Navigatore senza meta: la propria posizione e i punti di interesse vicini, con la ricerca Dove vuoi andare?">
  <img src="docs/images/route.png" width="200" alt="Navigatore con il percorso a piedi fino a un bar di San Marino: tempo, distanza e il pulsante per partire">
  <img src="docs/images/missing-routes.png" width="200" alt="Navigatore da San Marino alla Lettonia: l'elenco delle nazioni di cui mancano i percorsi, in ordine lungo la strada, con il peso di ciascuna e il pulsante per scaricarle tutte">
  <img src="docs/images/notes.png" width="200" alt="Documenti con il selettore Documenti e Note, sulle note di viaggio">
  <img src="docs/images/ai.png" width="200" alt="Assistente IA online: scelta del servizio (ChatGPT, Mistral AI, Gemini, Claude) e del modello">
  <img src="docs/images/packages.png" width="200" alt="Contenuti scaricabili per San Marino">
</p>

## Cosa fa

In viaggio la connessione manca proprio quando serve: in aereo, in montagna, all'estero senza roaming. Pocket Travel scarica prima quello che ti serve e poi lo usa offline.

- **Guida** – le informazioni essenziali di ogni paese e delle sue città (cosa vedere, dove mangiare, come muoversi, soldi, sicurezza), i fatti rapidi (lingua, prese, fuso orario, valuta, trasporti), i numeri di emergenza da chiamare con un tocco e le ambasciate e i consolati del tuo paese (da OpenStreetMap e Wikidata), con in cima quelli vicini a te.
- **Mappa** – mappa dettagliata con ristoranti, hotel, farmacie, bancomat e molto altro, con orari, indirizzo e contatti quando ci sono. Scegli come ti sposti e l'app ti mostra i punti che ti servono; i filtri sono divisi in gruppi.
- **Navigatore** – dalla barra principale o dalla scheda di una nazione: cerchi la meta per nome o per indirizzo ("Via Roma 12") in tutte le nazioni scaricate, vedi percorso, tempo e svolte e parti: la guida passo passo resta nella scheda, continua a schermo spento con una notifica fissa e si vede anche sopra la schermata di blocco. Puoi dire a che ora vuoi arrivare e farti avvisare quando partire.
- **Mezzi pubblici** – nella scheda di una fermata, di una stazione o di un porto le prossime partenze delle tre ore successive, offline, per le reti con dati aperti (per ora in Lettonia, Italia, Svezia, Spagna, Francia, Svizzera e Regno Unito). Dove una nazione ne ha molte scegli tu quali scaricare; di default quelle vicine a te.
- **Assistente IA** – chiedi in parole tue quello che ti serve sapere sul paese o sulla città in cui sei ("dove si mangia bene?", "come pago il bus?") e ti risponde in poche frasi, basandosi sulla guida scaricata e sulle tue note di viaggio, e dicendoti quando la guida non basta. Funziona anche senza internet (vedi [più sotto](#assistente-ia)).
- **Fonti ufficiali** – i siti del ministero degli esteri del tuo paese e quelli internazionali (OMS, CDC), per controllare documenti, sicurezza e salute prima di partire.
- **Documenti** – una copia del passaporto e dei biglietti, cifrata e sbloccabile solo con la tua impronta o il tuo volto, e accanto, con il selettore Documenti | Note, le tue note di viaggio (prenotazioni, indirizzi), cifrate e usate dall'assistente IA quando servono.

L'app è in **italiano e in inglese**: interfaccia, guide delle nazioni e delle città e assistente IA. La lingua si sceglie al primo avvio e si cambia in **Altro → Impostazioni**, insieme a nazionalità, aspetto, modo di spostarsi, opzioni del Navigatore e assistente IA.

Scegli tu cosa scaricare, anche già al primo avvio: per ogni paese mappa, punti di interesse, numeri civici, percorsi e orari dei mezzi pubblici sono contenuti separati, così non occupi spazio per quello che non usi. Le guide di tutto il mondo pesano pochi megabyte. Con i Percorsi il Navigatore ti guida fino a un punto della mappa o a un indirizzo, svolta dopo svolta, con il GPS acceso, anche da un paese all'altro se hai scaricato i percorsi dei paesi lungo la strada (se mancano, il Navigatore dice quali e li scarica insieme); autovelox, limiti di velocità e zone a traffico limitato o a basse emissioni non sono segnalati.

Ci sono 355 paesi e regioni (gli stati più grandi, come Stati Uniti, Canada e Cina, sono divisi in parti), aggiornati ogni settimana.

## Assistente IA

L'assistente non inventa: cerca nella guida del paese, in quelle delle sue città e nelle tue note, e risponde solo con quello che trova, in massimo tre frasi. Quando la guida non ha la risposta te lo dice; per dogane e salute aggiunge il link alla fonte ufficiale. Si usa in due modi, che scegli in **Altro → Impostazioni → Assistente IA**. La scheda **IA** di ogni paese compare quando l'assistente è pronto, cioè con un modello scaricato o una chiave API salvata.

### Sul dispositivo (offline)

Un piccolo modello di intelligenza artificiale gira direttamente sul telefono: nessuna connessione, nessun dato che esce dal telefono. Serve un telefono con almeno 4 GB di RAM; l'app propone il modello adatto alla sua memoria.

| RAM del telefono | Addestrato da noi (proposto in italiano) | Ufficiale (proposto in inglese) |
|---|---|---|
| 4 GB | [Pocket Travel 0.8B (Qwen3.5)](tools/data-pipeline/model-cards/qwen3.5-0.8b-travel-it-GGUF.md) – 0,53 GB | [Qwen3.5 0.8B](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF) – 0,56 GB |
| 8 GB | [Pocket Travel 2B (Qwen3.5)](tools/data-pipeline/model-cards/qwen3.5-2b-travel-it-GGUF.md) – 1,27 GB | [Qwen3.5 2B](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF) – 1,34 GB |
| 12 GB o più | [Pocket Travel 4B (Qwen3)](tools/data-pipeline/model-cards/qwen3-4b-instruct-2507-travel-it-GGUF.md) – 2,50 GB | [Qwen3 4B Instruct 2507](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF) – 2,55 GB |

I modelli "Pocket Travel" sono addestrati da noi sulle guide di viaggio e rispondono in italiano: ogni nome porta alla sua scheda nel repository (come è stato addestrato, risultati, licenza), pubblicata anche su [huggingface.co/pockettravel](https://huggingface.co/pockettravel). Gli "Ufficiali" sono gli stessi modelli Qwen come li pubblicano i loro autori (qui nella versione quantizzata di Unsloth). Un telefono vede anche i modelli delle fasce più basse. Con l'app in inglese la lista mostra solo i modelli che rispondono in inglese: per ora quelli "Ufficiali", in attesa dei nostri modelli in inglese.

Per iniziare: in **Impostazioni → Assistente IA** scegli **Sul dispositivo**, tocca il modello e poi **Scarica modello** (meglio col Wi-Fi). Nella scheda **IA**, dal menu ⋮, puoi misurare la velocità del modello sul tuo telefono o eliminarlo.

### Online (con la tua chiave API)

Se il telefono ha poca memoria, o vuoi un modello più grande, l'assistente può usare un servizio di intelligenza artificiale a tua scelta, con una tua chiave API personale. In questa modalità la domanda va al servizio **così com'è, senza la guida scaricata**: le risposte sono più generali, e per dogane e salute conviene sempre controllare la fonte ufficiale.

La chiave resta solo sul telefono, cifrata, e le domande vanno direttamente da te al servizio: non passano da nessun server nostro. L'uso lo paghi tu, sul tuo account del servizio (alcuni hanno una quota gratuita). Una chiave API è come una password: non condividerla, e dove il servizio lo permette imposta un limite di spesa.

| Servizio | Automatico (oggi) | Più capace | Dove si crea la chiave | Com'è fatta la chiave |
|---|---|---|---|---|
| ChatGPT (OpenAI) | `gpt-4o-mini` | `gpt-5.6-terra` | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) | inizia con `sk-` |
| Mistral AI | `mistral-small-latest` | `mistral-large-latest` | [console.mistral.ai/api-keys](https://console.mistral.ai/api-keys) | lettere e cifre, senza un prefisso fisso |
| Gemini (Google) | `gemini-flash-latest` | `gemini-2.5-pro` | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | inizia con `AIza` |
| Claude (Anthropic) | `claude-haiku-4-5` | `claude-sonnet-4-6` | [console.anthropic.com/settings/keys](https://console.anthropic.com/settings/keys) | inizia con `sk-ant-` |

In tutti i casi, nell'app: in **Impostazioni → Assistente IA** scegli **Online**, scegli il **Servizio**, incolla la chiave in **Chiave API** (l'icona a forma di occhio la mostra, per controllarla) e tocca **Salva chiave**. Il pulsante **Crea la chiave su …** apre direttamente la pagina giusta del servizio scelto. Cambiando servizio la chiave salvata si cancella, perché è valida solo per il suo servizio. Per toglierla: **Rimuovi chiave** nelle Impostazioni, o menu ⋮ → **Rimuovi chiave** nella scheda IA. Se il servizio rifiuta la chiave (scaduta o revocata) l'assistente lo dice e propone di toglierla.

**Esempio con ChatGPT.** Su [platform.openai.com](https://platform.openai.com) crea un account e aggiungi del credito in **Billing**; in **API keys** tocca **Create new secret key** e copia la chiave (OpenAI la mostra una volta sola: qualcosa come `sk-proj-AbCd…`). Nell'app scegli **ChatGPT (OpenAI)** e incollala.

**Esempio con Mistral AI.** Su [console.mistral.ai](https://console.mistral.ai) accedi, scegli un piano (c'è anche quello gratuito per provare) e in **API keys** crea una nuova chiave. Nell'app scegli **Mistral AI** e incollala. Una chiave appena creata può impiegare qualche minuto prima di funzionare.

**Esempio con Gemini.** Su [aistudio.google.com](https://aistudio.google.com) accedi con un account Google e tocca **Get API key** → **Create API key**: la chiave (`AIza…`) si copia subito. Nell'app scegli **Gemini (Google)** e incollala. Il piano gratuito di Gemini ha limiti di richieste al minuto e al giorno.

**Esempio con Claude.** Su [console.anthropic.com](https://console.anthropic.com) crea un account e aggiungi del credito in **Billing**; in **API keys** tocca **Create Key** e copia la chiave (`sk-ant-…`, mostrata una volta sola). Nell'app scegli **Claude (Anthropic)** e incollala.

Poi fai una domanda, per esempio "Serve il visto per entrare?" o "Come si paga il bus in città?". Le chiavi negli esempi sono finte: la tua sarà diversa. Tutti e quattro si usano con la stessa interfaccia, compatibile con quella di OpenAI.

**Il modello.** Sotto **Modello** ci sono tre scelte, ricordate per ciascun servizio:

- **Automatico (consigliato)**: un modello veloce ed economico, che basta per le risposte brevi di un viaggio. Per Mistral e Gemini è un nome che il servizio stesso aggiorna al modello più recente (`mistral-small-latest`, `gemini-flash-latest`); per ChatGPT e Claude, che non ne hanno uno, è quello indicato in tabella, aggiornato con le nuove versioni dell'app.
- **Più capace**: risposte più accurate alle domande complesse, ma costa di più e può essere più lento.
- **Altro modello**: scrivi a mano il nome esatto di un altro modello del servizio (per esempio `gpt-4.1` o `gemini-2.5-flash-lite`). Se il servizio non lo conosce, l'assistente lo dice; svuotando il campo si torna all'automatico.

Il modello in uso si legge sotto il nome di ogni servizio ("Modello: …").

## Da dove vengono i dati

Tutto viene da progetti aperti e liberi: le guide da [Wikivoyage](https://it.wikivoyage.org) (in italiano e, per l'app in inglese, in [inglese](https://en.wikivoyage.org)) e [Wikipedia](https://it.wikipedia.org), le mappe e i punti di interesse da [OpenStreetMap](https://www.openstreetmap.org) (le mappe nel formato di [Protomaps](https://protomaps.com)), i numeri civici da OpenStreetMap e dai registri ufficiali degli indirizzi raccolti da [Overture Maps](https://overturemaps.org), i percorsi da [BRouter](https://brouter.de), le ambasciate e i consolati anche da [Wikidata](https://www.wikidata.org). L'app non ha un server suo e non raccoglie dati su di te: la posizione, chiesta solo per il Navigatore, resta sul telefono e si usa solo lì: per la guida (anche a schermo spento, sempre con una notifica fissa che lo mostra), per i percorsi che partono da dove sei e, con il Navigatore aperto senza meta, per mostrarti i posti vicini; la chiave dell'assistente IA online, se la usi, resta solo sul telefono. Il catalogo dei dati è firmato: l'app controlla la firma con una chiave pubblica incorporata e rifiuta i file non firmati.

## Per chi sviluppa

Per iniziare c'è una [guida rapida](docs/GUIDA.md); il perché delle scelte tecniche è spiegato nella [wiki](https://github.com/miracle091/pocket-travel/wiki).

L'app è scritta in Kotlin con Jetpack Compose. Il codice è diviso in moduli:

| Cartella | Cosa contiene |
|---|---|
| `app/` | schermate principali (Nazioni, Navigatore, Altro, Impostazioni) e navigazione tra le schermate |
| `core/` | database, download e installazione dei pacchetti, tema grafico, categorie dei punti di interesse |
| `feature/` | guida, mappa e Navigatore, assistente IA, documenti e note, fonti ufficiali (ognuna dipende solo da `core`) |
| `tools/data-pipeline/` | gli script che preparano e pubblicano a turno, ogni notte, guide, mappe, punti di interesse e numeri civici, e quelli per addestrare i modelli dell'assistente IA |
| `tools/dev/` | strumenti per lo sviluppo, come la simulazione del GPS lungo un percorso sull'emulatore |
| `third-party/` | copie di BRouter e llama.cpp usate dall'app |

Per aprire il progetto serve Android Studio con JDK 17 o successivo: apri la cartella e Android Studio fa il resto (Gradle è già incluso).

Per provare l'app con un catalogo di dati tuo invece di quello pubblicato (solo nelle build di debug):

```bash
./gradlew :app:installDebug -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json
```

`10.0.2.2` è il tuo PC visto dall'emulatore. Con un catalogo tuo l'app non controlla le firme (vedi sotto).

Per provare la guida del Navigatore senza muoverti: in una build di debug apri il Navigatore, scegli la meta e tocca Avvia, poi lancia `node tools/dev/simulate-route.js 12`. Lo script legge il percorso dal log dell'app e muove il GPS dell'emulatore lungo il percorso, a 12 m/s (il numero è la velocità).

Per pubblicare i dati da un tuo fork: i file JSON che l'app scarica (`manifest.json`, `transit.json`, `address-grid.json`, `app-status.json`) sono firmati con ECDSA P-256 nei workflow, con la chiave privata del secret `MANIFEST_SIGNING_KEY`; senza il secret i workflow si fermano invece di pubblicare file non firmati. Come creare la chiave e incorporare la chiave pubblica nell'app è spiegato in [tools/data-pipeline/README.md](tools/data-pipeline/README.md). L'ordine conta: prima una pubblicazione delle regioni con le firme (`publish-regions.yml`), poi la versione dell'app che le richiede, altrimenti l'app rifiuta il catalogo. I numeri civici stanno su 12 release (`address-cells-0` … `address-cells-11`), per restare sotto il limite di 1000 file per release di GitHub.

## Licenza

Il codice è sotto licenza [MIT](LICENSE). I dati hanno le loro licenze ([CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/deed.it) per Wikivoyage e Wikipedia, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) per OpenStreetMap, [CC0](https://creativecommons.org/publicdomain/zero/1.0/deed.it) per Wikidata; i civici di Overture Maps hanno la licenza del registro da cui vengono): l'elenco completo, con tutte le fonti dei civici, è nella schermata Licenze dell'app.
