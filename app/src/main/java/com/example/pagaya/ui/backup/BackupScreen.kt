package com.example.pagaya.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.data.backup.BackupFailure
import com.example.pagaya.data.backup.BackupFormat
import com.example.pagaya.data.backup.BackupStatus
import com.example.pagaya.ui.common.ScreenHeader
import com.example.pagaya.ui.common.formatDateTime

/**
 * «Respaldo» (docs/06): guardar una copia en un archivo elegido con el selector del sistema. Solo se
 * anuncia «Copia guardada» después de releer y validar el archivo. Sin restauración ni Drive todavía.
 */
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)) { uri ->
        viewModel.onDestinationChosen(uri?.toString())
    }
    LaunchedEffect(state.pickerRequest) {
        val fileName = state.pickerRequest ?: return@LaunchedEffect
        viewModel.onPickerLaunched()
        try {
            picker.launch(fileName)
        } catch (e: android.content.ActivityNotFoundException) {
            // Sin selector de documentos en el teléfono: equivale a no poder elegir destino.
            viewModel.onDestinationChosen(null)
        }
    }

    Scaffold(topBar = { ScreenHeader(title = stringResource(R.string.backup_title), onBack = onBack) }) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state.status)
            OperationMessage(state.operation, onRetry = viewModel::onRetry, onDismiss = viewModel::onMessageShown)
            Button(
                onClick = viewModel::onSave,
                enabled = !state.operation.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.backup_save_file), style = MaterialTheme.typography.titleMedium)
            }
            Explanation()
        }
    }
}

@Composable
private fun StatusCard(status: StatusState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (status) {
                StatusState.Loading -> Text(stringResource(R.string.loading))
                StatusState.Error -> Text(stringResource(R.string.backup_status_error))
                is StatusState.Loaded -> LoadedStatus(status.status)
            }
        }
    }
}

@Composable
private fun LoadedStatus(status: BackupStatus) {
    val last = status.lastExport
    if (last == null) {
        Text(stringResource(R.string.backup_never), style = MaterialTheme.typography.titleMedium)
        if (status.currentRevision > 0) UnsavedChanges()
        return
    }
    val exportedAt = formatDateTime(checkNotNull(last.verifiedAtMillis))
    val capturedAt = formatDateTime(last.capturedAtMillis)
    Text(stringResource(R.string.backup_last_file), style = MaterialTheme.typography.bodyMedium)
    Text(exportedAt, style = MaterialTheme.typography.titleMedium)
    // Fecha de captura además de la de verificación cuando difieren (docs/05).
    if (capturedAt != exportedAt) {
        Text(stringResource(R.string.backup_captured_at, capturedAt), style = MaterialTheme.typography.bodyMedium)
    }
    if (status.hasUnsavedChanges) {
        UnsavedChanges()
    } else {
        Text(stringResource(R.string.backup_up_to_date), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UnsavedChanges() {
    Text(
        stringResource(R.string.backup_unsaved_changes),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun OperationMessage(operation: BackupOperation, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
    when (operation) {
        BackupOperation.Idle -> Unit
        BackupOperation.Preparing -> Progress(stringResource(R.string.backup_preparing), modifier)
        is BackupOperation.ChoosingDestination -> Progress(stringResource(R.string.backup_choosing), modifier)
        BackupOperation.Writing -> Progress(stringResource(R.string.backup_writing), modifier)
        BackupOperation.Verifying -> Progress(stringResource(R.string.backup_verifying), modifier)
        is BackupOperation.Saved -> Card(
            modifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.backup_saved), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.backup_saved_detail), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.backup_ok))
                }
            }
        }
        is BackupOperation.Failed -> Card(
            modifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(failureText(operation.failure)), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.backup_failed_kept), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_ok)) }
                    if (operation.canRetry) {
                        OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.backup_retry_other_place))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(text: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun failureText(failure: BackupFailure): Int = when (failure) {
    BackupFailure.PREPARATION -> R.string.backup_error_preparation
    BackupFailure.TOO_LARGE -> R.string.backup_error_too_large
    BackupFailure.EXPIRED -> R.string.backup_error_expired
    BackupFailure.WRITE -> R.string.backup_error_write
    BackupFailure.UNCONFIRMED -> R.string.backup_error_unconfirmed
    BackupFailure.VERIFICATION -> R.string.backup_error_verification
    BackupFailure.INTERRUPTED -> R.string.backup_error_interrupted
}

@Composable
private fun Explanation() {
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_privacy), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.backup_outside_phone), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.backup_cloud_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
