package com.example.pagaya.ui.clients

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.domain.AmountError
import com.example.pagaya.domain.AmountParse
import com.example.pagaya.domain.DateError
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.domain.ReasonError
import com.example.pagaya.ui.common.ScreenHeader
import com.example.pagaya.ui.common.formatCop

@Composable
fun MovementEditScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: MovementEditViewModel = viewModel(factory = MovementEditViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val busy = state.saving || state.reviewing || state.preview != null
    val requestBack = { if (state.isDirty && !state.saving) confirmDiscard = true else onBack() }

    LaunchedEffect(state.savedVersion) { if (state.savedVersion != null) onSaved() }
    BackHandler(enabled = state.isDirty && !state.saving && state.preview == null) { confirmDiscard = true }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.form_discard_title)) },
            text = { Text(stringResource(R.string.form_discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) { Text(stringResource(R.string.form_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.form_discard_cancel)) }
            },
        )
    }
    val date = state.date
    if (pickingDate && date != null) {
        MovementDatePicker(
            selected = date,
            latest = viewModel.today,
            onPicked = { viewModel.onDateChange(it); pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
    state.preview?.let { preview ->
        CorrectionPreviewDialog(
            preview = preview,
            saving = state.saving,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissPreview,
        )
    }

    Scaffold(topBar = { ScreenHeader(title = stringResource(R.string.edit_title), onBack = requestBack) }) { innerPadding ->
        val original = state.original
        if (original == null || date == null || state.failure.blocksForm()) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                if (state.loading) {
                    CircularProgressIndicator()
                } else {
                    Text(editFailureText(state.failure ?: EditFailure.NOT_FOUND), style = MaterialTheme.typography.titleMedium)
                }
            }
            return@Scaffold
        }
        val parsed = remember(state.amountText) { MovementRules.parseAmount(state.amountText) }
        val rejection = state.rejection

        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.clientName, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(
                        R.string.edit_fixed_data,
                        movementTypeLabel(original.type, original.origin),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val amountMessage = editAmountMessage(state.amountError, rejection)
            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::onAmountChange,
                label = { Text(stringResource(R.string.movement_amount)) },
                prefix = { Text(stringResource(R.string.movement_amount_prefix)) },
                isError = amountMessage != null,
                supportingText = (amountMessage ?: (parsed as? AmountParse.Valid)?.let {
                    stringResource(R.string.edit_amount_preview, formatCop(it.amountCop))
                })?.let { message -> { Text(message) } },
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            val dateMessage = when {
                state.dateError == DateError.FUTURE -> stringResource(R.string.movement_error_date_future)
                state.dateError == DateError.TOO_OLD -> stringResource(R.string.movement_error_date_too_old)
                rejection is HistoryRejection.NegativeAtDate -> stringResource(R.string.movement_error_date_insufficient)
                else -> null
            }
            DateRow(date = date, error = dateMessage, enabled = !busy, onClick = { pickingDate = true })

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text(stringResource(R.string.movement_note)) },
                isError = state.descriptionError != null,
                supportingText = state.descriptionError?.let {
                    { Text(pluralStringResource(R.plurals.movement_error_note_too_long, MovementRules.DESCRIPTION_MAX, MovementRules.DESCRIPTION_MAX)) }
                },
                minLines = 2,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            val reasonMessage = when (state.reasonError) {
                ReasonError.REQUIRED -> stringResource(R.string.reason_error_required)
                ReasonError.TOO_LONG -> pluralStringResource(R.plurals.reason_error_too_long, MovementRules.REASON_MAX, MovementRules.REASON_MAX)
                null -> null
            }
            OutlinedTextField(
                value = state.reason,
                onValueChange = viewModel::onReasonChange,
                label = { Text(stringResource(R.string.edit_reason_label)) },
                placeholder = { Text(stringResource(R.string.edit_reason_hint)) },
                isError = reasonMessage != null,
                supportingText = reasonMessage?.let { message -> { Text(message) } },
                minLines = 2,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            val generalMessage = when {
                rejection is HistoryRejection.UncoveredAbono || rejection is HistoryRejection.Overflow -> editRejectionText(rejection)
                state.noChanges -> stringResource(R.string.edit_no_changes)
                state.failure != null -> editFailureText(state.failure!!)
                else -> null
            }
            generalMessage?.let {
                Text(
                    text = it,
                    color = if (state.noChanges) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Button(
                onClick = viewModel::review,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.reviewing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.edit_reviewing))
                    }
                } else {
                    Text(stringResource(R.string.edit_review), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun EditFailure?.blocksForm() =
    this == EditFailure.NOT_FOUND || this == EditFailure.ALREADY_ANNULLED || this == EditFailure.VERSION_CONFLICT

@Composable
private fun editAmountMessage(error: AmountError?, rejection: HistoryRejection?): String? = when {
    error != null -> when (error) {
        AmountError.NOT_POSITIVE -> stringResource(R.string.movement_error_amount_not_positive)
        AmountError.NOT_WHOLE -> stringResource(R.string.movement_error_amount_not_whole)
        AmountError.INVALID -> stringResource(R.string.movement_error_amount_invalid)
        AmountError.TOO_LARGE -> stringResource(R.string.movement_error_amount_too_large, formatCop(MovementRules.AMOUNT_MAX))
    }
    rejection is HistoryRejection.NoDebt || rejection is HistoryRejection.ExceedsBalance -> editRejectionText(rejection)
    else -> null
}

@Composable
private fun editFailureText(failure: EditFailure): String = stringResource(
    when (failure) {
        EditFailure.SAVE_FAILED -> R.string.edit_error_save
        EditFailure.VERSION_CONFLICT -> R.string.edit_error_version
        EditFailure.ALREADY_ANNULLED -> R.string.edit_error_annulled
        EditFailure.CONFLICT -> R.string.edit_error_conflict
        EditFailure.CLIENT_ARCHIVED -> R.string.movement_error_client_archived
        EditFailure.NOT_FOUND -> R.string.movement_detail_not_found
    },
)

/** Los datos que cambiarán, antes de guardar. */
@Composable
private fun CorrectionPreviewDialog(preview: CorrectionPreview, saving: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                preview.changes.forEach { change ->
                    Text(fieldChangeText(change), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.edit_confirm_reason, preview.reason), style = MaterialTheme.typography.bodyLarge)
                preview.newBalanceCop?.let {
                    Text(stringResource(R.string.edit_confirm_balance, preview.clientName, formatCop(it)), style = MaterialTheme.typography.bodyLarge)
                }
                Text(stringResource(R.string.edit_confirm_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                if (saving) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.edit_saving))
                    }
                } else {
                    Text(stringResource(R.string.edit_confirm_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.edit_confirm_back))
            }
        },
    )
}
