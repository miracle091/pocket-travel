package com.pockettravel.app.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.ui.HeroShape
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.launch

/**
 * Primo avvio in tre passi: Benvenuto (lingua, modi di spostarsi, nazionalita'), Scegli dove vai (nazioni, con
 * download in background) e Pronto (riepilogo). Una sola azione principale per passo.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    // rememberSaveable: ruotando lo schermo il wizard resta allo stesso passo.
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingStep.entries[stepIndex]
    val isLastStep = step == OnboardingStep.READY
    val usageModes by viewModel.usageModes.collectAsStateWithLifecycle()
    val nationality by viewModel.nationality.collectAsStateWithLifecycle()
    val canAdvance = step.canAdvance(hasUsageModes = usageModes.isNotEmpty(), hasNationality = nationality != null)

    // Nazioni spuntate nel secondo passo: "Avanti" ne avvia il download in background e il wizard prosegue.
    val regionViewModel: RegionListViewModel = hiltViewModel()
    val selection = rememberSaveable(saver = selectionSaver) { mutableStateMapOf<String, Set<PackageKind>>() }
    var startedDownloads by rememberSaveable { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val advance: () -> Unit = {
        if (isLastStep) {
            viewModel.complete()
            onComplete()
        } else {
            stepIndex += 1
        }
    }
    val goNext: () -> Unit = {
        when {
            !canAdvance || starting -> Unit
            step == OnboardingStep.DESTINATIONS && selection.isNotEmpty() -> scope.launch {
                starting = true
                startedDownloads += regionViewModel.downloadSelection(selection)
                starting = false
                // Qualcuna non partita (spazio insufficiente): si resta qui con il messaggio.
                if (selection.isEmpty()) advance()
            }
            else -> advance()
        }
    }
    val goBack = { if (stepIndex > 0) stepIndex -= 1 }
    // Il tasto Indietro di sistema torna al passo precedente invece di uscire dal wizard; al primo passo esce.
    BackHandler(enabled = stepIndex > 0) { stepIndex -= 1 }
    NotificationPermissionOnLastStep(isLastStep)

    // Larghezza massima: su tablet il wizard resta una colonna leggibile al centro.
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize().padding(Spacing.xl)) {
            // Avanti/Indietro scorrono il contenuto nella direzione del passo (shared axis orizzontale).
            AnimatedContent(
                targetState = step,
                transitionSpec = { stepTransition(forward = targetState > initialState) },
                modifier = Modifier.weight(1f).then(swipeBetweenSteps(goNext, goBack, isLastStep)),
                label = "onboardingStep",
            ) { current ->
                when (current) {
                    OnboardingStep.WELCOME -> OnboardingWelcomeStep(viewModel)
                    OnboardingStep.DESTINATIONS -> OnboardingRegionStep(viewModel, selection, regionViewModel)
                    OnboardingStep.READY -> OnboardingReadyStep(viewModel, startedDownloads)
                }
            }
            StepIndicator(count = OnboardingStep.entries.size, current = stepIndex, modifier = Modifier.padding(vertical = Spacing.l))
            NavigationRow(stepIndex, isLastStep, enabled = canAdvance && !starting, hasDownloads = selection.isNotEmpty(), onBack = goBack, onNext = goNext)
        }
    }
}

private fun stepTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(tween(300)) { it / 6 * direction } + fadeIn(tween(200, delayMillis = 75)))
        .togetherWith(slideOutHorizontally(tween(300)) { -it / 6 * direction } + fadeOut(tween(75)))
}

// Richiesta al raggiungimento dell'ultimo passo, non subito all'apertura: a quel punto l'utente ha gia' visto cosa
// e' stato impostato (controllo aggiornamenti di nazioni, app e modello IA) invece di un permesso a freddo.
@Composable
private fun NotificationPermissionOnLastStep(isLastStep: Boolean) {
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
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
}

// Scorrimento orizzontale sul contenuto: verso sinistra come "Avanti" (non sull'ultimo passo, che si chiude solo col
// pulsante), verso destra come "Indietro". Stesse azioni dei pulsanti, quindi anche i controlli sui passi obbligatori
// e l'avvio dei download; un elemento del passo che scorre in orizzontale consuma il gesto per primo.
@Composable
private fun swipeBetweenSteps(goNext: () -> Unit, goBack: () -> Unit, isLastStep: Boolean): Modifier {
    val currentGoNext by rememberUpdatedState(goNext)
    val currentGoBack by rememberUpdatedState(goBack)
    val currentIsLastStep by rememberUpdatedState(isLastStep)
    val swipeThreshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    return Modifier.pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                when {
                    total < -swipeThreshold && !currentIsLastStep -> currentGoNext()
                    total > swipeThreshold -> currentGoBack()
                }
            },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                total += dragAmount
            },
        )
    }
}

// Un solo pulsante principale: "Avanti", "Scarica e continua" (con nazioni da scaricare) o "Inizia"; "Indietro" e' secondario.
@Composable
private fun NavigationRow(
    stepIndex: Int,
    isLastStep: Boolean,
    enabled: Boolean,
    hasDownloads: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.weight(1f))
        if (stepIndex > 0) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
            Spacer(modifier = Modifier.width(Spacing.s))
        }
        val label = when {
            isLastStep -> R.string.onboarding_start
            stepIndex == OnboardingStep.DESTINATIONS.ordinal && hasDownloads -> R.string.onboarding_next_download
            else -> R.string.onboarding_next
        }
        Button(onClick = onNext, enabled = enabled) { Text(stringResource(label)) }
    }
}

// Intestazione compatta (icona + titolo in riga, poi una riga di spiegazione) dei tre passi, che hanno sotto
// contenuti da tenere scrollabili.
@Composable
internal fun StepHeader(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = HeroShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(Spacing.m).size(24.dp),
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

// Indicatore di avanzamento in stile M3: il passo corrente e' una pillola allungata, gli altri pallini.
// Esposto a TalkBack come "Passo X di Y".
@Composable
private fun StepIndicator(count: Int, current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.onboarding_step, current + 1, count)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
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

// Spostamento orizzontale oltre il quale lo scorrimento cambia passo.
private val SWIPE_THRESHOLD = 72.dp
