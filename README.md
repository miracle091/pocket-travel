<div align="center">
  <img src="docs/images/icon.svg" width="128" alt="Icona di Pocket Travel: una tasca con una bussola" />
  <h1>Pocket Travel</h1>
  <p><strong>Una bussola in tasca per ogni viaggio.</strong></p>
  <p>Guida, mappa, Navigatore, assistente IA e documenti, anche senza internet. Nessun server, nessun account, nessun dato raccolto.</p>
  <p>
    <a href="README.md"><strong>Italiano</strong></a> | <a href="README.en.md">English</a>
  </p>
  <p>
    <a href="https://github.com/miracle091/pocket-travel/releases/latest"><img src="https://img.shields.io/github/v/tag/miracle091/pocket-travel?filter=v*&sort=semver&style=flat-square&logo=github&label=versione" alt="Versione" /></a>
    <a href="https://github.com/miracle091/pocket-travel/actions/workflows/android-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/miracle091/pocket-travel/android-ci.yml?branch=main&style=flat-square&logo=github-actions&label=build" alt="Stato della build" /></a>
    <a href="#installazione"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0 o successivo" /></a>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/kotlin-2.4-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" /></a>
    <a href="https://developer.android.com/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/licenza-MIT-blue?style=flat-square" alt="Licenza MIT" /></a>
    <a href="CONTRIBUTING.md"><img src="https://img.shields.io/badge/contributi-benvenuti-brightgreen?style=flat-square" alt="Contributi benvenuti" /></a>
  </p>
  <p>
    <img src="docs/images/guide.png" width="200" alt="Guida di San Marino con i numeri di emergenza, le ambasciate del proprio paese e i fatti rapidi" />
    <img src="docs/images/map.png" width="200" alt="Mappa offline di San Marino con i punti di interesse e il pulsante dei filtri" />
    <img src="docs/images/route.png" width="200" alt="Navigatore con il percorso a piedi fino a un bar di San Marino: tempo, distanza e il pulsante per partire" />
  </p>
</div>

---

## Indice

- [Installazione](#installazione)
- [Funzioni](#funzioni)
- [Perché Pocket Travel](#perché-pocket-travel)
- [Assistente IA](#assistente-ia)
- [Schermate](#schermate)
- [Dati e privacy](#dati-e-privacy)
- [Aiuto e contributi](#aiuto-e-contributi)
- [Per chi sviluppa](#per-chi-sviluppa)
- [Licenza](#licenza)

## Installazione

Pocket Travel funziona su **Android 8.0 o successivo** ed è distribuita solo su GitHub.

1. 📥 Scarica `app-release.apk` dall'[ultima versione](https://github.com/miracle091/pocket-travel/releases/latest) e installalo.
2. ⚙️ Al primo avvio scegli lingua, modi di spostarsi e nazionalità.
3. 🌍 Scarica le nazioni del viaggio, meglio con il Wi-Fi e prima di partire: poi tutto funziona offline.

> 💡 **Suggerimento.** Senza la rete stradale il Navigatore non calcola i percorsi: si aggiunge dai Contenuti della nazione, come i numeri civici e i mezzi pubblici.

L'app segnala le nuove versioni con una notifica; le novità sono nel [CHANGELOG](CHANGELOG.md).

## Funzioni

| | Funzione | Cosa fa |
|---|---|---|
| 📖 | **Guida** | Cosa vedere, dove mangiare, come muoversi e sicurezza per ogni paese e città; fatti rapidi, numeri di emergenza, ambasciate, meteo a 7 giorni |
| 💉 | **Vaccinazioni** | Obbligatorie, raccomandate e da valutare con il medico, calcolate offline da partenza, scali e paesi visitati |
| 🗺️ | **Mappa** | Ristoranti, hotel, farmacie, bancomat e molto altro, con orari e contatti, filtrati per modo di spostarsi |
| 🧭 | **Navigatore** | Percorsi a piedi, in bici e in auto, anche tra nazioni, con la navigazione passo passo a schermo spento |
| 🚌 | **Mezzi pubblici** | Partenze delle tre ore successive in fermate e stazioni, per le reti con dati aperti di 32 paesi, per lo più europei, e di Stati Uniti, Canada, Australia, Nuova Zelanda e Giappone |
| 🤖 | **Assistente IA** | Risposte brevi sulla guida scaricata e sulle note, anche senza internet |
| 🔐 | **Documenti e note** | Passaporto e biglietti cifrati, sbloccabili con impronta o volto; note di viaggio cifrate |
| 🏛️ | **Fonti ufficiali** | Ministero degli esteri del proprio paese, OMS e CDC |

Le 355 regioni sono aggiornate ogni settimana e i loro contenuti si scaricano separatamente; la mappa leggera e la rete stradale solo auto occupano circa il 40% in meno. Interfaccia, guide e assistente sono in italiano e in inglese.

## Perché Pocket Travel

| Situazione | Con un'app online | Con Pocket Travel |
|---|---|---|
| **Trovare una farmacia senza rete** | La mappa non carica | Mappa e punti di interesse offline |
| **Arrivare a un indirizzo senza roaming** | Nessun percorso | Navigatore offline, anche tra nazioni |
| **Prendere l'autobus** | App locale, spesso solo online | Partenze nel riquadro della fermata |
| **Fare una domanda sulla città** | Ricerca sul web | Assistente IA sul dispositivo, anche in aereo |
| **Proteggere i propri dati** | Account e dati sul server | Nessun account, tutto resta sul dispositivo |

## Assistente IA

L'assistente risponde in massimo tre frasi solo con ciò che trova nella guida e nelle note, e lo dice quando la guida non basta. Il motore si sceglie in **Impostazioni → Assistente IA**.

### 📱 Sul dispositivo (offline)

Il modello gira sul dispositivo e nessun dato ne esce. Servono almeno 4 GB di RAM; l'app propone il modello adatto e lo aggiorna quando migliora.

| RAM | Addestrato dal progetto (italiano) | Ufficiale (inglese) |
|---|---|---|
| 4 GB | [Pocket Travel 0.8B](tools/data-pipeline/model-cards/qwen3.5-0.8b-travel-it-GGUF.md) – 0,53 GB | [Qwen3.5 0.8B](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF) – 0,56 GB |
| 8 GB | [Pocket Travel 2B](tools/data-pipeline/model-cards/qwen3.5-2b-travel-it-GGUF.md) – 1,27 GB | [Qwen3.5 2B](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF) – 1,34 GB |
| 12 GB o più | [Pocket Travel 4B](tools/data-pipeline/model-cards/qwen3-4b-instruct-2507-travel-it-GGUF.md) – 2,50 GB | [Qwen3 4B Instruct 2507](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF) – 2,55 GB |

<a id="online-con-la-tua-chiave-api"></a>

### ☁️ Online (con una chiave API personale)

L'assistente usa un servizio di IA con la propria chiave API, che resta cifrata sul dispositivo. La domanda va al servizio **senza la guida scaricata** e l'uso si paga sull'account del servizio. Nell'app si sceglie il servizio, si incolla la chiave e si tocca **Salva la chiave**; **Crea la chiave su …** apre la pagina giusta.

| Servizio | Modello automatico | Più capace |
|---|---|---|
| [ChatGPT (OpenAI)](https://platform.openai.com/api-keys) | `gpt-4o-mini` | `gpt-5.6-terra` |
| [Mistral AI](https://console.mistral.ai/api-keys) | `mistral-small-latest` | `mistral-large-latest` |
| [Gemini (Google)](https://aistudio.google.com/apikey) | `gemini-flash-latest` | `gemini-2.5-pro` |
| [Claude (Anthropic)](https://console.anthropic.com/settings/keys) | `claude-haiku-4-5` | `claude-sonnet-4-6` |

## Schermate

<div align="center">
  <img src="docs/images/regions.png" width="200" alt="Nazioni scaricate e le altre divise per continente, con la ricerca in cima e il pulsante per la mappa delle nazioni" />
  <img src="docs/images/guide.png" width="200" alt="Guida di San Marino con i numeri di emergenza, le ambasciate del proprio paese e i fatti rapidi" />
  <img src="docs/images/map.png" width="200" alt="Mappa offline di San Marino con i punti di interesse e il pulsante dei filtri" />
  <img src="docs/images/route.png" width="200" alt="Navigatore con il percorso a piedi fino a un bar di San Marino: tempo, distanza e il pulsante per partire" />
</div>

<details>
<summary><strong>Altre schermate</strong></summary>

<div align="center">
  <br/>
  <img src="docs/images/filters.png" width="260" alt="Filtri della mappa divisi in gruppi, ognuno con i suoi interruttori" />
  <img src="docs/images/navigator.png" width="260" alt="Navigatore senza destinazione: la propria posizione e i punti di interesse vicini, con la ricerca Dove vuoi andare?" />
  <br/>
  <sub>Filtri della mappa e Navigatore senza destinazione</sub>
  <br/><br/>
  <img src="docs/images/missing-routes.png" width="260" alt="Navigatore da San Marino alla Lettonia: l'elenco delle nazioni di cui manca la rete stradale, in ordine lungo la strada, con il peso di ciascuna e il pulsante per scaricarle tutte" />
  <img src="docs/images/packages.png" width="260" alt="Contenuti scaricabili per San Marino" />
  <br/>
  <sub>Percorso tra nazioni con la rete stradale mancante e Contenuti di una nazione</sub>
  <br/><br/>
  <img src="docs/images/notes.png" width="260" alt="Documenti con il selettore Documenti e Note, sulle note di viaggio" />
  <img src="docs/images/ai.png" width="260" alt="Assistente IA online: scelta del servizio (ChatGPT, Mistral AI, Gemini, Claude) e del modello" />
  <br/>
  <sub>Note di viaggio e scelta del servizio per l'assistente IA online</sub>
</div>

</details>

## Dati e privacy

I dati vengono da progetti aperti: [Wikivoyage](https://it.wikivoyage.org) e [Wikipedia](https://it.wikipedia.org) per le guide, [OpenStreetMap](https://www.openstreetmap.org) e [Overture Maps](https://overturemaps.org) per mappe e civici, [Wikidata](https://www.wikidata.org) per fatti rapidi e ambasciate, Travel.gc.ca, TravelHealthPro e gov.uk per vaccinazioni e consigli di viaggio, [Open-Meteo](https://open-meteo.com) per il meteo. Fonti e licenze sono nella [pagina delle licenze](https://miracle091.github.io/pocket-travel/licenses.html).

🔒 L'app non ha un server proprio e non raccoglie dati sugli utenti. La posizione resta sul dispositivo; al meteo arriva solo la zona, arrotondata a circa 10 km. Il catalogo dei dati è firmato e l'app rifiuta i file non firmati.

## Aiuto e contributi

<div align="center">

[![Wiki](https://img.shields.io/badge/📖_Wiki-Leggi-blue?style=for-the-badge)](https://github.com/miracle091/pocket-travel/wiki)
[![Segnalazioni](https://img.shields.io/badge/🐛_Segnalazioni-Apri-red?style=for-the-badge)](https://github.com/miracle091/pocket-travel/issues)
[![Contribuire](https://img.shields.io/badge/🤝_Contribuire-Linee_guida-green?style=for-the-badge)](CONTRIBUTING.md)
[![Codice di condotta](https://img.shields.io/badge/📜_Codice_di_condotta-Leggi-purple?style=for-the-badge)](CODE_OF_CONDUCT.md)

</div>

- 🐛 [Segnala un errore](https://github.com/miracle091/pocket-travel/issues/new) con i passi per riprodurlo, la versione dell'app e il dispositivo.
- 🔒 Segnala le vulnerabilità in privato, con un [avviso di sicurezza](https://github.com/miracle091/pocket-travel/security/advisories/new).
- 🗺️ Correggi gli errori nei dati alla fonte (Wikivoyage, OpenStreetMap, Wikidata): arrivano nell'app con l'aggiornamento settimanale.
- 🤝 Per contribuire con codice, traduzioni o nuove reti di mezzi pubblici, segui le [linee guida](CONTRIBUTING.md) e il [codice di condotta](CODE_OF_CONDUCT.md).

## Per chi sviluppa

<details>
<summary><strong>Requisiti, comandi e architettura</strong></summary>

Servono Android Studio con **JDK 17** o successivo e Android 8.0 (API 26); Gradle è incluso. La [guida rapida](docs/GUIDA.md) spiega come compilare, provare e generare i dati; la [wiki](https://github.com/miracle091/pocket-travel/wiki) il perché delle scelte tecniche.

| Comando | Cosa fa |
|---|---|
| `./gradlew :app:installDebug` | Installa la build di debug |
| `./gradlew assembleDebug lint detekt testDebugUnitTest :core:poi:test :tools:data-pipeline:content:test` | Esegue i controlli della CI |
| `./gradlew :app:installDebug -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json` | Usa un catalogo di debug, senza controllo delle firme |
| `node tools/dev/simulate-route.js 12` | Muove il GPS dell'emulatore lungo il percorso del Navigatore, a 12 m/s |

```
┌────────────────────────────────────────────────────────────┐
│                       app (Compose)                        │
│          Nazioni · Navigatore · Documenti · Altro          │
└──────────────────────────────┬─────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────┐
│                         feature/*                          │
│   guide · map (mappa, Navigatore) · ai · vault · sources   │
│   con third-party: BRouter (percorsi), llama.cpp via JNI   │
└──────────────────────────────┬─────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────┐
│                           core/*                           │
│      data (Room) · sync (download, firme) · ui · poi       │
└──────────────────────────────┬─────────────────────────────┘
                               │ catalogo firmato (ECDSA P-256)
┌──────────────────────────────┴─────────────────────────────┐
│                   GitHub Pages: catalogo                   │
│     GitHub Releases: guide, mappe, punti di interesse,     │
│        rete stradale, numeri civici, mezzi pubblici        │
│                  Hugging Face: modelli IA                  │
└──────────────────────────────┬─────────────────────────────┘
                               │ pubblicati dalla pipeline (i dati ogni notte)
┌──────────────────────────────┴─────────────────────────────┐
│                    tools/data-pipeline                     │
│  OpenStreetMap · Overture · Wikivoyage · Wikidata · GTFS   │
└────────────────────────────────────────────────────────────┘
```

**Tecnologie:** Kotlin 2.4, Jetpack Compose con Material 3, Hilt, Room con ricerca FTS, MapLibre con PMTiles, BRouter, llama.cpp con modelli GGUF, WorkManager e OkHttp, detekt.

Un fork può pubblicare i propri dati firmati e usarli come catalogo personalizzato (**Altro → Impostazioni → Catalogo**): la procedura è in [tools/data-pipeline/README.md](tools/data-pipeline/README.md).

</details>

## Licenza

Codice sotto licenza [MIT](LICENSE) © 2026 miracle091. Software, modelli e dati di terze parti hanno le loro licenze, elencate nella [pagina delle licenze](https://miracle091.github.io/pocket-travel/licenses.html).
