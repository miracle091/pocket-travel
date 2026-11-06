package com.pockettravel.app.storage

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.app.regions.deleteTitle
import com.pockettravel.app.regions.icon
import com.pockettravel.app.regions.label
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val usedBytes = uiState.regionsSizeBytes + uiState.modelSizeBytes
    val totalBytes = usedBytes + uiState.availableBytes
    val usedFraction = if (totalBytes > 0) usedBytes / totalBytes.toFloat() else 0f

    val snackbarHostState = remember { SnackbarHostState() }
    val message = uiState.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            Text(
                text = stringResource(
                    R.string.storage_summary,
                    Formatter.formatShortFileSize(context, usedBytes),
                    Formatter.formatShortFileSize(context, uiState.availableBytes),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            LinearProgressIndicator(
                progress = { usedFraction },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.m, bottom = Spacing.xl),
            )

            SectionHeader(stringResource(R.string.storage_guides))
            val guides = uiState.guides
            if (guides != null) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    // Nessun elimina: le guide si riscaricherebbero da sole al primo Wi-Fi.
                    StorageRow(
                        icon = AppIcons.Book,
                        title = stringResource(R.string.storage_guides_title),
                        supporting = stringResource(R.string.storage_guides_auto, Formatter.formatShortFileSize(context, guides.sizeBytes)),
                    )
                }
            } else {
                EmptyLine(stringResource(R.string.storage_no_guides))
                TextButton(onClick = viewModel::downloadGuides, modifier = Modifier.padding(start = Spacing.s)) {
                    Text(stringResource(R.string.storage_guides_download))
                }
            }

            SectionHeader(stringResource(R.string.storage_regions), modifier = Modifier.padding(top = Spacing.xl))
            if (uiState.installedRegions.isEmpty()) {
                EmptyLine(stringResource(R.string.storage_no_regions))
            } else {
                // Una superficie per regione: il totale (elimina tutto) e sotto, chiusi finche' non si tocca la regione,
                // mappa, rete stradale e POI. key: il dialogo di conferma e l'apertura (rememberSaveable) restano alla
                // loro regione se l'elenco cambia.
                uiState.installedRegions.forEach { region -> key(region.regionId) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.padding(bottom = Spacing.s),
                    ) {
                        Column {
                            StorageRow(
                                icon = AppIcons.WorldFilled,
                                countryCode = region.countryCode,
                                title = region.displayName,
                                supporting = Formatter.formatShortFileSize(context, region.sizeBytes),
                                onDelete = { viewModel.deleteRegion(region.regionId) },
                                expanded = expanded,
                                onToggle = { expanded = !expanded },
                            )
                            AnimatedVisibility(visible = expanded) {
                                Column {
                                    region.packages.forEach { pkg -> key(pkg.kind) {
                                        HorizontalDivider(modifier = Modifier.padding(start = Spacing.l + 56.dp))
                                        StorageRow(
                                            icon = pkg.kind.icon(),
                                            title = stringResource(pkg.kind.label()),
                                            supporting = pkg.sizeBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "—",
                                            onDelete = { viewModel.deletePackage(region.regionId, pkg.kind) },
                                            deleteTitle = stringResource(pkg.kind.deleteTitle(), region.displayName),
                                            deleteMessage = stringResource(R.string.package_delete_message),
                                            underToggle = true,
                                        )
                                    } }
                                }
                            }
                        }
                    }
                } }
            }

            SectionHeader(stringResource(R.string.storage_model), modifier = Modifier.padding(top = Spacing.xl))
            if (uiState.isModelDownloaded) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    StorageRow(
                        icon = AppIcons.AiAssistant,
                        title = stringResource(R.string.storage_model),
                        supporting = Formatter.formatShortFileSize(context, uiState.modelSizeBytes),
                        onDelete = { viewModel.deleteModel() },
                    )
                }
            } else {
                EmptyLine(stringResource(R.string.storage_no_model))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Spacing.l, bottom = Spacing.s).semantics { heading() },
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.l),
    )
}

@Composable
private fun StorageRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
    // Se c'e' una bandiera per il codice, al posto di [icon].
    countryCode: String? = null,
    // null: riga solo informativa, senza elimina.
    onDelete: (() -> Unit)? = null,
    deleteTitle: String = stringResource(R.string.storage_delete_title, title),
    deleteMessage: String = stringResource(R.string.storage_delete_message),
    // Non null: la riga apre e chiude i contenuti sotto di lei.
    expanded: Boolean? = null,
    onToggle: () -> Unit = {},
    // Sotto una riga che si apre: uno spazio al posto della freccia, cosi' i cestini stanno in colonna.
    underToggle: Boolean = false,
) {
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val toggle = expanded?.let { isExpanded ->
        val stateText = stringResource(if (isExpanded) R.string.continent_expanded else R.string.continent_collapsed)
        val actionLabel = stringResource(if (isExpanded) R.string.storage_contents_collapse else R.string.storage_contents_expand)
        Modifier
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = stateText }
    } ?: Modifier
    val rotation by animateFloatAsState(if (expanded == true) 180f else 0f, label = "storageChevron")

    ListItem(
        supportingContent = { Text(supporting) },
        leadingContent = {
            // Le icone nella stessa colonna da 40 dp delle bandiere, centrate.
            CountryFlag(countryCode, size = 40.dp) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
        },
        trailingContent = if (onDelete != null || expanded != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onDelete != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(AppIcons.Delete, contentDescription = stringResource(R.string.storage_delete, title))
                        }
                    }
                    if (expanded != null) Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation))
                    if (underToggle) Spacer(modifier = Modifier.size(24.dp))
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.then(toggle),
        content = { Text(title) },
    )

    if (showDeleteConfirm && onDelete != null) {
        ConfirmationDialog(
            title = deleteTitle,
            message = deleteMessage,
            onConfirm = { showDeleteConfirm = false; onDelete() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
