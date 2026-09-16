package com.pockettravel.core.ui

// Non un limite tecnico: solo la soglia oltre cui un download (pacchetto regionale, modello IA)
// va confermato esplicitamente prima di partire, invece di scattare al primo tap - condivisa tra
// app/regions e feature/ai cosi' un domani lo stesso avviso resta coerente ovunque nell'app.
const val LARGE_DOWNLOAD_WARNING_BYTES = 100L * 1024 * 1024
