package com.pockettravel.app.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.app.regions.RegionRow
import com.pockettravel.app.regions.RegionRowActions
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.HeroShape
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.ai.AiAssistantViewModel
import com.pockettravel.feature.ai.ModelListCard

private enum class InfoIcon { COMPASS, AI, VAULT, OFFICIAL }

private sealed interface OnboardingStep {
    data class Info(@StringRes val title: Int, @StringRes val body: Int, val icon: InfoIcon) : OnboardingStep
    data object GuidesDownload : OnboardingStep
    data object RegionDownload : OnboardingStep
    data object AiModelDownload : OnboardingStep
}

@Composable
private fun InfoIcon.vector(): ImageVector = when (this) {
    InfoIcon.COMPASS -> AppIcons.Compass
    InfoIcon.AI -> AppIcons.AiAssistant
    InfoIcon.VAULT -> AppIcons.Passport
    InfoIcon.OFFICIAL -> AppIcons.OfficialAuthority
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val isOnDeviceAiSupported = viewModel.isOnDeviceAiSupported
    val steps = remember(isOnDeviceAiSupported) {
        buildList {
            add(OnboardingStep.Info(R.string.onboarding_welcome_title, R.string.onboarding_welcome_body, InfoIcon.COMPASS))
            add(OnboardingStep.GuidesDownload)
            add(OnboardingStep.RegionDownload)
            add(
                if (isOnDeviceAiSupported) {
                    OnboardingStep.Info(R.string.onboarding_ai_title, R.string.onboarding_ai_body, InfoIcon.AI)
                } else {
                    OnboardingStep.Info(R.string.onboarding_ai_online_title, R.string.onboarding_ai_online_body, InfoIcon.AI)
                },
            )
            if (isOnDeviceAiSupported) add(OnboardingStep.AiModelDownload)
            add(OnboardingStep.Info(R.string.onboarding_vault_title, R.string.onboarding_vault_body, InfoIcon.VAULT))
            add(OnboardingStep.Info(R.string.onboarding_sources_title, R.string.onboarding_sources_body, InfoIcon.OFFICIAL))
        }
    }

    // rememberSaveable: ruotando lo schermo il wizard resta allo stesso passo.
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val isLastStep = stepIndex == steps.lastIndex

    // Il tasto Indietro di sistema deve comportarsi come "Indietro" qui dentro (tornare allo step
    // precedente), non uscire di colpo dal wizard perdendo la posizione — solo al primo step non
    // c'e' nulla da intercettare, torna il comportamento di default (esce).
    BackHandler(enabled = stepIndex > 0) { stepIndex -= 1 }

    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Richiesta al raggiungimento dell'ultimo step, non subito all'apertura: a quel punto l'utente
    // ha gia' visto perche' servono (controllo aggiornamenti regioni/app/modello IA, vedi step
    // precedenti) invece di un permesso a freddo alla primissima schermata.
    LaunchedEffect(isLastStep) {
        if (
            isLastStep &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Larghezza massima: su tablet il wizard resta una colonna leggibile al centro.
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(Spacing.xl)) {
            // Avanti/Indietro scorrono il contenuto nella direzione del passo (shared axis orizzontale).
            AnimatedContent(
                targetState = stepIndex,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(300)) { it / 6 * direction } + fadeIn(tween(200, delayMillis = 75)))
                        .togetherWith(slideOutHorizontally(tween(300)) { -it / 6 * direction } + fadeOut(tween(75)))
                },
                modifier = Modifier.weight(1f),
                label = "onboardingStep",
            ) { index ->
                when (val currentStep = steps[index]) {
                    is OnboardingStep.Info -> InfoStepContent(currentStep)
                    OnboardingStep.GuidesDownload -> GuidesDownloadStepContent()
                    OnboardingStep.RegionDownload -> RegionDownloadStepContent()
                    OnboardingStep.AiModelDownload -> AiModelDownloadStepContent()
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.l)) {
                StepIndicator(count = steps.size, current = stepIndex)
                Spacer(modifier = Modifier.width(Spacing.m))
                Text(
                    text = stringResource(R.string.onboarding_step, stepIndex + 1, steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.l))

            // "Salta"/"Avanti" restano gli unici controlli di navigazione, anche per i due step
            // interattivi: scaricare una regione o il modello IA qui e' facoltativo, "Avanti" non
            // resta mai bloccato in attesa di un download.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.complete(); onComplete() }) {
                    Text(stringResource(R.string.onboarding_skip))
                }
                Spacer(modifier = Modifier.weight(1f))
                if (stepIndex > 0) {
                    TextButton(onClick = { stepIndex -= 1 }) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                    Spacer(modifier = Modifier.width(Spacing.s))
                }
                Button(
                    onClick = {
                        if (isLastStep) {
                            viewModel.complete()
                            onComplete()
                        } else {
                            stepIndex += 1
                        }
                    },
                ) {
                    Text(stringResource(if (isLastStep) R.string.onboarding_start else R.string.onboarding_next))
                }
            }
        }
    }
}

@Composable
private fun InfoStepContent(step: OnboardingStep.Info) {
    // Scorrevole: con il testo al 200% il contenuto puo' superare l'altezza disponibile.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = HeroShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                imageVector = step.icon.vector(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(Spacing.xl).size(56.dp),
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xl))
        Text(
            text = stringResource(step.title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(Spacing.l))
        Text(
            text = stringResource(step.body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Intestazione compatta (icona + titolo in riga) per gli step interattivi, che a differenza di
// InfoStepContent hanno sotto una lista da tenere scrollabile, non spazio vuoto da centrare.
@Composable
private fun StepHeader(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(Spacing.s).size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(Spacing.m))
        Text(text = title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    }
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.m),
    )
}

// Passo facoltativo: pacchetto guide unico per tutte le nazioni (meno di un MB). Se l'utente lo
// salta, le guide si scaricano da sole alla prima connessione Wi-Fi (GuidesSyncWorker).
@Composable
private fun GuidesDownloadStepContent(viewModel: GuidesDownloadViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        StepHeader(
            icon = AppIcons.Book,
            title = stringResource(R.string.onboarding_guides_title),
            body = stringResource(R.string.onboarding_guides_body),
        )
        when {
            uiState.isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(stringResource(R.string.onboarding_guides_done), style = MaterialTheme.typography.bodyLarge)
            }
            uiState.isDownloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                PocketTravelLoadingIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(Spacing.m))
                Text(stringResource(R.string.onboarding_guides_downloading), style = MaterialTheme.typography.bodyLarge)
            }
            else -> FilledTonalButton(onClick = viewModel::download) {
                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(
                    uiState.downloadBytes
                        ?.let { stringResource(R.string.onboarding_guides_download, Formatter.formatShortFileSize(context, it)) }
                        ?: stringResource(R.string.onboarding_guides_download_no_size),
                )
            }
        }
    }
}

// Riusa RegionListViewModel/RegionRow del modulo :regions (stesso modulo :app, package diverso):
// stessa logica di download/eliminazione della schermata "Regioni" vera, non una copia.
@Composable
private fun RegionDownloadStepContent(viewModel: RegionListViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        StepHeader(
            icon = AppIcons.World,
            title = stringResource(R.string.onboarding_region_title),
            body = stringResource(R.string.onboarding_region_body),
        )
        when {
            uiState.isLoading && uiState.items.isEmpty() -> PocketTravelLoadingIndicator()
            uiState.loadError != null -> Text(text = stringResource(uiState.loadError!!), color = MaterialTheme.colorScheme.error)
            else -> Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn {
                    items(uiState.items, key = { it.regionId }) { item ->
                        // onClick e' no-op qui: l'onboarding non naviga al dettaglio di una regione,
                        // la riga serve solo per vedere lo stato e scaricare/eliminare.
                        RegionRow(
                            item = item,
                            actions = RegionRowActions(
                                observeProgress = viewModel::observeDownloadProgress,
                                onDownload = viewModel::download,
                                onDelete = viewModel::delete,
                                onDownloadPackage = viewModel::downloadPackage,
                                onDeletePackage = viewModel::deletePackage,
                            ),
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

// Riusa AiAssistantViewModel/ModelListCard di :feature:ai: stessa logica di selezione/download/
// download della schermata "Assistente IA" vera. Mostrato solo quando isOnDeviceAiSupported
// (vedi OnboardingScreen), quindi il catalogo qui non e' mai vuoto.
@Composable
private fun AiModelDownloadStepContent(viewModel: AiAssistantViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepHeader(
            icon = AppIcons.AiAssistant,
            title = stringResource(R.string.onboarding_model_title),
            body = stringResource(R.string.onboarding_model_body),
        )
        ModelListCard(uiState, viewModel)
    }
}

// Indicatore di avanzamento in stile M3: il passo corrente e' una pillola allungata, gli altri
// pallini. Puramente visivo (il testo "Passo X di Y" accanto lo rende accessibile).
@Composable
private fun StepIndicator(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .size(width = if (active) 24.dp else 8.dp, height = 8.dp)
                    .background(
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )
        }
    }
}
