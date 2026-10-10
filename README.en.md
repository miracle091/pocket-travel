<div align="center">
  <img src="docs/images/icon.svg" width="128" alt="Pocket Travel icon: a pocket with a compass" />
  <h1>Pocket Travel</h1>
  <p><strong>A compass in your pocket for every trip.</strong></p>
  <p>Guide, map, Navigator, AI assistant and documents, even without internet. No server, no account, no data collected.</p>
  <p>
    <a href="README.md">Italiano</a> | <a href="README.en.md"><strong>English</strong></a>
  </p>
  <p>
    <a href="https://github.com/miracle091/pocket-travel/releases/latest"><img src="https://img.shields.io/github/v/tag/miracle091/pocket-travel?filter=v*&sort=semver&style=flat-square&logo=github&label=version" alt="Version" /></a>
    <a href="https://github.com/miracle091/pocket-travel/actions/workflows/android-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/miracle091/pocket-travel/android-ci.yml?branch=main&style=flat-square&logo=github-actions&label=build" alt="Build status" /></a>
    <a href="#installation"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0 or later" /></a>
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/kotlin-2.4-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" /></a>
    <a href="https://developer.android.com/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue?style=flat-square" alt="MIT license" /></a>
    <a href="CONTRIBUTING.en.md"><img src="https://img.shields.io/badge/contributions-welcome-brightgreen?style=flat-square" alt="Contributions welcome" /></a>
  </p>
  <p>
    <img src="docs/images/guide.png" width="200" alt="San Marino guide with emergency numbers, the embassies of the user's country and quick facts" />
    <img src="docs/images/map.png" width="200" alt="Offline map of San Marino with points of interest and the filters button" />
    <img src="docs/images/route.png" width="200" alt="Navigator with a walking route to a café in San Marino: time, distance and the start button" />
  </p>
  <p><sub>The screenshots show the app in Italian; the whole app is also available in English.</sub></p>
</div>

---

## Contents

- [Installation](#installation)
- [Features](#features)
- [Why Pocket Travel?](#why-pocket-travel)
- [AI assistant](#ai-assistant)
- [Screenshots](#screenshots)
- [Data and privacy](#data-and-privacy)
- [Help and contributions](#help-and-contributions)
- [For developers](#for-developers)
- [License](#license)

## Installation

Pocket Travel runs on **Android 8.0 or later** and is distributed only on GitHub.

1. 📥 Download `app-release.apk` from the [latest version](https://github.com/miracle091/pocket-travel/releases/latest) and install it.
2. ⚙️ On first launch, choose language, travel modes and nationality.
3. 🌍 Download the countries of the trip, preferably over Wi-Fi and before leaving: then everything works offline.

> 💡 **Hint.** Without the road network the Navigator cannot calculate routes: add it from the country's content, like house numbers and public transport.

The app announces new versions with a notification; the changes are in the [CHANGELOG](CHANGELOG.md) (in Italian).

## Features

| | Feature | What it does |
|---|---|---|
| 📖 | **Guide** | What to see, where to eat, how to get around and safety for each country and city; quick facts, emergency numbers, embassies, 7-day weather |
| 💉 | **Vaccinations** | Required, recommended and to discuss with a doctor, calculated offline from departure, stopovers and countries visited |
| 🗺️ | **Map** | Restaurants, hotels, pharmacies, ATMs and much more, with hours and contacts, filtered by travel mode |
| 🧭 | **Navigator** | Walking, cycling and driving routes, across countries too, with turn-by-turn navigation with the screen off |
| 🚌 | **Public transport** | Departures of the next three hours at stops and stations, for open-data networks in 32 countries, mostly in Europe, and in the United States, Canada, Australia, New Zealand and Japan |
| 🤖 | **AI assistant** | Short answers based on the downloaded guide and the notes, even without internet |
| 🔐 | **Documents and notes** | Encrypted passport and tickets, unlocked with fingerprint or face; encrypted travel notes |
| 🏛️ | **Official sources** | Foreign ministry of the user's country, WHO and CDC |

The 355 regions are updated every week and their content is downloaded separately; the light map and the car-only road network take about 40% less space. Interface, guides and assistant are in Italian and English.

## Why Pocket Travel?

| Situation | With an online app | With Pocket Travel |
|---|---|---|
| **Finding a pharmacy without a connection** | The map does not load | Offline map and points of interest |
| **Reaching an address without roaming** | No route | Offline Navigator, across countries too |
| **Catching a bus** | Local app, often online only | Departures in the stop's card |
| **Asking about the city** | Web search | AI assistant on the device, even on a plane |
| **Protecting personal data** | Account and data on a server | No account, everything stays on the device |

## AI assistant

The assistant answers in at most three sentences, only with what it finds in the guide and the notes, and says when the guide is not enough. The engine is chosen in **Settings → AI assistant**.

### 📱 On device (offline)

The model runs on the device and no data leaves it. It needs at least 4 GB of RAM; the app suggests the right model and updates it when it improves.

| RAM | Trained by the project (Italian) | Official (English) |
|---|---|---|
| 4 GB | [Pocket Travel 0.8B](tools/data-pipeline/model-cards/qwen3.5-0.8b-travel-it-GGUF.md) – 0.53 GB | [Qwen3.5 0.8B](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF) – 0.56 GB |
| 8 GB | [Pocket Travel 2B](tools/data-pipeline/model-cards/qwen3.5-2b-travel-it-GGUF.md) – 1.27 GB | [Qwen3.5 2B](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF) – 1.34 GB |
| 12 GB or more | [Pocket Travel 4B](tools/data-pipeline/model-cards/qwen3-4b-instruct-2507-travel-it-GGUF.md) – 2.50 GB | [Qwen3 4B Instruct 2507](https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF) – 2.55 GB |

### ☁️ Online (with a personal API key)

The assistant uses an AI service with the user's own API key, which stays encrypted on the device. The question goes to the service **without the downloaded guide** and usage is billed to the service account. In the app, choose the service, paste the key and tap **Save key**; **Create the key on …** opens the right page.

| Service | Automatic model | More capable |
|---|---|---|
| [ChatGPT (OpenAI)](https://platform.openai.com/api-keys) | `gpt-4o-mini` | `gpt-5.6-terra` |
| [Mistral AI](https://console.mistral.ai/api-keys) | `mistral-small-latest` | `mistral-large-latest` |
| [Gemini (Google)](https://aistudio.google.com/apikey) | `gemini-flash-latest` | `gemini-2.5-pro` |
| [Claude (Anthropic)](https://console.anthropic.com/settings/keys) | `claude-haiku-4-5` | `claude-sonnet-4-6` |

## Screenshots

<div align="center">
  <img src="docs/images/regions.png" width="200" alt="Downloaded countries and the others divided by continent, with search at the top and the button for the country map" />
  <img src="docs/images/guide.png" width="200" alt="San Marino guide with emergency numbers, the embassies of the user's country and quick facts" />
  <img src="docs/images/map.png" width="200" alt="Offline map of San Marino with points of interest and the filters button" />
  <img src="docs/images/route.png" width="200" alt="Navigator with a walking route to a café in San Marino: time, distance and the start button" />
</div>

<details>
<summary><strong>More screenshots</strong></summary>

<div align="center">
  <br/>
  <img src="docs/images/filters.png" width="260" alt="Map filters divided into groups, each with its own switches" />
  <img src="docs/images/navigator.png" width="260" alt="Navigator without a destination: the current location and nearby points of interest, with the Where to? search" />
  <br/>
  <sub>Map filters and the Navigator without a destination</sub>
  <br/><br/>
  <img src="docs/images/missing-routes.png" width="260" alt="Navigator from San Marino to Latvia: the countries whose road network is missing, in order along the way, with the size of each and the button to download them all" />
  <img src="docs/images/packages.png" width="260" alt="Downloadable content for San Marino" />
  <br/>
  <sub>A route across countries with missing road networks, and a country's content</sub>
  <br/><br/>
  <img src="docs/images/notes.png" width="260" alt="Documents with the Documents and Notes selector, on the travel notes" />
  <img src="docs/images/ai.png" width="260" alt="Online AI assistant: choice of service (ChatGPT, Mistral AI, Gemini, Claude) and model" />
  <br/>
  <sub>Travel notes and the service choice for the online AI assistant</sub>
</div>

</details>

## Data and privacy

The data comes from open projects: [Wikivoyage](https://en.wikivoyage.org) and [Wikipedia](https://en.wikipedia.org) for the guides, [OpenStreetMap](https://www.openstreetmap.org) and [Overture Maps](https://overturemaps.org) for maps and house numbers, [Wikidata](https://www.wikidata.org) for quick facts and embassies, Travel.gc.ca, TravelHealthPro and gov.uk for vaccinations and travel advice, [Open-Meteo](https://open-meteo.com) for the weather. Sources and licenses are on the [licenses page](https://miracle091.github.io/pocket-travel/licenses-en.html).

🔒 The app has no server of its own and collects no data about its users. The location stays on the device; the weather service receives only the area, rounded to about 10 km. The data catalog is signed and the app rejects unsigned files.

## Help and contributions

<div align="center">

[![Wiki](https://img.shields.io/badge/📖_Wiki-Read-blue?style=for-the-badge)](https://github.com/miracle091/pocket-travel/wiki)
[![Issues](https://img.shields.io/badge/🐛_Issues-Open-red?style=for-the-badge)](https://github.com/miracle091/pocket-travel/issues)
[![Contributing](https://img.shields.io/badge/🤝_Contributing-Guidelines-green?style=for-the-badge)](CONTRIBUTING.en.md)
[![Code of conduct](https://img.shields.io/badge/📜_Code_of_conduct-Read-purple?style=for-the-badge)](CODE_OF_CONDUCT.en.md)

</div>

- 🐛 [Report a bug](https://github.com/miracle091/pocket-travel/issues/new) with the steps to reproduce it, the app version and the device.
- 🔒 Report vulnerabilities privately, with a [security advisory](https://github.com/miracle091/pocket-travel/security/advisories/new).
- 🗺️ Fix data errors at the source (Wikivoyage, OpenStreetMap, Wikidata): they reach the app with the weekly update.
- 🤝 To contribute code, translations or new public transport networks, follow the [guidelines](CONTRIBUTING.en.md) and the [code of conduct](CODE_OF_CONDUCT.en.md). Issues and pull requests can be written in Italian or English.

## For developers

<details>
<summary><strong>Requirements, commands and architecture</strong></summary>

Android Studio with **JDK 17** or later and Android 8.0 (API 26) are required; Gradle is included. The [quick guide](docs/GUIDA.md) explains how to build, test and generate the data; the [wiki](https://github.com/miracle091/pocket-travel/wiki) explains the technical choices. Both are in Italian.

| Command | What it does |
|---|---|
| `./gradlew :app:installDebug` | Installs the debug build |
| `./gradlew assembleDebug lint detekt testDebugUnitTest :core:poi:test :tools:data-pipeline:content:test` | Runs the CI checks |
| `./gradlew :app:installDebug -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json` | Uses a debug catalog, without signature checks |
| `node tools/dev/simulate-route.js 12` | Moves the emulator's GPS along the Navigator route, at 12 m/s |

```
┌────────────────────────────────────────────────────────────┐
│                       app (Compose)                        │
│          Countries · Navigator · Documents · More          │
└──────────────────────────────┬─────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────┐
│                         feature/*                          │
│    guide · map (map, Navigator) · ai · vault · sources     │
│   with third-party: BRouter (routing), llama.cpp via JNI   │
└──────────────────────────────┬─────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────┐
│                           core/*                           │
│   data (Room) · sync (downloads, signatures) · ui · poi    │
└──────────────────────────────┬─────────────────────────────┘
                               │ signed catalog (ECDSA P-256)
┌──────────────────────────────┴─────────────────────────────┐
│                   GitHub Pages: catalog                    │
│     GitHub Releases: guides, maps, points of interest,     │
│       road networks, house numbers, public transport       │
│                  Hugging Face: AI models                   │
└──────────────────────────────┬─────────────────────────────┘
                               │ published by the pipeline (data nightly)
┌──────────────────────────────┴─────────────────────────────┐
│                    tools/data-pipeline                     │
│  OpenStreetMap · Overture · Wikivoyage · Wikidata · GTFS   │
└────────────────────────────────────────────────────────────┘
```

**Tech stack:** Kotlin 2.4, Jetpack Compose with Material 3, Hilt, Room with FTS search, MapLibre with PMTiles, BRouter, llama.cpp with GGUF models, WorkManager and OkHttp, detekt.

A fork can publish its own signed data and use it as a custom catalog (**More → Settings → Catalog**): the procedure is in [tools/data-pipeline/README.md](tools/data-pipeline/README.md).

</details>

## License

Code under the [MIT](LICENSE) license © 2026 miracle091. Third-party software, models and data have their own licenses, listed on the [licenses page](https://miracle091.github.io/pocket-travel/licenses-en.html).
