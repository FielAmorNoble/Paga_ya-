package com.example.pagaya.ui.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pagaya.R
import com.example.pagaya.data.backup.RestoreFailure
import com.example.pagaya.ui.common.formatDateTime
import com.example.pagaya.ui.common.formatCop

/** Conserva Material, tamaños y lenguaje de Respaldo. La confirmación muestra el archivo privado
 * validado, nunca vuelve a resolver el URI del proveedor.
 */
@Composable
internal fun RestoreSection(backupBusy: Boolean, onBusy: (Boolean) -> Unit,
    vm: RestoreViewModel = viewModel(factory = RestoreViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.chosen(it?.toString()) }
    LaunchedEffect(state.busy, state.preview) { onBusy(state.busy || state.preview != null) }
    BackHandler(state.applying) { /* Una aplicación confirmada termina antes de salir. */ }
    OutlinedButton(onClick = {
        if (vm.choose()) try { picker.launch(arrayOf("*/*")) } catch (e: android.content.ActivityNotFoundException) { vm.chosen(null) }
    }, enabled = !backupBusy && !state.busy && state.preview == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(stringResource(R.string.restore_from_file))
    }
    if (state.hasRecovery) OutlinedButton(onClick = vm::recoverPrevious,
        enabled = !backupBusy && !state.busy && state.preview == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.restore_previous)) }
    if (state.validating || state.applying) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(if (state.applying) R.string.restore_applying else R.string.restore_validating))
    }
    if (state.busyFailure) Text(stringResource(R.string.restore_busy), color = MaterialTheme.colorScheme.error)
    state.failure?.let { Text(stringResource(restoreFailureText(it)), color = MaterialTheme.colorScheme.error) }
    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.applying) vm.cancelPreview() },
            title = { Text(stringResource(if (preview.recovery) R.string.restore_previous else R.string.restore_preview_title)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.restore_replace_warning))
                    Text(stringResource(R.string.restore_capture, formatDateTime(preview.source.manifest.capturedAtMillis)))
                    Text(stringResource(R.string.restore_source_counts, preview.source.manifest.clients, preview.source.manifest.movements, preview.source.manifest.revisions))
                    Text(stringResource(R.string.restore_pending, formatCop(preview.source.totalPendingCop)))
                    Text(stringResource(R.string.restore_current_counts, preview.current.clients, preview.current.movements, preview.current.revisions, formatCop(preview.current.pendingCop)))
                    if (preview.emptyReplacesData) Text(stringResource(R.string.restore_empty_warning), color = MaterialTheme.colorScheme.error)
                    if (preview.current.hasData) Text(stringResource(R.string.restore_recovery_explanation))
                    if (state.changed) Text(stringResource(R.string.restore_changed), color = MaterialTheme.colorScheme.error)
                    if (state.busyFailure) Text(stringResource(R.string.restore_busy), color = MaterialTheme.colorScheme.error)
                    if (state.applying) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.restore_applying)) }
                }
            },
            confirmButton = { TextButton(onClick = vm::confirm, enabled = !state.applying) { Text(stringResource(R.string.restore_confirm)) } },
            dismissButton = { TextButton(onClick = vm::cancelPreview, enabled = !state.applying) { Text(stringResource(R.string.restore_cancel)) } },
        )
    }
}

private fun restoreFailureText(failure: RestoreFailure) = when (failure) {
    RestoreFailure.FUTURE_DATE -> R.string.restore_future
    RestoreFailure.TOO_LARGE -> R.string.restore_too_large
    RestoreFailure.INVALID -> R.string.restore_invalid
    RestoreFailure.RECOVERY -> R.string.restore_recovery_failed
    RestoreFailure.EXPIRED -> R.string.restore_expired
    RestoreFailure.INTERRUPTED -> R.string.restore_interrupted
    RestoreFailure.READ -> R.string.restore_read_failed
    RestoreFailure.STAGING, RestoreFailure.APPLY -> R.string.restore_failed
}
