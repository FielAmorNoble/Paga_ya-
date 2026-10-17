package com.example.pagaya.ui.clients

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.data.local.MovementType
import com.example.pagaya.domain.AmountError
import com.example.pagaya.domain.AmountParse
import com.example.pagaya.domain.DateError
import com.example.pagaya.domain.HistoryRejection
import com.example.pagaya.domain.MovementRules
import com.example.pagaya.ui.common.ScreenHeader
import com.example.pagaya.ui.common.accountStatusText
import com.example.pagaya.ui.common.formatCop
import com.example.pagaya.ui.common.formatDayNumeric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun MovementFormScreen(
    onSaved: (type: MovementType) -> Unit,
    onBack: () -> Unit,
    viewModel: MovementFormViewModel = viewModel(factory = MovementFormViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (state.isDirty && !state.saving) confirmDiscard = true else onBack() }

    LaunchedEffect(state.savedMovementId) { if (state.savedMovementId != null) onSaved(state.type) }
    BackHandler(enabled = state.isDirty && !state.saving) { confirmDiscard = true }

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
    if (pickingDate) {
        MovementDatePicker(
            selected = state.date,
            latest = viewModel.today,
            onPicked = { viewModel.onDateChange(it); pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }

    val isFiado = state.type == MovementType.FIADO
    Scaffold(
        topBar = {
            ScreenHeader(
                title = stringResource(if (isFiado) R.string.movement_title_fiado else R.string.movement_title_abono),
                onBack = requestBack,
            )
        },
    ) { innerPadding ->
        val account = state.account
        if (account == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                if (state.failure == MovementFormFailure.CLIENT_NOT_FOUND) {
                    Text(stringResource(R.string.movement_error_client_not_found), Modifier.padding(24.dp))
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }
        val parsed = remember(state.amountText) { MovementRules.parseAmount(state.amountText) }
        val focus = remember { FocusRequester() }
        // El foco va al valor y abre el teclado numérico, salvo que el formulario se restaure con datos.
        LaunchedEffect(Unit) { if (state.amountText.isEmpty()) focus.requestFocus() }

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
                Text(account.client.name, style = MaterialTheme.typography.titleLarge)
                Text(accountStatusText(account), style = MaterialTheme.typography.titleMedium)
            }

            val amountMessage = amountErrorText(state.amountError, state.rejection)
            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::onAmountChange,
                label = { Text(stringResource(R.string.movement_amount)) },
                prefix = { Text(stringResource(R.string.movement_amount_prefix)) },
                placeholder = { Text(stringResource(R.string.movement_amount_hint)) },
                isError = amountMessage != null,
                supportingText = (amountMessage ?: (parsed as? AmountParse.Valid)?.let {
                    stringResource(R.string.movement_amount_preview, formatCop(it.amountCop))
                })?.let { message -> { Text(message) } },
                singleLine = true,
                enabled = !state.saving,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            DateRow(
                date = state.date,
                error = dateErrorText(state.dateError, state.rejection),
                enabled = !state.saving,
                onClick = { pickingDate = true },
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text(stringResource(R.string.movement_note)) },
                isError = state.descriptionError != null,
                supportingText = state.descriptionError?.let {
                    { Text(pluralStringResource(R.plurals.movement_error_note_too_long, MovementRules.DESCRIPTION_MAX, MovementRules.DESCRIPTION_MAX)) }
                },
                minLines = 2,
                enabled = !state.saving,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            generalErrorText(state.failure, state.rejection)?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (state.saving) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.form_saving))
                    }
                } else {
                    Text(
                        stringResource(if (isFiado) R.string.movement_save_fiado else R.string.movement_save_abono),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DateRow(date: LocalDate, error: String?, enabled: Boolean, onClick: () -> Unit) {
    val formatted = formatDayNumeric(date)
    val changeDescription = stringResource(R.string.movement_date_change_description, formatted)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = changeDescription },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stringResource(R.string.movement_date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatted, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.movement_date_change))
            }
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MovementDatePicker(selected: LocalDate, latest: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // El selector trabaja con el día a medianoche UTC; solo se permite entre 1900-01-01 y hoy.
    fun LocalDate.toPickerMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.toPickerDate() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.toPickerMillis(),
        yearRange = MovementRules.MIN_DATE.year..latest.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis.toPickerDate() in MovementRules.MIN_DATE..latest

            override fun isSelectableYear(year: Int): Boolean = year in MovementRules.MIN_DATE.year..latest.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { pickerState.selectedDateMillis?.let { onPicked(it.toPickerDate()) } ?: onDismiss() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.movement_date_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.movement_date_cancel))
            }
        },
    ) { DatePicker(state = pickerState) }
}

@Composable
private fun amountErrorText(error: AmountError?, rejection: HistoryRejection?): String? = when {
    error != null -> when (error) {
        AmountError.NOT_POSITIVE -> stringResource(R.string.movement_error_amount_not_positive)
        AmountError.NOT_WHOLE -> stringResource(R.string.movement_error_amount_not_whole)
        AmountError.INVALID -> stringResource(R.string.movement_error_amount_invalid)
        AmountError.TOO_LARGE -> stringResource(R.string.movement_error_amount_too_large, formatCop(MovementRules.AMOUNT_MAX))
    }
    rejection is HistoryRejection.NoDebt -> stringResource(R.string.movement_error_no_debt)
    rejection is HistoryRejection.ExceedsBalance -> stringResource(R.string.movement_error_exceeds_balance, formatCop(rejection.balanceCop))
    else -> null
}

@Composable
private fun dateErrorText(error: DateError?, rejection: HistoryRejection?): String? = when {
    error == DateError.FUTURE -> stringResource(R.string.movement_error_date_future)
    error == DateError.TOO_OLD -> stringResource(R.string.movement_error_date_too_old)
    rejection is HistoryRejection.NegativeAtDate ->
        stringResource(if (rejection.atNewEntry) R.string.movement_error_date_insufficient else R.string.movement_error_date_breaks_later)
    else -> null
}

@Composable
private fun generalErrorText(failure: MovementFormFailure?, rejection: HistoryRejection?): String? = when {
    rejection is HistoryRejection.Overflow -> stringResource(R.string.movement_error_overflow)
    failure == MovementFormFailure.SAVE_FAILED -> stringResource(R.string.movement_error_save)
    failure == MovementFormFailure.CONFLICT -> stringResource(R.string.movement_error_conflict)
    failure == MovementFormFailure.CLIENT_NOT_FOUND -> stringResource(R.string.movement_error_client_not_found)
    failure == MovementFormFailure.CLIENT_ARCHIVED -> stringResource(R.string.movement_error_client_archived)
    else -> null
}
