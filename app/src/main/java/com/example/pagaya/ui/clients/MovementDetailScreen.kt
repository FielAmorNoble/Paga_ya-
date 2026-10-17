package com.example.pagaya.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.data.local.MovementEntity
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.ReasonError
import com.example.pagaya.ui.common.ScreenHeader
import com.example.pagaya.ui.common.formatCop
import com.example.pagaya.ui.common.formatDateTime
import com.example.pagaya.ui.common.formatDayLong
import com.example.pagaya.ui.common.formatDayShort
import com.example.pagaya.ui.common.formatSignedCop
import kotlinx.coroutines.launch

@Composable
fun MovementDetailScreen(
    onCorrect: (movementId: String) -> Unit,
    onViewChanges: (movementId: String) -> Unit,
    onBack: () -> Unit,
    /** Aviso pendiente tras volver de la corrección («CORRECTED»); `null` si no hay ninguno. */
    savedMessage: String?,
    onSavedMessageShown: () -> Unit,
    viewModel: MovementDetailViewModel = viewModel(factory = MovementDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val annul by viewModel.annul.collectAsStateWithLifecycle()
    val annulled by viewModel.annulled.collectAsStateWithLifecycle()
    val loaded = state as? MovementDetailState.Loaded

    val snackbarHostState = remember { SnackbarHostState() }
    val correctionSaved = stringResource(R.string.saved_correction)
    val annulledMessage = stringResource(R.string.saved_annulled)
    // Consumir el aviso cambia la clave del efecto: el snackbar va en un scope que no se cancela por eso.
    val snackbarScope = rememberCoroutineScope()
    LaunchedEffect(savedMessage) {
        if (savedMessage == SAVED_CORRECTION) {
            onSavedMessageShown()
            snackbarScope.launch { snackbarHostState.showSnackbar(correctionSaved) }
        }
    }
    LaunchedEffect(annulled) {
        if (annulled) {
            viewModel.consumeAnnulled()
            snackbarScope.launch { snackbarHostState.showSnackbar(annulledMessage) }
        }
    }

    Scaffold(
        topBar = { ScreenHeader(title = stringResource(R.string.movement_detail_title), onBack = onBack) },
        bottomBar = {
            if (loaded != null && loaded.movement.active && loaded.account.client.active) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = { onCorrect(loaded.movement.id) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.movement_action_correct), style = MaterialTheme.typography.titleMedium)
                        }
                        OutlinedButton(
                            onClick = viewModel::openAnnul,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        ) {
                            Text(stringResource(R.string.movement_action_annul), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val current = state) {
                MovementDetailState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                MovementDetailState.NotFound -> Text(
                    stringResource(R.string.movement_detail_not_found),
                    Modifier.align(Alignment.Center).padding(24.dp),
                )
                MovementDetailState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.clients_load_error), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = viewModel::retry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                is MovementDetailState.Loaded -> MovementContent(
                    current,
                    onViewChanges = { onViewChanges(current.movement.id) },
                    reserveNavigationBar = !(current.movement.active && current.account.client.active),
                )
            }
        }
    }

    (annul as? AnnulState.Open)?.let { open ->
        if (loaded != null) {
            AnnulDialog(
                state = open,
                movement = loaded.movement,
                clientName = loaded.account.client.name,
                clientBalance = loaded.account.balanceCop,
                onReasonChange = viewModel::onAnnulReasonChange,
                onConfirm = viewModel::confirmAnnul,
                onDismiss = viewModel::dismissAnnul,
            )
        }
    }
}

/** Valor con el que la corrección avisa a la cuenta de que acaba de guardar. */
const val SAVED_CORRECTION = "CORRECTED"

@Composable
private fun MovementContent(loaded: MovementDetailState.Loaded, onViewChanges: () -> Unit, reserveNavigationBar: Boolean) {
    val movement = loaded.movement
    val isPayment = movement.type == MovementType.ABONO
    val annulled = !movement.active
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val typeLabel = movementTypeLabel(movement.type, movement.origin)
    val amount = formatSignedCop(movement.amountCop, positive = !isPayment)
    val summary = stringResource(
        if (annulled) R.string.movement_detail_a11y_annulled else R.string.movement_detail_a11y_active,
        typeLabel,
        formatCop(movement.amountCop).removePrefix("$"),
        loaded.account.client.name,
    )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .then(if (reserveNavigationBar) Modifier.navigationBarsPadding() else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp).semantics(mergeDescendants = true) { contentDescription = summary },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(loaded.account.client.name, style = MaterialTheme.typography.labelLarge, color = muted)
                Text(
                    if (annulled) stringResource(R.string.history_label_annulled, typeLabel) else typeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    amount,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = if (annulled) FontWeight.Normal else FontWeight.Bold,
                    textDecoration = if (annulled) TextDecoration.LineThrough else null,
                    color = when {
                        annulled -> muted
                        isPayment -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (annulled) {
                    Text(stringResource(R.string.history_annulled_note), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                if (movement.version > 1) {
                    Text(stringResource(R.string.history_edited), style = MaterialTheme.typography.labelMedium, color = muted)
                }
            }
        }
        Field(stringResource(R.string.movement_detail_date), formatDayLong(movement.date))
        Field(stringResource(R.string.movement_detail_description), movement.description ?: stringResource(R.string.movement_detail_no_description))
        Field(stringResource(R.string.movement_detail_status), statusLabel(movement.active))
        Field(stringResource(R.string.movement_detail_created), formatDateTime(movement.createdAtMillis))
        Field(stringResource(R.string.movement_detail_modified), formatDateTime(movement.modifiedAtMillis))
        if (!loaded.account.client.active) {
            // Sin «Corregir» ni «Anular»: se explica por qué en lugar de ocultarlo sin más.
            Text(
                stringResource(R.string.movement_detail_client_archived),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loaded.revisionCount > 0) {
            OutlinedButton(onClick = onViewChanges, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.movement_action_changes), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AnnulDialog(
    state: AnnulState.Open,
    movement: MovementEntity,
    clientName: String,
    clientBalance: Long,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Saldo que quedaría: un fiado anulado lo reduce y un abono anulado lo aumenta. Si fuera negativo, la
    // anulación se rechazará al confirmar (con el abono afectado) y no se muestra un saldo imposible.
    val newBalance = try {
        if (movement.type == MovementType.FIADO) Math.subtractExact(clientBalance, movement.amountCop) else Math.addExact(clientBalance, movement.amountCop)
    } catch (e: ArithmeticException) {
        null
    }
    val reasonMessage = when (state.reasonError) {
        ReasonError.REQUIRED -> stringResource(R.string.reason_error_required)
        ReasonError.TOO_LONG -> pluralStringResource(R.plurals.reason_error_too_long, MovementRules.REASON_MAX, MovementRules.REASON_MAX)
        null -> null
    }
    val failureMessage = state.rejection?.let { annulRejectionText(it) } ?: state.failure?.let {
        stringResource(
            when (it) {
                AnnulFailure.SAVE_FAILED -> R.string.annul_error_save
                AnnulFailure.VERSION_CONFLICT -> R.string.annul_error_version
                AnnulFailure.ALREADY_ANNULLED -> R.string.annul_error_already
                AnnulFailure.CLIENT_ARCHIVED -> R.string.movement_error_client_archived_change
                AnnulFailure.UNAVAILABLE -> R.string.annul_error_unavailable
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.annul_title)) },
        text = {
            Column(
                Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        R.string.annul_text,
                        movementTypeInSentence(movement.type, movement.origin),
                        formatCop(movement.amountCop),
                        formatDayShort(movement.date),
                        clientName,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                newBalance?.takeIf { it >= 0 }?.let { Text(stringResource(R.string.annul_balance, clientName, formatCop(it)), style = MaterialTheme.typography.bodyLarge) }
                Text(stringResource(R.string.annul_note), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = state.reason,
                    onValueChange = onReasonChange,
                    label = { Text(stringResource(R.string.annul_reason_label)) },
                    isError = reasonMessage != null,
                    supportingText = reasonMessage?.let { message -> { Text(message) } },
                    enabled = !state.saving,
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                failureMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !state.saving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (state.saving) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                        Text(stringResource(R.string.annul_saving))
                    }
                } else {
                    Text(stringResource(R.string.annul_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.annul_cancel))
            }
        },
    )
}
