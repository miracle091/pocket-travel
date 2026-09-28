<p align="center">
  <img src="docs/images/icon.svg" width="120" alt="Icona di Pocket Travel: una tasca con una bussola">
</p>

<h1 align="center">Pocket Travel</h1>

<p align="center">
  Una bussola in tasca per ogni viaggio.<br>
  Guida, mappa, assistente IA e documenti, anche senza internet.
</p>

<p align="center">
  <a href="https://github.com/miracle091/pocket-travel/releases/tag/v0.8.0">Scarica l'app</a> ·
  <a href="CHANGELOG.md">Novità</a> ·
  versione <strong>0.8.0</strong>
</p>

<p align="center">
  <img src="docs/images/regions.png" width="200" alt="Elenco delle nazioni divise per continente">
  <img src="docs/images/guide.png" width="200" alt="Guida di San Marino con i numeri di emergenza, le ambasciate del proprio paese e i fatti rapidi">
  <img src="docs/images/map.png" width="200" alt="Mappa offline di San Marino con i punti di interesse">
  <img src="docs/images/packages.png" width="200" alt="Pacchetti scaricabili di San Marino">
</p>

## Cosa fa

In viaggio la connessione manca proprio quando serve: in aereo, in montagna, all'estero senza roaming. Pocket Travel scarica prima quello che ti serve e poi lo usa offline.

- **Guida** – le informazioni essenziali di ogni paese e delle sue città (cosa vedere, dove mangiare, come muoversi, soldi, sicurezza), i fatti rapidi (lingua, prese, fuso orario) e i numeri di emergenza da chiamare con un tocco.
- **Mappa** – mappa dettagliata con ristoranti, alloggi, farmacie, bancomat e molto altro. Scegli come ti sposti e l'app ti mostra i punti che ti servono.
- **Assistente IA** – fai una domanda sul posto in cui sei ("dove si mangia bene?", "come pago il bus?") e ti risponde usando la guida. Funziona anche offline, con un piccolo modello di intelligenza artificiale scaricato sul telefono.
- **Documenti** – una copia del passaporto e dei biglietti, cifrata e sbloccabile solo con la tua impronta o il tuo volto, e le tue note di viaggio (prenotazioni, indirizzi), cifrate e usate dall'assistente IA quando servono.

Scegli tu cosa scaricare: per ogni paese mappa, punti di interesse e numeri civici sono pacchetti separati, così non occupi spazio per quello che non usi. Le guide di tutto il mondo pesano pochi megabyte. Il calcolo dei percorsi a piedi, in bici e in auto è in arrivo.

Ci sono 355 paesi e regioni (gli stati più grandi, come Stati Uniti, Canada e Cina, sono divisi in parti), aggiornati ogni settimana.

## Da dove vengono i dati

Tutto viene da progetti aperti e liberi: le guide da [Wikivoyage](https://it.wikivoyage.org) e [Wikipedia](https://it.wikipedia.org), le mappe e i punti di interesse da [OpenStreetMap](https://www.openstreetmap.org) (le mappe nel formato di [Protomaps](https://protomaps.com)), i numeri civici da OpenStreetMap e dai registri ufficiali degli indirizzi raccolti da [Overture Maps](https://overturemaps.org), i percorsi da [BRouter](https://brouter.de). L'app non ha un server suo e non raccoglie dati su di te: la chiave dell'assistente IA online, se la usi, resta solo sul telefono.

## Per chi sviluppa

Per iniziare c'è una [guida rapida](docs/GUIDA.md); il perché delle scelte tecniche è spiegato nella [wiki](https://github.com/miracle091/pocket-travel/wiki).

L'app è scritta in Kotlin con Jetpack Compose. Il codice è diviso in moduli:

| Cartella | Cosa contiene |
|---|---|
| `app/` | schermate principali e navigazione |
| `core/` | database, download e installazione dei pacchetti, tema grafico, categorie dei punti di interesse |
| `feature/` | guida, mappa, assistente IA, documenti, fonti ufficiali (ognuna dipende solo da `core`) |
| `tools/data-pipeline/` | gli script che preparano e pubblicano a turno, ogni notte, guide, mappe, punti di interesse e numeri civici, e quelli per addestrare i modelli dell'assistente IA |
| `third-party/` | copie di BRouter e llama.cpp usate dall'app |

Per aprire il progetto serve Android Studio con JDK 17 o successivo: apri la cartella e Android Studio fa il resto (Gradle è già incluso).

Per provare l'app con un catalogo di dati tuo invece di quello pubblicato (solo nelle build di debug):

```bash
./gradlew :app:installDebug -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json
```

`10.0.2.2` è il tuo PC visto dall'emulatore.

## Licenza

Il codice è sotto licenza [MIT](LICENSE). I dati hanno le loro licenze ([CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/deed.it) per Wikivoyage e Wikipedia, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) per OpenStreetMap; i civici di Overture Maps hanno la licenza del registro da cui vengono): l'elenco completo, con tutte le fonti dei civici, è nella schermata Licenze dell'app.
