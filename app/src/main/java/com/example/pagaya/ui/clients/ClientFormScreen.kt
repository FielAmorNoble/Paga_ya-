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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.domain.ClientRules
import com.example.pagaya.domain.NameError
import com.example.pagaya.domain.NoteError
import com.example.pagaya.domain.PhoneError
import com.example.pagaya.ui.common.ScreenHeader

@Composable
fun ClientFormScreen(
    onSaved: (clientId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: ClientFormViewModel = viewModel(factory = ClientFormViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (state.isDirty && !state.saving) confirmDiscard = true else onBack() }

    LaunchedEffect(state.savedClientId) { state.savedClientId?.let(onSaved) }
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

    Scaffold(
        topBar = {
            ScreenHeader(
                title = stringResource(if (state.isEdit) R.string.form_title_edit else R.string.form_title_new),
                onBack = requestBack,
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.form_name)) },
                placeholder = { Text(stringResource(R.string.form_name_hint)) },
                isError = state.nameError != null,
                supportingText = (state.nameError?.let { nameErrorText(it) } ?: homonymText(state.homonymWarning))
                    ?.let { message -> { Text(message) } },
                singleLine = true,
                enabled = !state.saving,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.phone,
                onValueChange = viewModel::onPhoneChange,
                label = { Text(stringResource(R.string.form_phone)) },
                isError = state.phoneError != null,
                supportingText = state.phoneError?.let { error -> { Text(phoneErrorText(error)) } },
                singleLine = true,
                enabled = !state.saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                label = { Text(stringResource(R.string.form_note)) },
                isError = state.noteError != null,
                supportingText = state.noteError?.let { error -> { Text(noteErrorText(error)) } },
                minLines = 3,
                enabled = !state.saving,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            state.failure?.let { failure ->
                Text(
                    text = stringResource(
                        when (failure) {
                            FormFailure.SAVE_FAILED -> R.string.form_error_save
                            FormFailure.CONFLICT -> R.string.form_error_conflict
                            FormFailure.NOT_FOUND -> R.string.form_error_not_found
                        },
                    ),
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
                    Text(stringResource(R.string.form_save), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun nameErrorText(error: NameError) = when (error) {
    NameError.REQUIRED -> stringResource(R.string.form_error_name_required)
    NameError.TOO_LONG -> pluralStringResource(R.plurals.form_error_name_too_long, ClientRules.NAME_MAX, ClientRules.NAME_MAX)
}

@Composable
private fun phoneErrorText(error: PhoneError) = when (error) {
    PhoneError.TOO_LONG -> pluralStringResource(R.plurals.form_error_phone_too_long, ClientRules.PHONE_MAX, ClientRules.PHONE_MAX)
    PhoneError.INVALID -> stringResource(R.string.form_error_phone_invalid)
}

@Composable
private fun noteErrorText(error: NoteError) = when (error) {
    NoteError.TOO_LONG -> pluralStringResource(R.plurals.form_error_note_too_long, ClientRules.NOTE_MAX, ClientRules.NOTE_MAX)
}

@Composable
private fun homonymText(show: Boolean): String? = if (show) stringResource(R.string.form_homonym_warning) else null
