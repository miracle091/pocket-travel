package com.pockettravel.app.browser

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

// Sostituisce le Chrome Custom Tabs per le fonti ufficiali del registro (Farnesina, OMS, Agenzia
// delle Dogane) e per la sorgente Wikivoyage di ogni sezione guida: l'utente ha chiesto
// esplicitamente di restare dentro l'app per questi link. Le pagine modello HuggingFace (licenze)
// restano invece su CustomTabsLauncher — vedi il commento li' per il perche' quella resta la
// scelta di default (aggiornamenti di sicurezza del browser di sistema, nessuna cache persistente
// nell'app). Il tasto Indietro di sistema torna alla pagina precedente DENTRO il sito se
// possibile, prima di uscire dalla schermata.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InAppBrowserScreen(url: String, title: String, onBack: () -> Unit) {
    var pageTitle by remember { mutableStateOf(title) }
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val current = webView
        if (current != null && current.canGoBack()) current.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pageTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                modifier = Modifier.fillMaxSize().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, loadedUrl: String) {
                                isLoading = false
                                view.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
            )
        }
    }
}
